plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.firetvonwatch.data"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":domain"))
    api(libs.dadb)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
