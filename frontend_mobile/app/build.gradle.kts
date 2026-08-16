plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("io.gitlab.arturbosch.detekt")
}

val releaseStorePath = providers.environmentVariable("CAMPUSLINK_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("CAMPUSLINK_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CAMPUSLINK_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CAMPUSLINK_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "com.campuslink.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.campuslink.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions += "environment"
    productFlavors {
        create("local") {
            dimension = "environment"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
            buildConfigField("String", "MAIL_API_BASE_URL", "\"http://10.0.2.2:5000/\"")
        }
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "API_BASE_URL", "\"https://campuslink.tokeninf.xyz/\"")
            buildConfigField("String", "MAIL_API_BASE_URL", "\"https://campuslink.tokeninf.xyz/\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://campuslink.tokeninf.xyz/\"")
            buildConfigField("String", "MAIL_API_BASE_URL", "\"https://campuslink.tokeninf.xyz/\"")
        }
    }

    signingConfigs {
        create("campuslinkRelease") {
            releaseStorePath?.let { storeFile = file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("campuslinkRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val validateProdReleaseSigning by tasks.registering {
    group = "verification"
    description = "验证 prodRelease 正式签名环境变量和密钥文件"
    doLast {
        require(
            listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
                .all { !it.isNullOrBlank() },
        ) {
            "prodRelease 必须通过 CAMPUSLINK_RELEASE_* 环境变量提供正式签名配置"
        }
        require(file(requireNotNull(releaseStorePath)).isFile) {
            "CAMPUSLINK_RELEASE_STORE_FILE 指向的签名文件不存在"
        }
    }
}

tasks.matching { it.name == "preProdReleaseBuild" }.configureEach {
    dependsOn(validateProdReleaseSigning)
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    // 固定在与 API 36 / AGP 8.13 兼容的 Compose 版本，避免未来 BOM 静默抬升到 API 37。
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.commonmark:commonmark:0.25.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
