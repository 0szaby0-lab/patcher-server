plugins {
    kotlin("jvm") version "1.9.22"
}

group = "app.revanced"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://jitpack.io")
    maven {
        name = "githubPackages"
        url = uri("https://maven.pkg.github.com/revanced/registry")
        credentials(PasswordCredentials::class)
    }
}

dependencies {
    implementation("app.revanced:revanced-patcher:21.0.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes(
            "name" to "LO Boss Mode Patches",
            "version" to version,
            "description" to "Hardware-locked subscription patch for YouTube",
            "author" to "LO"
        )
    }
}
