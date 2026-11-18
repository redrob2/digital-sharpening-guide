plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.redrob2.sharpguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.redrob2.sharpguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}


// AGP's unit-test task passes the Gradle daemon's own java.library.path (built from PATH)
// to the forked test JVM. A PATH entry containing a stray double quote (seen on Windows)
// breaks that JVM's command line, so drop quote characters from it before tests run.
tasks.withType<Test>().configureEach {
    doFirst {
        val lib = System.getProperty("java.library.path")
        if (lib != null && lib.contains('"')) System.setProperty("java.library.path", lib.replace("\"", ""))
    }
}
