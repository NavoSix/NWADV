plugins {
    id("com.android.application") version "8.7.3"
}

val gdxVersion: String by project
val natives: Configuration by configurations.creating

android {
    namespace = "com.northwoodsadv.ride"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.northwoodsadv.ride"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src/main/java"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("../assets"))
            jniLibs.setSrcDirs(listOf("libs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Signed with the debug key so CI artifacts install directly; use your own key for the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86_64")) {
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-$abi")
    }
}

// libGDX ships its native libraries as jars; unpack them where Android expects .so files.
val copyAndroidNatives by tasks.registering {
    doFirst {
        natives.files.forEach { jar ->
            val abi = jar.name.substringAfter("natives-").removeSuffix(".jar")
            val outDir = file("libs/$abi").apply { mkdirs() }
            copy {
                from(zipTree(jar))
                into(outDir)
                include("*.so")
            }
        }
    }
}
tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach { dependsOn(copyAndroidNatives) }
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyAndroidNatives) }
