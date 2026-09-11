import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val rustTargets = (project.findProperty("rustTargets") as String? ?: "arm64-v8a,x86_64").split(",")
val rustProfile = project.findProperty("rustProfile") as String? ?: "release"
// Which VT engine the Rust core is compiled with: "alacritty" (default) or
// "ghostty" (libghostty-vt, which needs a Zig toolchain on PATH).
val termBackend = (project.findProperty("termBackend") as String? ?: "alacritty").lowercase()
require(termBackend in setOf("alacritty", "ghostty")) {
    "termBackend must be alacritty or ghostty, got '${'$'}termBackend'"
}
val cargoRoot = rootProject.projectDir
val ndkDirPath: String = System.getenv("ANDROID_NDK_HOME")
    ?: file("${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/Android/Sdk"}/ndk").listFiles()
        ?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
    ?: error("NDK not found; set ANDROID_NDK_HOME")

android {
    namespace = "dev.flint.term"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.flint.term"
        minSdk = 26
        targetSdk = 37
        // The one number a phone compares when deciding whether an APK is an
        // update. Derived from the name so cutting a release cannot forget it:
        // 0.1.0 -> 100, 0.2.3 -> 2003, 1.0.0 -> 10000.
        versionName = "0.1.3"
        versionCode = versionName!!.split(".").map { it.takeWhile(Char::isDigit).toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        ndk { abiFilters += rustTargets }

        // What the About screen can say about this build beyond a version
        // number: the commit it was cut from, and the versions of the Rust
        // crates the core is built on, read from Cargo.lock so they cannot
        // drift from what was actually linked.
        val gitSha = providers.exec {
            workingDir = rootProject.projectDir
            commandLine("git", "rev-parse", "--short=10", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        val lock = rootProject.file("Cargo.lock").takeIf { it.exists() }?.readText().orEmpty()
        fun locked(crate: String): String =
            Regex("name = \"" + Regex.escape(crate) + "\"\nversion = \"([^\"]+)\"").find(lock)?.groupValues?.get(1) ?: ""
        val core = listOf("russh", "russh-sftp", "alacritty_terminal", "boringtun", "smoltcp", "uniffi", "tokio", "ring")
            .joinToString(";") { "$it=${locked(it)}" }
        buildConfigField("String", "CORE_VERSIONS", "\"$core\"")
    }

    // Release signing: keystore.properties in the repo root (see build-apk.sh, which creates one),
    // or KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD in the environment.
    // Falls back to the debug key so `assembleRelease` always produces an installable APK.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun ks(name: String, env: String) = ksProps.getProperty(name) ?: System.getenv(env)
    val ksFile = ks("storeFile", "KEYSTORE_FILE")?.let { rootProject.file(it) }
    if (ksFile != null && ksFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = ksFile
                storePassword = ks("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = ks("keyAlias", "KEY_ALIAS") ?: "androidterm"
                keyPassword = ks("keyPassword", "KEY_PASSWORD") ?: ks("storePassword", "KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // en-XA is accented and about a third longer, en-XB is right to
            // left: between them they show which rows a translation breaks.
            isPseudoLocalesEnabled = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Feeds the per-app language picker on Android 13+, from whatever
        // values-* folders exist.
        generateLocaleConfig = true
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true  // compress native libs (libtailscale is 22 MB); extracted at install time
        // boringtun builds a shared library of its own for its C API, which
        // cargo-ndk dutifully copies next to ours. Nothing loads it: the crate
        // is linked into libflintterm.so, whose only NEEDED entries are
        // libtailscale and the platform. Shipping it is half a megabyte per ABI
        // of something no one can call.
        jniLibs.excludes += "**/libboringtun*.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ---- Rust core -------------------------------------------------------------

abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val crates: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val cargoToml: RegularFileProperty
    @get:Input abstract val targets: ListProperty<String>
    @get:Input abstract val profile: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val features: ListProperty<String>
    @get:Input abstract val noDefaultFeatures: Property<Boolean>
    @get:Input abstract val tailscaleLibs: Property<String>
    @get:Internal abstract val workDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        // cargo-ndk copies into this directory and never takes anything out, so
        // a library whose name carries a build hash lands beside the last one
        // instead of replacing it, and every build that changed a feature left
        // another copy behind to be packaged.
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val args = mutableListOf("cargo", "ndk")
        targets.get().forEach { args += listOf("-t", it) }
        args += listOf("-o", outputDir.get().asFile.absolutePath, "build", "-p", "flintterm")
        if (profile.get() == "release") args += "--release"
        if (noDefaultFeatures.get()) args += "--no-default-features"
        if (features.get().isNotEmpty()) args += listOf("--features", features.get().joinToString(","))
        exec.exec {
            workingDir = workDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get())
            environment("TAILSCALE_JNILIBS", tailscaleLibs.get())
            commandLine(args)
        }
    }
}

abstract class UniffiBindgenTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val crates: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val wd = workDir.get().asFile
        exec.exec {
            workingDir = wd
            commandLine("cargo", "build", "-q", "-p", "flintterm")
        }
        exec.exec {
            workingDir = wd
            commandLine(
                "cargo", "run", "-q", "-p", "uniffi-bindgen", "--",
                "generate", "--library", "target/debug/libflintterm.so",
                "--language", "kotlin", "--no-format",
                "--out-dir", outputDir.get().asFile.absolutePath,
            )
        }
    }
}

val cargoNdkBuild = tasks.register<CargoNdkTask>("cargoNdkBuild") {
    group = "rust"
    description = "Cross-compile the Rust core for Android ABIs"
    crates.set(File(cargoRoot, "crates"))
    cargoToml.set(File(cargoRoot, "Cargo.toml"))
    targets.set(rustTargets)
    profile.set(rustProfile)
    ndkDir.set(ndkDirPath)
    // Embedded Tailscale is optional: only when build-tailscale.sh produced the library for every ABI we build.
    val jniLibs = file("src/main/jniLibs")
    val haveTailscale = rustTargets.all { File(jniLibs, "$it/libtailscale.so").exists() }
    features.set(buildList {
        if (haveTailscale) add("tailscale")
        if (termBackend == "ghostty") add("ghostty")
    })
    // Only one emulator backend is reachable at runtime, so don't compile the
    // other one in: alacritty is the default feature and has to be turned off
    // explicitly for a ghostty build.
    noDefaultFeatures.set(termBackend != "alacritty")
    tailscaleLibs.set(jniLibs.absolutePath)
    workDir.set(cargoRoot)
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val uniffiBindgen = tasks.register<UniffiBindgenTask>("uniffiBindgen") {
    group = "rust"
    description = "Generate Kotlin bindings with uniffi"
    crates.set(File(cargoRoot, "crates"))
    workDir.set(cargoRoot)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgenTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkTask::outputDir)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The android.jar stub throws for every org.json call; the real thing lets a
    // unit test read back what the store wrote.
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.usb.serial)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)
}
