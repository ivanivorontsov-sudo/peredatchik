import java.util.Properties
plugins { id("com.android.application") }

val kp = Properties().apply { val f = rootProject.file("key.properties"); if (f.exists()) f.inputStream().use { load(it) } }

android {
    namespace = "ru.peredatchik.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.peredatchik.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            if (kp.isNotEmpty()) {
                storeFile = rootProject.file(kp.getProperty("storeFile"))
                storePassword = kp.getProperty("storePassword"); keyAlias = kp.getProperty("keyAlias")
                keyPassword = kp.getProperty("keyPassword"); storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
dependencies {
    implementation(project(":core"))
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")
}
