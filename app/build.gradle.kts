import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties (gitignored) next to the root build file, or
// MB_KEYSTORE_* Gradle properties (CI). Without either, release builds are debug-signed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    (findProperty("MB_KEYSTORE_$key") as String?) ?: keystoreProps.getProperty(key)

android {
    namespace = "com.cedrickgd.morgenbriefing"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cedrickgd.morgenbriefing"
        minSdk = 24
        targetSdk = 36
        // Bumped by scripts/release.ps1 (gradle.properties: MB_VERSION_CODE / MB_VERSION_NAME).
        versionCode = (findProperty("MB_VERSION_CODE") as String?)?.toInt() ?: 1
        versionName = (findProperty("MB_VERSION_NAME") as String?) ?: "1.0.0"

        // Where the cloud routine publishes the daily briefs (private repo, data branch).
        buildConfigField("String", "BRIEF_OWNER", "\"CedrickGD\"")
        buildConfigField("String", "BRIEF_REPO", "\"Morgenbriefing\"")
        buildConfigField("String", "BRIEF_BRANCH", "\"briefs\"")

        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("PASSWORD")
                keyAlias = signingValue("ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // Parallel QA installs can use their own id: -PMB_ID_SUFFIX=.qa1
            applicationIdSuffix = (findProperty("MB_ID_SUFFIX") as String?) ?: ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API 24/25
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Jackson reaches the Anthropic SDK's JSON model (com.anthropic.core, Values.kt) only through
// annotations, so R8 does not see that it is used (proguard-rules.pro, "Anthropic Java SDK").
// Fail the release build when R8 removed one of these classes anyway: in the app every Claude
// request would then fail before it reaches the network.
val r8KeptJsonModel = Regex(
    """com\.anthropic\.core\.(ExcludeMissing|KnownValue|MultipartField|BaseSerializer|BaseDeserializer|""" +
        """Json(Field|Value|Missing|Null|Boolean|Number|String|Array|Object))(\$.*)?""",
)
tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    val usage = layout.buildDirectory.file("outputs/mapping/release/usage.txt")
    doLast {
        val file = usage.get().asFile
        if (!file.exists()) return@doLast
        val removed = file.readLines().filter { !it.startsWith(" ") && r8KeptJsonModel.matches(it.trim()) }
        check(removed.isEmpty()) {
            "R8 removed classes that Jackson needs to (de)serialize Claude requests: $removed – see proguard-rules.pro"
        }
    }
}
