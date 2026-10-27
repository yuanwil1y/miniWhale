import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "ltd.nightsky.whale"
        minSdk = 26
        targetSdk = 37
        versionCode = 200
        versionName = "3.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // ------------------------------------------------------------------ 签名
    // 签名是可选的：clone 下来直接 assembleGithubRelease 也能得到一个能装的 APK（用本机的 debug key 签）。
    //
    // 想用自己的密钥：准备一个 keystore.properties（storeFile / storePassword / keyAlias / keyPassword，
    // storeFile 相对这个 properties 文件所在目录），然后用下面任一方式告诉构建它在哪：
    //   1. 环境变量 WHALE_KEYSTORE_PROPERTIES=<路径>
    //   2. gradle 属性 whale.keystoreProperties=<路径>（写在 ~/.gradle/gradle.properties 或命令行 -P）
    //   3. 都不给时找仓库上一级的 _signing/keystore.properties（维护者自己的目录布局）
    // debug 和 release 用同一把密钥，从 debug 换成正式版能直接覆盖安装、数据不丢。
    val keystorePropsFile = (System.getenv("WHALE_KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }
        ?: (findProperty("whale.keystoreProperties") as String?)?.takeIf { it.isNotBlank() })
        ?.let { file(it) }
        ?: rootProject.file("../_signing/keystore.properties")
    val whaleSigning = if (keystorePropsFile.isFile) {
        val props = Properties()
        FileInputStream(keystorePropsFile).use { props.load(it) }
        val storePath = props.getProperty("storeFile")
        val aliasValue = props.getProperty("keyAlias")
        if (storePath != null && aliasValue != null) {
            signingConfigs.create("whale") {
                storeFile = File(keystorePropsFile.parentFile, storePath)
                storePassword = props.getProperty("storePassword")
                keyAlias = aliasValue
                keyPassword = props.getProperty("keyPassword")
            }
        } else {
            null
        }
    } else {
        null
    }
    if (whaleSigning == null) {
        logger.lifecycle("miniWhale: 没找到签名配置（WHALE_KEYSTORE_PROPERTIES / whale.keystoreProperties），release 用 debug key 签名")
    }

    buildTypes {
        release {
            // 没有自己的密钥时用 AGP 的 debug key，保证打出来的 release 包能直接安装
            signingConfig = whaleSigning ?: signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            // debug 也用同一把密钥：换正式版时能覆盖安装、数据不丢；没有就是 AGP 默认的 debug key
            whaleSigning?.let { signingConfig = it }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    // 按分发渠道区分：github 是正常使用的 APK；play 是上游留下的 Google Play 渠道（本项目不上架）
    // 两个渠道共用同一个 applicationId，Play 版不能自更新，相关入口通过 BuildConfig.IS_PLAY_STORE 关闭
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
        // 只留中文（D7）：默认 values/ 是兜底资源必须保留；其余语言（含依赖库带的）都不打包
        localeFilters += listOf("zh")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// 两个渠道的启动路径一样，profile 合并到 src/main 共用，不按变体各存一份
baselineProfile {
    mergeIntoMain = true
}

// baselineprofile 插件按 release 复制出 nonMinifiedRelease 时只关掉 isMinifyEnabled，关不掉 optimization，
// 不手动关的话生成用的包会被 R8 内联、合并类，profile 里记下的是优化后的类和方法
androidComponents {
    finalizeDsl { extension ->
        extension.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
    // 调试入口（广播命令）只在 src/debug；其它构建类型（release 及 baselineprofile 复制出来的
    // nonMinifiedRelease / benchmarkRelease）编译 src/nodebug 里的同名空实现，release 包里没有调试广播代码（W-051）
    // 公开仓库不带 src/debug（开发用的调试工具不发布），这时 debug 也编译 src/nodebug 的空实现
    val hasDebugTools = file("src/debug/java").isDirectory
    onVariants { variant ->
        if (variant.buildType != "debug" || !hasDebugTools) {
            variant.sources.java?.addStaticSourceDirectory("src/nodebug/java")
            variant.sources.kotlin?.addStaticSourceDirectory("src/nodebug/java")
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleGithubRelease", "bundlePlayRelease")
    description = "Build GitHub APK and Play AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// S10 之前 kotlin-reflect 由 ktor-server-core（:oauth）传递引入，这里曾把它从打包 classpath 排除。
// 删掉 :oauth 后保留这条排除当保险：以后若有库再把它带进来，不会让 R8 保留整个反射实现（约 0.7MB dex）。
// Koin 的模块校验（verify）在单元测试里要用 kotlin-reflect，测试 classpath 不受影响。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("Test") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    // ListenableFuture（profileinstaller、WorkManager 都要）；完整 guava 已不在依赖里
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // EXIF orientation for picked images
    implementation(libs.androidx.exifinterface)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // pebble (template engine)
    implementation(libs.pebble)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)


    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":common"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // S11 删掉的依赖（haze-glass / material3-adaptive / navigation3 等）原来把下面这些传递依赖抬到了更高的版本；
    // 钉在删除前实际解析到的版本（githubReleaseRuntimeClasspath 前后对比），运行时的依赖版本不变，离线构建也不用去下载更低的版本
    constraints {
        implementation("androidx.annotation:annotation-experimental:1.5.1")
        implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
        implementation("androidx.annotation:annotation:1.11.0")
        implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
        implementation("androidx.drawerlayout:drawerlayout:1.1.1")
        implementation("androidx.customview:customview:1.1.0")
    }

    // tests
    testImplementation(libs.junit)
    // Koin 的模块校验（verify）用反射读构造函数，需要 kotlin-reflect（仅测试）
    testImplementation(kotlin("reflect"))
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // DocxParser 用 XmlPullParser；JVM 单测里 android.jar 只有桩，换成 kxml2 的实现（F5，文档附件测试）
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
