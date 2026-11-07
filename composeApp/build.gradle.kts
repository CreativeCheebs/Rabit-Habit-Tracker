plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}
ktlint {
    // Generated SQLDelight code is not ours to format; existing violations are baselined so only new ones fail.
    filter { exclude { it.file.invariantSeparatorsPath.contains("/build/") } }
    baseline.set(file("config/ktlint/baseline.xml"))
}
kotlin {
    androidTarget()
    jvm()
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { ios -> ios.binaries.framework { baseName = "ComposeApp"; isStatic = true } }
    sourceSets { commonMain.dependencies {
        implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3); implementation(compose.ui)
        implementation(libs.kotlinx.coroutines); implementation(libs.kotlinx.serialization.json)
        implementation(libs.ktor.client.core); implementation(libs.ktor.client.negotiation); implementation(libs.ktor.client.serialization)
    }
      androidMain.dependencies { implementation("androidx.activity:activity-compose:1.11.0"); implementation(libs.sqldelight.android.driver); implementation(libs.ktor.client.okhttp) }
      iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
      jvmMain.dependencies { implementation(libs.sqldelight.sqlite.driver); implementation(libs.ktor.client.okhttp) }
      jvmTest.dependencies { implementation(libs.sqldelight.sqlite.driver); implementation(libs.kotlinx.coroutines.test) }
      commonTest.dependencies { implementation(kotlin("test")); implementation(libs.ktor.client.mock) }
    }
}
android {
    namespace = "app.habitlab"
    compileSdk = 36
    defaultConfig { applicationId = "app.habitlab"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += "debug"
        }
    }
}
sqldelight { databases { create("HabitDatabase") { packageName.set("app.habitlab.db") } } }
