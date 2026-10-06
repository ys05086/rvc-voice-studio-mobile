# RVC Voice Studio Mobile

Unofficial voice conversion apps for Android and iPhone, based on [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI) (Retrieval-based Voice Conversion). Everything runs on the phone: recordings and voices are never uploaded.

[한국어 README](README.ko.md)

## What it does

- **Record** with a live waveform, then play back, seek and keep takes.
- **Convert** a recording or an audio file with your own RVC voice model: pitch shift, formant, pitch range guards and intonation.
- **Realtime** (iPhone): the microphone converted into your earphones, with an optional recording of both the original and the converted voice.
- **Hardware acceleration**: Core ML on iPhone (Neural Engine for the feature models, GPU in 32-bit for the decoder); on Android a Vulkan GPU decoder (ncnn, 32-bit) and an experimental Qualcomm NPU path (QNN).

## Requirements

- Android 10 or later on an arm64 phone. The GPU decoder needs Vulkan; the NPU option needs a recent Snapdragon.
- iPhone with iOS 16 or later. Install the IPA with SideStore or AltStore (a free Apple ID re-signs every 7 days).

## Getting started

1. Install the app (APK or IPA from the releases page, or build it yourself, see below).
2. Get the shared models (`hubert.onnx`, `rmvpe.onnx`, about 740 MB): tap **받기** (download) in the app's model settings. If the download does not work, build them with `converter/rvc2phone.bat --common` and copy them into the app's `common` folder.
3. Convert your voice model on a PC with [`converter/`](converter/README.md): drag an RVC v2 48 kHz `.pth` onto `rvc2phone.bat`, then import the resulting folder or zip in the app.

Voice models are not included and are never downloaded by the app. Use only models and voices you have the right to use.

## Building

- **Android**: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK, NDK 28). Without a keystore (`VS_KEYSTORE`, `VS_KEYSTORE_PASSWORD`) the release APK is signed with the debug key.
- **iPhone**: `cd ios && xcodegen generate`, then build the `VoiceStudio` scheme in Xcode 16 (or unsigned with `xcodebuild`, as the CI does).
- **CI**: `.github/workflows/build.yml` builds both on demand (Actions › build › Run workflow); a `v*` tag also publishes a release. The iPhone build runs on macOS runners, so it only runs when asked.
- **Tests**: `swift test` (the iPhone app's DSP against the Android golden vectors) and `./gradlew testDebugUnitTest`.

## Limitations

- Only RVC v2 models at 48 kHz with pitch (f0) are supported for now.
- Realtime conversion is iPhone-only for now. iOS does not let one app replace another app's microphone, so realtime output goes to your earphones.

## License

MIT, see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
