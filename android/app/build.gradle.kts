plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ver = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ks: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.cwk021t.precip"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cwk021t.precip"
        minSdk = 26
        targetSdk = 35
        versionCode = ver
        versionName = "1.0.$ver"
    }
    signingConfigs {
        create("release") {
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "precip"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/web"))
}

// The app bundles the same index.html that GitHub Pages serves
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.buildDirectory.dir("generated/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
