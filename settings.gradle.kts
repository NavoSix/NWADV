pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "nwadv"

include(":core", ":desktop")

// The Android module needs the Android SDK. It is included automatically when an SDK is
// available (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties), so the shared game code
// can still be built and tested on machines without one.
val localProps = file("local.properties")
val hasSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasSdk) include(":android")
