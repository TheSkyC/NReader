// Modified for NReader by TheSkyC (2026); see NOTICE.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.internal.extensions.stdlib.capitalized
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val majorVersion = 1
val minorVersion = 0
val patchVersion = 0
val buildVersion = 0
val version = "${majorVersion}.${minorVersion}.${patchVersion}"

android {
    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }
    compileSdk = 36
    defaultConfig {
        applicationId = "com.theskyc.nreader"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"\"")
        buildConfigField("String", "ISSUE_TRACKER_URL", "\"\"")
        // Format: MmPPbb
        // M: Major, m: minor, P: Patch, b: build
        versionCode =
            "%d%d%02d%02d".format(majorVersion, minorVersion, patchVersion, buildVersion).toInt()
        multiDexEnabled = true
        versionName = version
        vectorDrawables.useSupportLibrary = true
        proguardFiles("proguard-rules.pro")
    }
    flavorDimensions += "sdk"
    productFlavors {
        create("post28") {
            dimension = "sdk"
            targetSdk = 36
            minSdk = 28
        }
        create("pre28") {
            dimension = "sdk"
            targetSdk = 28
            minSdk = 26
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            resValue("string", "app_name", "NReader")
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "NReader Debug")
        }
        create("RelWithDebInfo") {
            initWith(buildTypes["release"])
            versionNameSuffix = "-relwithdebinfo"
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "RestrictedApi"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    bundle {
        language {
            // Specifies that the app bundle should not support
            // configuration APKs for language resources. These
            // resources are instead packaged with each base and
            // dynamic feature APK.
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }
    namespace = "com.maxwai.nclientv3"
    buildFeatures {
        buildConfig = true
        resValues = true
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

}

androidComponents {
    var apkFolder = layout.buildDirectory.dir("outputs/apk").get()
    var deleteFolder = tasks.register<Delete>("deleteReleaseAPKs") {
        delete(apkFolder.dir("release"))
    }
    tasks.named("assemble") {
        dependsOn(deleteFolder)
    }
    onVariants { variant ->
        val copyLegalNotices = tasks.register<CopyLegalNotices>("copy${variant.name.capitalized()}LegalNotices") {
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            noticeFile.set(rootProject.layout.projectDirectory.file("NOTICE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalNotices, CopyLegalNotices::outputDirectory)
        var suffix = ""
        if (variant.flavorName == "pre28") {
            suffix += "_pre28"
        }
        if (variant.buildType == "debug") {
            suffix += "_debug"
        } else if (variant.buildType == "RelWithDebInfo") {
            suffix += "_relwithdebinfo"
        }
        var renameTask =
            tasks.register<CreateRenamedApk>("createRenamedApk${variant.flavorName?.capitalized()}${variant.buildType?.capitalized()}") {
                this.apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
                this.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                this.versionName.set(
                    variant.outputs.single().versionName.get().substringBeforeLast("-")
                )
                this.suffix.set(suffix)
            }.get()
        tasks.whenTaskAdded {
            if (name == "assemble${variant.flavorName?.capitalized()}${variant.buildType?.capitalized()}") {
                finalizedBy(renameTask)
                dependsOn(deleteFolder)
            } else if (name == "assemble") {
                dependsOn(renameTask)
            }
        }
    }
    beforeVariants {
        it.enableAndroidTest = false
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
// Android
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.14.0")

// Other
    // image loading and caching
    implementation("com.github.bumptech.glide:glide:5.0.7") {
        exclude(group = "com.android.support")
    }
    implementation("com.github.bumptech.glide:okhttp3-integration:5.0.7@aar")
    annotationProcessor("com.github.bumptech.glide:compiler:5.0.7")
    // For Http Connection
    implementation("com.squareup.okhttp3:okhttp-urlconnection:5.4.0")
    // Used to store the cookies between runs
    implementation("com.github.franmontiel:PersistentCookieJar:v1.0.1")
    // To parse HTML
    implementation("org.jsoup:jsoup:1.22.2")
    // color picker
    implementation("com.github.yukuku:ambilwarna:2.0.1")
    // fast scroll
    implementation("me.zhanghai.android.fastscroll:library:1.3.0")
}



abstract class CopyLegalNotices : DefaultTask() {
    @get:InputFile
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    abstract val noticeFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun taskAction() {
        val output = outputDirectory.get().asFile
        output.mkdirs()
        licenseFile.get().asFile.copyTo(output.resolve("LICENSE"), overwrite = true)
        noticeFile.get().asFile.copyTo(output.resolve("NOTICE"), overwrite = true)
    }
}

abstract class CreateRenamedApk : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val suffix: Property<String>

    @TaskAction
    fun taskAction() {
        val builtArtifacts = builtArtifactsLoader.get().load(apkFolder.get())
            ?: throw RuntimeException("Cannot load APKs")
        val newName = "NReader_${versionName.get()}${suffix.get()}.apk"
        val newFile = apkFolder.asFile.get().resolve(newName)
        File(builtArtifacts.elements.single().outputFile).copyTo(newFile, overwrite = true)
        if (!suffix.get().contains("_debug")) {
            val releaseFolder =
                apkFolder.get().asFile.parentFile.parentFile.resolve("release").resolve(newName)
            newFile.copyTo(releaseFolder)
        }
    }
}
