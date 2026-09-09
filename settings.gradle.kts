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
        // usb-serial-for-android (CP210x / CH34x / FTDI / PL2303 / CDC-ACM drivers)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "flintTerm"
include(":app")
