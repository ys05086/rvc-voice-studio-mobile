# RVC Voice Studio Mobile (Android)

[RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)(Retrieval-based Voice Conversion)를 바탕으로 만든 비공식 Android 목소리 변환 앱이에요. 모든 계산을 폰 안에서 해요. 녹음과 목소리 모델은 어디에도 올라가지 않아요.

[English README](README.md)

## 할 수 있는 것

- **녹음**: 파형을 보면서 녹음하고, 다시 듣고, 원하는 곳으로 넘기고, 여러 개를 보관해요.
- **변환**: 녹음이나 오디오 파일을 내 RVC 목소리 모델로 바꿔요. 음높이, 포먼트, 음역 보호, 억양 넓히기를 조절할 수 있어요.
- **실시간**: 마이크 소리를 바로 바꿔서 이어폰으로 들려줘요. 원래 목소리와 바뀐 목소리를 같이 녹음할 수도 있고, 화면을 꺼도 계속 돌아요.
- **스냅드래곤 NPU**: NPU(Hexagon)가 있는 폰에서는 특징 모델과 디코더를 NPU로 돌려요. 디코더는 PC 변환 도구가 내 녹음 몇 분으로 맞춘 **가중치 8비트·중간값 16비트 정수** 버전을 써서 32비트와 같은 소리가 나요. NPU의 16비트 소수 모드는 소리가 거칠어져서 쓰지 않아요. NPU가 없으면 GPU(Vulkan, 32비트)나 CPU로 돌려요.
- **설정**(톱니바퀴): 목소리 모델, 계산 장치(추천 / 전부 32비트 / 직접 고르기), 진단 기록.

Galaxy S23+(스냅드래곤 8 Gen 2) 기준: 15초 녹음을 약 7초에 바꾸고, 실시간은 0.25초 블록을 한 번에 약 0.21~0.23초에 처리해요. 입에서 귀까지는 약 0.65초로 추정돼요.

## 필요한 것

- Android 10 이상, arm64 폰.
- 실시간은 NPU(Hexagon)가 있는 스냅드래곤이 필요해요 (스냅드래곤 8 Gen 2에서 확인). 파일 변환은 arm64 폰이면 다 되고, Vulkan GPU나 NPU가 있으면 빨라요.
- 변환 도구용 Windows PC (파이썬 3.10 이상, 나머지는 도구가 알아서 설치해요).

## 시작하기

1. Releases의 APK를 설치해요 (또는 아래처럼 직접 빌드).
2. 공통 모델(HuBERT, RMVPE, NPU용 HuBERT, 약 1.1 GB)을 받아요: 앱의 설정에서 **받기**. 받기가 안 되면 변환 도구의 `--common`으로 만들어서 옮겨요.
3. 목소리 모델을 PC에서 [`converter/`](converter/README.md)로 바꿔요: `rvc2phone_gui.bat`을 열고 RVC v2 48 kHz `.pth`를 추가, NPU 디코더용으로 내 녹음(WAV) 몇 분을 `converter/calib`에 넣고 **변환하기**. USB나 무선 디버깅이 켜져 있으면 **폰으로 보내기**로 앱에 바로 들어가요. 아니면 출력 폴더나 zip을 앱 설정에서 불러와요.

목소리 모델은 들어 있지 않고, 앱이 따로 내려받지도 않아요. 쓸 권리가 있는 모델과 목소리만 쓰세요.

## 빌드

- **Android**: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK, NDK 28). 키스토어(`VS_KEYSTORE`, `VS_KEYSTORE_PASSWORD`)가 없으면 디버그 키로 서명돼요.
- **CI**: `.github/workflows/build.yml`이 올릴 때마다 테스트하고 APK를 빌드해요.
- **테스트**: `./gradlew testDebugUnitTest` (신호 처리를 기준 파이프라인의 값과 비교).

## 아직 안 되는 것

- RVC v2, 48 kHz, 음높이(f0) 있는 모델만 돼요.
- Android는 다른 앱의 마이크를 바꿔치기할 수 없어서, 실시간 소리는 이어폰(또는 통화 스피커)으로 나와요.
- 실시간은 CPU와 NPU를 계속 써서 오래 쓰면 폰이 뜨거워지고, CPU가 뜨거워지면 가끔 소리가 끊길 수 있어요.
- NPU 경로는 아직 폰 한 대에서만 확인했어요.
- iPhone 버전은 따로 나올 예정이에요.

## 라이선스

MIT. [LICENSE](LICENSE)와 [NOTICE.md](NOTICE.md)를 보세요.
