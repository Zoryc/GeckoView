import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM local HTTP proxy that the Chromecast streams from.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.nanohttpd)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
}
