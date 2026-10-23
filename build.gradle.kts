// Dbrij Resolve for Android: a library, built and published on its own. JitPack builds
// a tag of the repo this folder is pushed to; see README.md.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    `maven-publish`
}

group = "com.dbrij"
version = "0.1.0"

android {
    namespace = "com.dbrij.resolve"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // ComponentActivity and the file picker contract, for screenshots on tickets.
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.dbrij"
            artifactId = "resolve"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
