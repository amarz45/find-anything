plugins {
    // Lets Gradle download the JDK named in build.gradle.kts (Java 25) when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "find-anything"
