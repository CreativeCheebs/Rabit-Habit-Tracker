plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.sqldelight)
}
kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { ios -> ios.binaries.framework { baseName = "ComposeApp"; isStatic = true } }
    sourceSets { commonMain.dependencies {
        implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3); implementation(compose.ui)
        implementation(libs.kotlinx.coroutines); implementation(libs.kotlinx.serialization.json)
    }
      androidMain.dependencies { implementation("androidx.activity:activity-compose:1.11.0"); implementation(libs.sqldelight.android.driver) }
      iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
      commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android { namespace = "app.habitlab"; compileSdk = 36; defaultConfig { applicationId = "app.habitlab"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" } }
sqldelight { databases { create("HabitDatabase") { packageName.set("app.habitlab.db") } } }
