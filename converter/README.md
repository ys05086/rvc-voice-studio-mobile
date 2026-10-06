# rvc2phone: RVC 목소리 모델을 폰 앱용으로 바꾸기

Voice Studio 폰 앱(Android, iPhone)은 RVC 목소리 모델을 ONNX 파일로 나눠서 써요. 이 도구가 `.pth` 모델을 그 파일들로 바꿔 줘요.

## 쓰는 법 (Windows)

1. 파이썬 3.10 이상을 설치하세요 ([python.org](https://www.python.org/downloads/)). 설치할 때 "Add python.exe to PATH"를 체크하세요.
2. 목소리 모델(`.pth`)을 `rvc2phone.bat` 위에 끌어다 놓으세요. 처음 한 번은 필요한 패키지를 설치하느라 몇 분 걸려요 (약 1 GB).
3. 다 되면 `out/<이름>/` 폴더와 `out/<이름>.zip`이 생겨요. 이걸 폰으로 옮겨서 앱에서 불러오세요.
   - Android: 변환 탭 › 불러오기 › 폴더나 zip 고르기
   - iPhone: 톱니바퀴 › 폴더에서 불러오기 (zip은 파일 앱에서 먼저 풀어 주세요)

### 공통 모델 (hubert.onnx, rmvpe.onnx)

모든 목소리가 같이 쓰는 모델이에요. 앱의 설정에서 "받기"로 받는 게 제일 쉬워요. 받기가 안 되면 `rvc2phone.bat --common`으로 직접 만들어서 앱의 `common` 폴더에 넣으세요.

- Android: `Android/data/com.voicestudio.phone/files/common/`
- iPhone: 파일 앱 › 나의 iPhone › Voice Studio › `common`

## 되는 모델

- RVC **v2**, **48 kHz**, 음높이(f0)가 있는 모델만 돼요. v1, 40k·32k, f0 없는 모델은 아직 안 돼요.
- 학습 중간 저장본(`G_1234.pth`, 수백 MB)이 아니라, 학습이 끝나고 `weights/`에 생기는 작은 `.pth`(수십 MB)를 넣으세요.

## 만들어지는 파일

| 파일 | 쓰임 |
| --- | --- |
| `<이름>.enc.onnx` | 말소리 특징과 음높이를 받아서 목소리의 잠재 표현을 만들어요 (두 폰 공통) |
| `<이름>.dec.onnx` | 잠재 표현에서 48 kHz 소리를 만들어요 (두 폰 공통) |
| `<이름>.dec.ncnn.param`, `.bin` | Android에서 디코더를 GPU로 돌릴 때 써요 (없으면 CPU로 돌려요) |
| `<이름>.dec_a.onnx`, `.dec_b.onnx` | 디코더를 두 조각으로 나눈 것. iPhone의 "NPU+GPU" 디코더가 앞 조각은 NPU, 뒤 조각은 GPU로 돌려요 |
| `settings.json` | 기본 설정 (음높이 0, 포먼트 0). 앱에서 바꾼 값도 여기 저장돼요 |

변환한 파일은 각각 PyTorch 원본과 비교해서 결과가 같은지 확인해요 (터미널에 "max relative error"로 나와요).

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
