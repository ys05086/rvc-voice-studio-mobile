# RVC Voice Studio Mobile

[RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)(Retrieval-based Voice Conversion)를 바탕으로 만든 비공식 Android·iPhone 목소리 변환 앱이에요. 모든 계산을 폰 안에서 해요. 녹음과 목소리 모델은 어디에도 올라가지 않아요.

[English README](README.md)

## 할 수 있는 것

- **녹음**: 파형을 보면서 녹음하고, 다시 듣고, 원하는 곳으로 넘기고, 여러 개를 보관해요.
- **변환**: 녹음이나 오디오 파일을 내 RVC 목소리 모델로 바꿔요. 음높이, 포먼트, 음역 보호, 억양 넓히기를 조절할 수 있어요.
- **실시간** (iPhone): 마이크 소리를 바로 바꿔서 이어폰으로 들려줘요. 원래 목소리와 바뀐 목소리를 같이 녹음할 수도 있어요.
- **가속**: iPhone은 Core ML (특징 모델은 뉴럴 엔진, 소리를 만드는 디코더는 GPU 32비트), Android는 Vulkan GPU 디코더 (ncnn, 32비트)와 실험용 퀄컴 NPU (QNN).

## 필요한 것

- Android 10 이상, arm64 폰. GPU 디코더는 Vulkan이 필요하고, NPU 옵션은 최신 스냅드래곤에서만 돼요.
- iOS 16 이상 iPhone. IPA는 SideStore나 AltStore로 설치해요 (무료 Apple ID는 7일마다 다시 서명해요).

## 시작하기

1. 앱을 설치해요 (Releases의 APK·IPA, 또는 아래처럼 직접 빌드).
2. 공통 모델(`hubert.onnx`, `rmvpe.onnx`, 약 740 MB)을 받아요: 앱의 모델 설정에서 **받기**. 받기가 안 되면 PC에서 `converter/rvc2phone.bat --common`으로 만들어서 앱의 `common` 폴더에 넣어요.
3. 목소리 모델을 PC에서 [`converter/`](converter/README.md)로 바꿔요: RVC v2 48 kHz `.pth`를 `rvc2phone.bat` 위에 끌어다 놓고, 생긴 폴더나 zip을 앱에서 불러와요.

목소리 모델은 들어 있지 않고, 앱이 따로 내려받지도 않아요. 쓸 권리가 있는 모델과 목소리만 쓰세요.

## 빌드

- **Android**: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK, NDK 28). 키스토어(`VS_KEYSTORE`, `VS_KEYSTORE_PASSWORD`)가 없으면 디버그 키로 서명돼요.
- **iPhone**: `cd ios && xcodegen generate` 후 Xcode 16에서 `VoiceStudio` 스킴을 빌드해요 (CI처럼 `xcodebuild`로 서명 없이 빌드해도 돼요).
- **CI**: `.github/workflows/build.yml`이 요청할 때 둘 다 빌드해요 (Actions › build › Run workflow). `v*` 태그를 올리면 릴리스도 만들어요. iPhone 빌드는 macOS 러너를 써서 요청할 때만 돌아요.
- **테스트**: `swift test` (iPhone 앱의 신호 처리를 Android 기준 값과 비교), `./gradlew testDebugUnitTest`.

## 아직 안 되는 것

- RVC v2, 48 kHz, 음높이(f0) 있는 모델만 돼요.
- 실시간 변환은 지금은 iPhone만 돼요. iOS는 다른 앱의 마이크를 바꿔치기할 수 없어서, 실시간 소리는 이어폰으로 나와요.

## 라이선스

MIT. [LICENSE](LICENSE)와 [NOTICE.md](NOTICE.md)를 보세요.
