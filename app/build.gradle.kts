plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.dogukan.bigmatch"
    compileSdk = 35
    defaultConfig { applicationId = "com.dogukan.bigmatch"; minSdk = 29; targetSdk = 35; versionCode = 3; versionName = "0.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
