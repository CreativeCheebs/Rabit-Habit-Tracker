# Gradle integration examples

These snippets are reference examples only. Habit Lab already has Android, iOS, and JVM targets configured; do not copy in a Wasm target for the hosted JavaScript web app.

## Shared multiplatform module

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    jvm()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        // Put only multiplatform-compatible Compose and SQLDelight APIs in commonMain.
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.example.app.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // migrations.setFrom over time as appropriate for the installed plugin
        }
    }
}
```

Android application configuration and Compose Material 3 dependency coordinates follow the versions already pinned in the catalog. Keep Android-only dependencies out of `commonMain`.

## Quality plugins and reports

Apply ktlint and Detekt from the version catalog in each module that contains Kotlin. Configure Detekt's source sets and rules intentionally; do not silence findings with a blanket baseline. A common root task can depend on module tasks after module names are known:

```kotlin
tasks.register("quality") {
    group = "verification"
    description = "Runs configured formatting, analysis, tests, and migration checks."
    dependsOn("ktlintCheck", "detekt", "check")
}
```

Do not add assumed task paths to this aggregate until confirming them with `./gradlew tasks --all`. Keep Android instrumentation separate from web browser checks.

## Source-set driver dependencies

Declare `android-driver`, `native-driver`, and `sqlite-driver` in the corresponding Android, iOS, and JVM source sets, as the existing app does. The hosted web app persists through the server API and does not use SQLDelight.
