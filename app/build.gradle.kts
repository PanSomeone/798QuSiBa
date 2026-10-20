import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// `java.util.Properties` cannot be written inline below: in a Kotlin DSL script
// `java` resolves to the project's `java` extension, which shadows the root
// package ("Unresolved reference 'util'"). The import is what puts the JDK type
// back in scope.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Where the app talks to. Resolved in this order:
 *
 *   1. `-PhuishuiBaseUrl=…`        (one-off override, handy for a proxy or a staging host)
 *   2. `huishuiBaseUrl=…`          (root gradle.properties — committed, shared by everyone)
 *   3. `huishuiBaseUrl=…`          (local.properties — per machine, and git-ignored)
 *   4. the placeholder below
 *
 * local.properties is in the chain because it is the one config file every Android
 * checkout already has and already ignores; a real host belongs there, not in a
 * committed default. The placeholder is deliberately a host that cannot resolve
 * (`.invalid` is reserved by RFC 2606), so a build that was never configured fails
 * loudly on the first request instead of silently sending credentials somewhere.
 */
val huishuiBaseUrl: String = run {
    val override = findProperty("huishuiBaseUrl") as String?
    if (override != null) return@run override

    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        val fromLocal = Properties().apply {
            localProperties.inputStream().use { load(it) }
        }.getProperty("huishuiBaseUrl")
        if (!fromLocal.isNullOrBlank()) return@run fromLocal.trim()
    }

    "https://example.invalid/"
}

/**
 * The four values a signing config needs, resolved before `android { }` opens.
 *
 * A record rather than four loose `val`s: the two configuration routes (a `-P`
 * property, or `local.properties`) use different key names for the same thing, and
 * a loose `val storeFile` forced `signingConfigs` to guess which one it was looking
 * at. It guessed wrong once already — see the KDoc on `releaseSigning` below.
 */
data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/** True when someone actually chose a host, so the UI can stop nagging about it. */
val baseUrlIsConfigured: Boolean = huishuiBaseUrl != "https://example.invalid/"

android {
    namespace = "com.killhuishenghuo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.killhuishenghuo"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"$huishuiBaseUrl\"")
        buildConfigField("boolean", "BASE_URL_CONFIGURED", baseUrlIsConfigured.toString())

        /**
         * 扫码用的 ML Kit 是 **bundled** 模型（原生库，arm64 一份就是 5.0MB），所以
         * ABI 直接决定包有多大：四个全留约 22MB，只留 arm64 约 7MB。
         *
         * 默认值就是发布值（arm64）。`x86_64` 只在 debug 变体上加回来，见文件末尾的
         * `androidComponents` —— 那一条不是给用户的，是给开发的：模拟器是 x86_64，
         * 砍掉它扫码页在模拟器上会以 `UnsatisfiedLinkError` 结束，而那正是这个功能
         * 在交付前唯一能被打开看一眼的地方。
         */
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    /**
     * Where release signing material comes from, in the same spirit as the base URL
     * above: `-P` override, then `local.properties`, then nothing.
     *
     * "Then nothing" matters. A release build with no keystore configured is left
     * unsigned and fails at `packageRelease`, which is the correct outcome: the
     * alternative — committing a keystore with a published password — is worse than
     * not shipping a signed build at all, because it looks like a real release while
     * being signable by anyone who reads the repository.
     *
     * The two routes carry their own key names and **do not mix**, which is why this
     * returns a record rather than a path. An earlier version returned just the path
     * and then had `signingConfigs` read `storePassword` / `keyAlias` from a
     * `Properties` that only ever contained the `huishuiRelease*` names — so a
     * perfectly configured `local.properties` produced `Cannot convert 'null' to
     * File` at the signing block. Both routes are exercised in the README so this
     * cannot regress into another "it works on the route I tested" bug.
     */
    val releaseSigning: ReleaseSigning? = run {
        val fromProperty = findProperty("huishuiReleaseStoreFile") as String?
        if (!fromProperty.isNullOrBlank()) {
            val property = { name: String -> (findProperty(name) as String?)?.takeIf { it.isNotBlank() } }
            val password = property("huishuiReleaseStorePassword")
            val alias = property("huishuiReleaseKeyAlias")
            if (password == null || alias == null) {
                throw GradleException(
                    "huishuiReleaseStoreFile was passed as a -P property but not " +
                        "huishuiReleaseStorePassword / huishuiReleaseKeyAlias. Either pass " +
                        "all three as -P properties, or put all three (plus an optional " +
                        "huishuiReleaseKeyPassword) in local.properties.",
                )
            }
            return@run ReleaseSigning(
                storeFile = fromProperty,
                storePassword = password,
                keyAlias = alias,
                keyPassword = property("huishuiReleaseKeyPassword") ?: password,
            )
        }

        val localProperties = rootProject.file("local.properties")
        if (localProperties.exists()) {
            val fromLocal = Properties().apply {
                localProperties.inputStream().use { load(it) }
            }
            val file = fromLocal.getProperty("huishuiReleaseStoreFile")
            if (!file.isNullOrBlank()) {
                val password = fromLocal.getProperty("huishuiReleaseStorePassword")
                val alias = fromLocal.getProperty("huishuiReleaseKeyAlias")
                // All three or none: a half-configured signing block fails deep inside
                // AGP with a message about a null alias, which reads like a bug in
                // this file rather than a gap in the reader's configuration.
                if (password.isNullOrBlank() || alias.isNullOrBlank()) {
                    throw GradleException(
                        "local.properties sets huishuiReleaseStoreFile but not " +
                            "huishuiReleaseStorePassword / huishuiReleaseKeyAlias.",
                    )
                }
                return@run ReleaseSigning(
                    storeFile = file,
                    storePassword = password,
                    keyAlias = alias,
                    keyPassword = fromLocal.getProperty("huishuiReleaseKeyPassword")
                        ?.takeIf { it.isNotBlank() } ?: password,
                )
            }
        }
        null
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = rootProject.file(signing.storeFile)
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        /**
         * R8 is left off and the app is left unshrunk on purpose, because `assembleDebug`
         * is the artefact the README tells people to install: it must be buildable and
         * debuggable with no keystore and no configuration.
         *
         * The cost of that choice is real and is worth stating rather than hiding — an
         * unminified Compose app is measurably slower than a minified one, because every
         * method it calls is still there to be called. If you want the fast build, use
         * `benchmark` (fast, installable, unshippable) or `release` (fast, shippable,
         * needs a keystore). Device measurements in the README are taken on `benchmark`
         * for exactly this reason.
         */
        debug {
            isMinifyEnabled = false

            /**
             * 模拟器是 x86_64，而扫码页在交付前唯一能被打开看一眼的地方就是模拟器，
             * 所以这个 ABI 只加在 debug 上。
             *
             * 这必须是「加」而不是「覆盖」：AGP 把 `defaultConfig` 与 buildType 的
             * `abiFilters` **取并集**，所以 `defaultConfig` 里只能放最小值（arm64），
             * 想要更多 ABI 的变体自己往上加。反过来做是行不通的 —— 第一版把两个 ABI
             * 都放进 `defaultConfig`、再在 `release` 里 `abiFilters.clear()` 想砍掉
             * x86_64，结果 release 里两个 ABI 都在，包 15.4MB。
             */
            ndk {
                abiFilters += "x86_64"
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        /**
         * The build this project actually measures on: identical to `release` except it
         * is signed with the debug key and gets an applicationId suffix, so it can be
         * built by anyone, installed alongside the debug build, and uninstalled without
         * touching the reader's real app.
         *
         * `initWith(release)` is what makes it identical. Anything added to `release`
         * is picked up here automatically, so the two cannot drift into being different
         * programs, which is the failure mode of hand-rolled "release-like" build types.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        /**
         * 由扫码库逼出来的：Quickie 的 AAR 元数据要求 core library desugaring，
         * 不开这一行 `:app:checkDebugAarMetadata` 就失败。minSdk 是 26，本来几乎
         * 用不到它 —— 这里开着的唯一原因是那一条依赖声明。
         */
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 扫码：CameraX + ML Kit，只用来读设备上的那个二维码。这是本项目唯一一个
    // 「为了一个功能而显著改变包体积」的依赖（约 +2.7MB/ABI，见 defaultConfig）。
    implementation(libs.quickie.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests exist for the two things a JVM test cannot see: that the
    // ambience is actually driven by the frame clock (and actually stops), and that
    // the R8-minified build still works — serialization and Compose both reach their
    // own code reflectively, so a `release`/`benchmark` build can pass every unit
    // test and still crash on device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Pulled in explicitly and above Compose's own request for 3.5.0; see the
    // version comment in libs.versions.toml.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
