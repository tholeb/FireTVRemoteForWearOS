plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.core)
}
