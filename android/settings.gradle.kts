pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VoiceStudioPhone"
include(":app")
// Opt-in, isolated numerical experiment app; never part of the normal release.
if (providers.gradleProperty("decoderProbe").orNull == "true") include(":decoderprobe")
