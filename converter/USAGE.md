# Converter walkthrough (Windows)

[한국어](USAGE.ko.md) · [App usage](../docs/USAGE.md) · [Export details](README.md)

`rvc2phone` turns an RVC voice `.pth` into model files for Voice Studio. It does not convert a recording into another voice; the phone app does that after importing the model. Shizuku is not needed to convert models or transfer files.

The converter window currently uses Korean labels. This guide gives the exact labels beside their English meanings.

## 1. Prepare the files

- Install Python 3.10 or later with the Python launcher or Python on PATH. Use a Python version supported by the pinned packages in `requirements.txt`; the working environment used for these tools is Python 3.10.
- Extract the project to a writable folder. Leave several GB free for dependencies, downloaded weights, model output and temporary files. The first setup and shared-model download require internet access.
- Prepare an exported **RVC v2, 48 kHz, f0-enabled** voice `.pth`. v1, 32/40 kHz and non-f0 models are not supported. Use the inference model, not a large training checkpoint such as `G_1234.pth`.
- For the Snapdragon integer NPU decoder, put a few minutes of the intended speaker's normal voice in WAV files in `converter/calib/`. This is calibration input, not the target voice model. Include different levels and ordinary speech sounds. Without recordings, the integer decoder is skipped.

## 2. Open the converter

Double-click **`rvc2phone_gui.bat`** inside `converter/`. You can also drop one or more `.pth` files onto it. The launcher runs `setup.bat` to create its own `.venv` and install packages. Keep the setup window open until it finishes. Subsequent launches reuse that environment.

If Python cannot be found, install it and reopen the launcher. If package installation fails, read the terminal error, check the connection and supported Python version, then retry. No CUDA installation is required by this CPU-based export environment.

## 3. Add voices and choose options

1. Under **목소리 모델 (.pth)**, click **추가** (Add) and choose the model. Multiple models can be queued.
2. Select a row. Check the version, sample rate and f0 result. Unsupported models are not converted.
3. Set **앱에서 쓸 이름** (Name in the app). Each name becomes its own output folder; use distinct names to avoid replacing another conversion.
4. Under **보정 녹음**, choose the calibration folder with **바꾸기** (Change). Confirm that the WAV count and duration appear. The folder summary reads `.wav` files directly in that folder.
5. Choose options:

| Window option | Meaning |
| --- | --- |
| Android NPU 정수 디코더 | Build the additional `.dec.w8a16.onnx` for Snapdragon NPU; requires calibration WAVs |
| Android GPU 디코더 | Build ncnn `.param`/`.bin` decoder files for Android Vulkan GPU |
| 공통 모델도 만들기 | Build shared HuBERT, RMVPE and NPU-compatible HuBERT; normally needed once |
| 출력 폴더 | Where outputs and temporary work are stored; default is `converter/out` |

The ordinary encoder and decoder ONNX files are still produced when optional GPU/NPU output is disabled. The phone can use CPU/GPU for ordinary conversion; virtual mic's current tested configuration uses Snapdragon NPU. Exporting a model is not proof that virtual mic works on a given phone.

## 4. Convert and inspect the output

Click **변환하기** (Convert), then wait for completion. The log reports export, optional GPU generation and calibration. A failed stage is shown in the log; do not assume a partial folder is a complete conversion. Exporting shared models for the first time can take substantially longer than reopening an already prepared model.

For a voice named `MyVoice`, expect:

```text
out/
  MyVoice.zip
  MyVoice/
    MyVoice.enc.onnx
    MyVoice.dec.onnx
    settings.json
    MyVoice.dec.w8a16.onnx      # optional NPU output
    MyVoice.dec.ncnn.param     # optional GPU output
    MyVoice.dec.ncnn.bin       # optional GPU output
  common/
    hubert.onnx
    rmvpe.onnx
    hubert.htp.onnx            # created with --common
```

Other export parts may be present; keep each voice's folder together. Shared models are separate from the voice ZIP. A ZIP existing does not mean common models are already on the phone.

## 5A. Send by USB (Android)

1. Install Voice Studio on the phone, open it once, and stop any recording or conversion. Transfer restarts the app at the end.
2. Enable USB debugging in Developer options, connect a data-capable USB cable, and allow this PC's debugging prompt on the phone.
3. The converter finds `adb` in the Android SDK's `platform-tools`, its own `converter/platform-tools/adb.exe`, or PATH. Install Google's [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) if needed.
4. Refresh the device list in **폰으로 보내기 (Android)** and select the intended phone. `unauthorized` devices need confirmation on the phone; `offline` devices need reconnection.
5. Select the converted voice in the voice list. Enable the shared-model transfer option if this phone needs common files.
6. Click **골라 둔 목소리 보내기** (Send selected voice). The output folder must still point to the folder containing that conversion.
7. Wait for the success message and app restart. Voice Studio sorts the transferred files into its model directories. Select the voice in the app; manual Import is not needed.

With a selected row, that voice is sent. With no row selected, the implementation sends voices marked as successfully converted. Existing voice settings are preserved by this transfer path; model parts with the same name are replaced. Existing common files are skipped. If files remain loose, open Voice Studio again and check the converter log.

## 5B. Send by wireless debugging

1. Put PC and phone on a network that allows them to communicate. Enable **Developer options → Wireless debugging** on the phone.
2. Click **무선 연결…** in the converter.
3. On the phone, open **Pair device with pairing code**. Enter its **IP:pairing-port** into **페어링 주소** and the six-digit code into **페어링 코드**, then click **페어링**.
4. Return to the main Wireless debugging screen. Enter its **IP address and port** into **연결 주소**, then click **연결**. The connection port is often different from the pairing port.
5. Select the connected device and follow USB steps 5–7 above. A later session may need a new connection address.

This pairs the PC's ADB for file transfer. It does not start or authorize Shizuku. Set up Shizuku separately only if you want virtual mic.

## 5C. Import manually without debugging

Copy `out/MyVoice.zip` or the whole `out/MyVoice/` folder to a location accessible through the phone's file picker. Open Voice Studio → **Import → Choose ZIP file / Choose folder** and select it. Download shared models in Settings, or import the `out/common/` folder separately. Choose the imported voice and try a short file conversion first.

Use a new model name when preserving a manually imported voice's settings matters: manual Import can replace files including `settings.json`. This differs from the converter's ADB transfer, which preserves existing settings.

## Command-line examples

Open PowerShell in `converter/`. Keep paths containing spaces in quotes.

```powershell
# Build shared models once.
.\rvc2phone.bat --common

# Export a voice, with calibration and both optional backends enabled.
.\rvc2phone.bat "D:\Models\MyVoice.pth" --name MyVoice --calib "D:\Recordings\Calibration"

# Export ordinary ONNX only, without GPU generation or NPU calibration.
.\rvc2phone.bat "D:\Models\MyVoice.pth" --name MyVoice --no-ncnn --no-npu

# Choose another output location and also build common models.
.\rvc2phone.bat "D:\Models\MyVoice.pth" --name MyVoice --out "D:\PhoneModels" --common
```

For an existing environment, `.\.venv\Scripts\python.exe .\rvc2phone.py --help` lists all flags. The batch launcher pauses before exiting; calling the Python script is more convenient for automation. Dropping `.pth` on `rvc2phone.bat` uses defaults without opening the GUI.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Model rejected | Exported RVC v2, 48 kHz, f0 model rather than a training checkpoint |
| No integer decoder | NPU option enabled; usable calibration WAVs; calibration completed in the log |
| No GPU files | GPU option enabled; pnnx export completed |
| Phone not listed | ADB installed; USB data cable/debugging approval or correct wireless connection port |
| Voice imported but app says models needed | Both shared models and the voice encoder/decoder are installed |
| Wrong voice transferred | Selected row and output folder match the desired completed conversion |
| Conversion sounds noisy | Try a different compute backend and calibration data; 16-bit float NPU decoding differs from the calibrated integer decoder |

Model weights and personal calibration recordings are not included in the repository. Keep their applicable usage rights and licenses in mind. Export internals and upstream licenses are listed in [README.md](README.md).
