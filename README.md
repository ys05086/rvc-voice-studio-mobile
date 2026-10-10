# RVC Voice Studio Mobile (Android)

Unofficial voice conversion for Android phones, based on [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI) (Retrieval-based Voice Conversion). Everything runs on the phone: recordings and voices are never uploaded.

[한국어 README](README.ko.md)

## What it does

- **Record** with a live waveform, then play back, seek and keep takes.
- **Convert** a recording or an audio file with your own RVC voice model: pitch shift, formant, pitch range guards and intonation.
- **Realtime**: the microphone converted into your earphones, with an optional recording of both the original and the converted voice. It keeps running with the screen off.
- **Snapdragon NPU**: on phones with a Hexagon NPU, the feature models and the decoder run on it. The decoder uses 8-bit weights with 16-bit activations (made by the PC converter from a few minutes of your recordings), which sounds like the 32-bit decoder; the NPU's own 16-bit float mode audibly roughens the voice. Elsewhere the decoder runs on the GPU (Vulkan, 32-bit) or the CPU.
- **Settings** behind the gear: voice models, where the models run (Recommended / All 32-bit / Custom) and diagnosis logs.

Measured on a Galaxy S23+ (Snapdragon 8 Gen 2): a 15 s recording converts in about 7 s; realtime runs 0.25 s blocks in about 0.21-0.23 s each, with an estimated 0.65 s from mouth to ear.

## Requirements

- Android 10 or later on an arm64 phone.
- Realtime needs a Snapdragon with a Hexagon NPU (tested on Snapdragon 8 Gen 2). File conversion works on any arm64 phone, faster with Vulkan or the NPU.
- A Windows PC for the converter (Python 3.10 or later; it sets up its own environment).

## Getting started

1. Install the APK from the Releases page (or build it, see below).
2. Get the shared models (HuBERT, RMVPE and HuBERT for the NPU, about 1.1 GB): tap **받기** (download) in the app's settings. If the download does not work, build them with the converter (`--common`) and copy them over.
3. Convert your voice model on a PC with [`converter/`](converter/README.md): open `rvc2phone_gui.bat`, add an RVC v2 48 kHz `.pth`, put a few minutes of your own recordings (WAV) in `converter/calib` for the NPU decoder, and press **변환하기** (convert). With USB or wireless debugging on, **폰으로 보내기** (send to phone) puts the voice straight into the app; otherwise import the output folder or zip in the app's settings.

Voice models are not included and are never downloaded by the app. Use only models and voices you have the right to use.

## Building

- **Android**: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK, NDK 28). Without a keystore (`VS_KEYSTORE`, `VS_KEYSTORE_PASSWORD`) the release APK is signed with the debug key.
- **CI**: `.github/workflows/build.yml` tests and builds the APK on every push.
- **Tests**: `./gradlew testDebugUnitTest` (the signal processing against golden vectors from the reference pipeline).

## Limitations

- Only RVC v2 models at 48 kHz with pitch (f0) are supported for now.
- Android does not let one app replace another app's microphone, so realtime output goes to your earphones (or the earpiece).
- Realtime keeps the CPU and NPU busy: phones get warm in long sessions, and a hot CPU can make the voice drop out now and then.
- The NPU path is tested on one phone so far.
- An iPhone version exists and will follow separately.

## License

MIT, see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
