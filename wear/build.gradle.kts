import javax.inject.Inject
import javax.tools.ToolProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Compiles the on-TV key injector (src/injector/java) against android.jar and dexes it with
 * d8. The result is shipped as the asset `injector.dex`; the watch pushes it to the Fire TV.
 */
abstract class InjectorDexTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bootClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val buildToolsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val classesDir = File(temporaryDir, "classes").apply { deleteRecursively(); mkdirs() }
        val dexDir = File(temporaryDir, "dex").apply { deleteRecursively(); mkdirs() }
        val sources = sourceDir.get().asFileTree.matching { include("**/*.java") }.files.map { it.absolutePath }
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("Building the injector needs a JDK (javac)")
        val rc = compiler.run(
            null, null, null,
            "-source", "8", "-target", "8", "-Xlint:-options", "-nowarn",
            "-bootclasspath", bootClasspath.asPath,
            "-d", classesDir.absolutePath,
            *sources.toTypedArray(),
        )
        check(rc == 0) { "javac failed for the injector" }

        val javaHome = System.getProperty("java.home")
        val classFiles = classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.absolutePath }.toList()
        execOps.exec {
            executable = buildToolsDir.get().file("d8").asFile.absolutePath
            environment("JAVA_HOME", javaHome)
            environment("PATH", "$javaHome/bin" + File.pathSeparator + (System.getenv("PATH") ?: ""))
            args(listOf("--release", "--min-api", "22", "--output", dexDir.absolutePath) + classFiles)
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        File(dexDir, "classes.dex").copyTo(File(out, "injector.dex"), overwrite = true)
    }
}

android {
    namespace = "dev.firetvonwatch.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.firetvonwatch.wear"
        minSdk = 34
        targetSdk = 37
        // CI passes -PversionCode=<run number> -PversionName=<release tag>.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull?.removePrefix("v") ?: "1.0.0-dev"
    }

    // Release signing comes from the environment (CI secrets). Without it, release builds are
    // signed with the debug key, which is fine for sideloading on your own watch.
    val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
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
    }

    buildFeatures { compose = true }

    // Per-app language (Android 13+ / Wear OS 4+): Settings → Apps → Fire TV Remote → Language.
    androidResources { generateLocaleConfig = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(21) }

val buildInjectorDex = tasks.register<InjectorDexTask>("buildInjectorDex") {
    sourceDir.set(layout.projectDirectory.dir("src/injector/java"))
    bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
    buildToolsDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildInjectorDex, InjectorDexTask::outputDir)
    }
}

dependencies {
    implementation(project(":data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.wear.input)
    implementation(libs.wear)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.complications.ktx)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
