plugins {
    kotlin("jvm")
    application
}

val gdxVersion: String by project
val lwjglVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    implementation("org.lwjgl:lwjgl-tinyfd:$lwjglVersion")
    for (os in listOf("linux", "linux-arm64", "macos", "macos-arm64", "windows")) {
        runtimeOnly("org.lwjgl:lwjgl-tinyfd:$lwjglVersion:natives-$os")
    }
}

// The game's assets (sample routes, engine recordings) go inside the jar, so the packaged game
// runs from any folder; libGDX finds internal files on the classpath.
sourceSets.main {
    resources.srcDir(rootProject.file("assets"))
}
tasks.processResources {
    exclude("saved-routes/**", "cache/**", ".lwjgl/**")
}

application {
    mainClass.set("com.northwoodsadv.ride.desktop.DesktopLauncherKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("assets")
    isIgnoreExitValue = true
    if (System.getProperty("os.name").lowercase().contains("mac")) jvmArgs("-XstartOnFirstThread")
    // Pass extra arguments with: ./gradlew :desktop:run --args="path/to/ride.gpx"
}
