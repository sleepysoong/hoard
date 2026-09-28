import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Load version properties dynamically (bumped by release workflow)
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
}
val verCode = versionProps.getProperty("versionCode", "1").toInt()
val verName = versionProps.getProperty("versionName", "1.0.0")

android {
    namespace = "com.sleepysoong.hoard"
    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "hoardhoard"
            keyAlias = "hoard"
            keyPassword = "hoardhoard"
        }
    }

    defaultConfig {
        applicationId = "com.sleepysoong.hoard"
        minSdk = 31
        targetSdk = 35
        versionCode = verCode
        versionName = verName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/gradle/incremental.annotation.processors"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Low-spec: one Robolectric JVM, small heap, serial GC, capped CPU use.
                it.maxHeapSize = "768m"
                // Fresh JVM every 40 classes: Robolectric sandboxes pile up native memory otherwise.
                it.setForkEvery(40)
                it.maxParallelForks = 1
                it.jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:TieredStopAtLevel=1")
                // Flow tests dump conversation transcripts here (reviewable artifact).
                it.systemProperty("hoard.artifacts", layout.buildDirectory.dir("test-artifacts").get().asFile.path)
                // Robolectric unpacks a ~200 MB native runtime into java.io.tmpdir per JVM and
                // never removes it. On machines where /tmp is tmpfs (RAM) that piled up to GBs
                // and got the dev box OOM-killed; keep it on disk under build/, wiped by clean.
                val tmp = layout.buildDirectory.dir("test-tmp").get().asFile.apply { mkdirs() }
                it.systemProperty("java.io.tmpdir", tmp.path)
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// hrm-markdown pulls the JetBrains material3 wrapper, which would lift androidx
// material3 to an alpha. It only uses stable APIs (Text, MaterialTheme, Checkbox,
// HorizontalDivider...), so keep the BOM's stable material3.
configurations.configureEach {
    exclude(group = "org.jetbrains.compose.material3")
}

dependencies {
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.jsoup)
    // Only TermuxConstants' compile-time String constants are used; they are inlined into
    // our bytecode, so none of termux-shared (appcompat, guava, markwon, native libs) ships.
    compileOnly(libs.termux.shared) { isTransitive = false }
    implementation(libs.hrm.markdown.parser)
    implementation(libs.hrm.markdown.runtime)
    implementation(libs.hrm.markdown.renderer)
    implementation(libs.hrm.latex.renderer) // LatexTheme (transparent math background)
    implementation(libs.hrm.codehighlight.render) // light/dark code themes

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.window)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
