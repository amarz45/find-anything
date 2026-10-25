plugins {
    application
    alias(libs.plugins.spotless)
}

group = "io.github.amarz45"
version = "0.2.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// JavaFX ships one jar per operating system. This picks the one for the machine running the
// build, so a build made on Linux runs on Linux and a build made on Windows runs on Windows.
// JavaFX goes on the class path, not the module path: the app has no module-info.java,
// because Tika's forked parser starts its child JVM with the parent's class path.
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)

    implementation(libs.tika.pipes.fork.parser)

    implementation(platform(libs.langchain4j.bom))
    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j.ollama)

    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.picocli)
    implementation(libs.directory.watcher)

    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:${libs.versions.javafx.get()}:$javafxPlatform")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

application {
    applicationName = "findany"
    // `findany` alone prints help; `findany gui` opens the GUI (see docs/notes/handoff.md).
    mainClass = "io.github.amarz45.findanything.ui.CliApp"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
    // Tests never touch your real index or settings: FINDANY_HOME points at the build folder.
    environment("FINDANY_HOME", layout.buildDirectory.dir("test-findany-home").get().asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get()).aosp().reflowLongStrings(false)
    }
}
