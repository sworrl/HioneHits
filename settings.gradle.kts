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
rootProject.name = "MikuPlayer"
include(":app")
include(":hardware-settings")
include(":miku-sysbridge")
include(":mikuos-settings")
include(":mikuos-systemui")
include(":mikuos-launcher")
include(":fmradio")
include(":qcom-fmradio-stubs")
include(":mikuos-tv")
include(":mikuos-tools")
include(":mikuos-media")
include(":mikuos-update")
include(":voicecheck")
