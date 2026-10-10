# rvc2phone: RVC 목소리 모델을 폰 앱용으로 바꾸기

Voice Studio 폰 앱(Android, iPhone)은 RVC 목소리 모델을 ONNX 파일로 나눠서 써요. 이 도구가 `.pth` 모델을 그 파일들로 바꿔 줘요.

## 창으로 쓰기 (Windows, 권장)

`rvc2phone_gui.bat`을 두 번 누르세요 (목소리 모델 `.pth`를 이 파일 위에 끌어다 놓으면 그 모델이 담긴 채로 열려요).
처음 한 번은 필요한 패키지를 설치하느라 몇 분 걸려요 (약 1 GB).

1. **목소리 모델**: "추가"로 `.pth`를 고르세요 (여러 개 가능). 고른 모델이 되는 모델인지(v2 · 48 kHz · 음높이) 바로 보여 줘요. 앱에서 쓸 이름도 여기서 바꿔요.
2. **보정 녹음**: 내 목소리 WAV가 든 폴더 (기본 `calib`). 개수와 길이가 보여요. Android NPU 정수 디코더에 쓰여요.
3. **옵션**: NPU 정수 디코더, GPU 디코더, 공통 모델(처음 한 번). 출력 폴더.
4. **변환하기**: 단계가 위에 표시돼요.
5. **폰으로 보내기 (Android)**: USB 디버깅으로 꽂거나 "무선 연결…"로 페어링·연결한 뒤 "골라 둔 목소리 보내기". 앱 폴더에 바로 넣고, 앱이 열리면서 알아서 제자리로 옮겨요 (불러오기 필요 없음). 이미 있는 목소리면 그 목소리의 설정(음높이 등)은 그대로 둬요. adb는 Android SDK의 platform-tools에서 찾아요 (없으면 이 폴더 안 `platform-tools`에 adb.exe를 두세요).

iPhone은 출력 폴더를 파일 앱으로 옮겨서 불러오세요 (아래).

## 명령으로 쓰기 (Windows)

1. 파이썬 3.10 이상을 설치하세요 ([python.org](https://www.python.org/downloads/)). 설치할 때 "Add python.exe to PATH"를 체크하세요.
2. (Android NPU용, 권장) `calib` 폴더에 내 목소리 녹음 WAV를 몇 분 넣어 두세요. 아래 "NPU 디코더" 참고.
3. 목소리 모델(`.pth`)을 `rvc2phone.bat` 위에 끌어다 놓으세요. 처음 한 번은 필요한 패키지를 설치하느라 몇 분 걸려요 (약 1 GB).
4. 다 되면 `out/<이름>/` 폴더와 `out/<이름>.zip`이 생겨요. 이걸 폰으로 옮겨서 앱에서 불러오세요.
   - Android: 변환 탭 › 불러오기 › 폴더나 zip 고르기
   - iPhone: 톱니바퀴 › 폴더에서 불러오기 (zip은 파일 앱에서 먼저 풀어 주세요)

### 공통 모델 (hubert.onnx, rmvpe.onnx)

모든 목소리가 같이 쓰는 모델이에요. 앱의 설정에서 "받기"로 받는 게 제일 쉬워요. 받기가 안 되면 `rvc2phone.bat --common`으로 직접 만들어서 앱의 `common` 폴더에 넣으세요. `--common`은 Android NPU가 HuBERT를 통째로 돌릴 수 있게 고친 `hubert.htp.onnx`도 만들어요 (같이 넣으면 실시간 한 단계가 약 90 ms 빨라져요).

- Android: `Android/data/com.voicestudio.phone/files/common/`
- iPhone: 파일 앱 › 나의 iPhone › Voice Studio › `common`

### NPU 디코더 (Android, `<이름>.dec.w8a16.onnx`)

Android 폰의 NPU(스냅드래곤)는 16비트 소수 계산이 거칠어서 그대로 쓰면 소리가 지글거려요. 이 도구는 디코더를 **가중치 8비트·중간값 16비트 정수**로 바꾼 파일도 만들어요. Galaxy S23+에서 32비트 디코더와 거의 같은 소리(평균 스펙트럼 차이 0.14 dB)에, 15초를 7초에 바꾸고 실시간도 돌아가요. 앱은 이 파일이 있으면 알아서 써요.

이 파일을 만들려면 실제 목소리 녹음으로 맞춰 보는 과정(보정)이 필요해요.

- `calib` 폴더(이 파일 옆)에 **WAV** 녹음을 넣으세요. 몇 분이면 충분해요. 평소 말소리에 큰 소리·작은 소리·웃음·숨소리가 섞여 있으면 더 좋아요. 목소리 모델을 쓸 사람(보통 나)의 녹음이 제일 좋아요.
- 다른 폴더를 쓰려면 `rvc2phone.bat 목소리.pth --calib D:\녹음폴더`.
- 녹음이 없으면 이 파일만 건너뛰어요. 나중에 녹음을 넣고 다시 변환하면 돼요.
- 보정에는 공통 모델(HuBERT, RMVPE)이 필요해서, `out/common`에 없으면 먼저 만들어요.
- 변환 중 임시 파일(수 GB까지)은 `out/tmp`에 써요. C 드라이브가 가득 차도 괜찮아요.

## 되는 모델

- RVC **v2**, **48 kHz**, 음높이(f0)가 있는 모델만 돼요. v1, 40k·32k, f0 없는 모델은 아직 안 돼요.
- 학습 중간 저장본(`G_1234.pth`, 수백 MB)이 아니라, 학습이 끝나고 `weights/`에 생기는 작은 `.pth`(수십 MB)를 넣으세요.

## 만들어지는 파일

| 파일 | 쓰임 |
| --- | --- |
| `<이름>.enc.onnx` | 말소리 특징과 음높이를 받아서 목소리의 잠재 표현을 만들어요 (두 폰 공통) |
| `<이름>.dec.onnx` | 잠재 표현에서 48 kHz 소리를 만들어요 (두 폰 공통) |
| `<이름>.dec.ncnn.param`, `.bin` | Android에서 디코더를 GPU로 돌릴 때 써요 (없으면 CPU로 돌려요) |
| `<이름>.dec.w8a16.onnx` | Android NPU용 정수 디코더 (`calib`에 녹음이 있을 때) |
| `<이름>.dec_a.onnx`, `.dec_b.onnx` | 디코더를 두 조각으로 나눈 것. iPhone의 "NPU+GPU" 디코더가 앞 조각은 NPU, 뒤 조각은 GPU로 돌려요 |
| `settings.json` | 기본 설정 (음높이 0, 포먼트 0). 앱에서 바꾼 값도 여기 저장돼요 |

변환한 파일은 각각 PyTorch 원본과 비교해서 결과가 같은지 확인해요 (터미널에 "max relative error"로 나와요).

## iPhone NPU용 고정 길이 버전 (`static_export.py`)

Core ML은 길이가 바뀌는 모델을 조각내서 CPU로 돌리고, 뉴럴 엔진(NPU)은 고정 길이만 받아요. `static_export.py`는 앱이 쓰는 길이로 고정하고 Core ML이 모르는 연산을 바꿔 써서, 모델이 통째로 Core ML에 들어가게 만들어요. 가중치는 길이별 파일들이 하나(`*.weights`)를 같이 써요.

```bash
.venv/Scripts/python static_export.py --common            # hubert.s25600/s96320, rmvpe.s4960/s96320 + .weights
.venv/Scripts/python static_export.py 목소리.pth --name 이름  # 이름.enc.t160/t602 + model.enc.weights, 이름.dec.n200, 이름.dec_a.n200
```

만든 파일은 원래 파일들과 같은 폴더(공통은 `common`, 목소리는 그 목소리 폴더)에 넣으면 앱이 알아서 써요.

## Android NPU용 HuBERT (`htp_export.py`)

Snapdragon NPU(QNN HTP)에는 Erf 연산이 없어서, 그냥 `hubert.onnx`를 쓰면 GELU 20개가 CPU로 빠지고 모델이 21조각으로 쪼개져요. `htp_export.py`는 GELU 수식을 `Gelu` 노드 하나씩으로 합쳐요 (같은 함수, CPU 결과 동일). Galaxy S23+ 실시간 한 단계(1.6초 창)에서 HuBERT가 131~154 ms → 61 ms가 됐어요.

```bash
.venv/Scripts/python htp_export.py common/hubert.onnx --check   # -> common/hubert.htp.onnx
```

`hubert.htp.onnx`를 폰의 `common` 폴더에 넣으면, Android 앱은 앞 단계를 NPU로 돌릴 때 이 파일을 써요.

## 명령줄로 쓰기 (Windows, macOS, Linux)

```bash
python -m venv .venv
.venv/bin/pip install -r requirements.txt        # Windows: .venv\Scripts\pip
.venv/bin/python rvc2phone.py 목소리.pth [--name 이름] [--out 폴더] [--no-ncnn]
.venv/bin/python rvc2phone.py --common            # 공통 모델
```

## 출처와 라이선스

- `rvc/`: [RVC-Project](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)의 모델 코드 일부 (MIT, `rvc/LICENSE`).
- `--common`은 원본 가중치를 내려받아서 변환해요 (SHA-256 확인): ContentVec ([lengyue233/content-vec-best](https://huggingface.co/lengyue233/content-vec-best), MIT), RMVPE ([lj1995/VoiceConversionWebUI](https://huggingface.co/lj1995/VoiceConversionWebUI), MIT).
- 목소리 모델은 들어 있지 않아요. 쓸 권리가 있는 모델과 목소리만 쓰세요.
