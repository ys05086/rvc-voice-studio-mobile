// swift-tools-version:5.9
// Only for checking the iOS app's numeric code on any machine: `swift test` runs ios/DspTests against the
// golden vectors in android/app/src/test/resources/golden. The app itself is built from ios/project.yml.
import PackageDescription

let package = Package(
    name: "VoiceStudioDsp",
    targets: [
        .target(name: "VSDsp", path: "ios/VoiceStudio", sources: ["Dsp.swift"]),
        .testTarget(name: "DspTests", dependencies: ["VSDsp"], path: "ios/DspTests"),
    ]
)
