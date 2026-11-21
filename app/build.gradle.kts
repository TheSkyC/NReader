// Modified for NReader by TheSkyC (2026); see NOTICE.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.internal.extensions.stdlib.capitalized
import java.io.File
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
val externalSigningDirectory = providers.gradleProperty("nreaderSigningDir")
    .orNull?.let(::File)
val externalSigningKeystore = externalSigningDirectory?.resolve("nreader-release.jks")
val externalSigningStorePassword = externalSigningDirectory?.resolve("store-password.txt")
val externalSigningKeyPassword = externalSigningDirectory?.resolve("key-password.txt")
val hasExternalSigning = externalSigningKeystore?.isFile == true
    && externalSigningStorePassword?.isFile == true
    && externalSigningKeyPassword?.isFile == true

val nreaderApplicationId = "com.theskyc.nreader"
val updateEndpoint = providers.gradleProperty("nreaderUpdateEndpoint").orNull ?: ""
val updateEndpointConfig = updateEndpoint
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val tagCatalogManifestUrl = providers.gradleProperty("nreaderTagCatalogManifestUrl").orNull ?: ""
val tagCatalogManifestUrlConfig = tagCatalogManifestUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val tagTranslationManifestUrlConfig = (providers.gradleProperty("nreaderTagTranslationManifestUrl").orNull ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val nreaderVersion = providers.gradleProperty("nreaderVersion").orNull
    ?: error("nreaderVersion must be set in gradle.properties")
val versionPattern = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(pre\d+|(?:alpha|beta|rc)\.\d+))?$""")
val versionMatch = versionPattern.matchEntire(nreaderVersion)
    ?: error("nreaderVersion must use SemVer, for example 1.0.0-beta.1")
val majorVersion = versionMatch.groupValues[1].toInt()
val minorVersion = versionMatch.groupValues[2].toInt()
val patchVersion = versionMatch.groupValues[3].toInt()
val prerelease = versionMatch.groupValues[4].ifEmpty { null }
val qualifierCode = when {
    prerelease == null -> 399
    prerelease.startsWith("pre") -> prerelease.removePrefix("pre").toInt()
    prerelease.startsWith("alpha.") -> 100 + prerelease.substringAfter('.').toInt()
    prerelease.startsWith("beta.") -> 200 + prerelease.substringAfter('.').toInt()
    prerelease.startsWith("rc.") -> 300 + prerelease.substringAfter('.').toInt()
    else -> error("Unsupported nreaderVersion qualifier: $prerelease")
}
require(qualifierCode in 1..399) {
    "nreaderVersion qualifier sequence must produce a version code between 1 and 399"
}
val computedVersionCode = majorVersion * 1_000_000 + minorVersion * 10_000 + patchVersion * 1_000 + qualifierCode

android {
    signingConfigs {
        if (hasExternalSigning) {
            create("release") {
                keyAlias = providers.gradleProperty("nreaderSigningKeyAlias").orNull ?: "nreader"
                keyPassword = externalSigningKeyPassword!!.readText().trim()
                storeFile = externalSigningKeystore
                storePassword = externalSigningStorePassword!!.readText().trim()
            }
        } else if (keystorePropertiesFile.isFile) {
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
        applicationId = nreaderApplicationId
        buildConfigField("String", "UPDATE_REPOSITORY", "\"TheSkyC/NReader\"")
        buildConfigField("String", "UPDATE_ENDPOINT", "\"\"")
        buildConfigField("String", "ISSUE_TRACKER_URL", "\"\"")
        buildConfigField("String", "TAG_CATALOG_MANIFEST_URL", "\"$tagCatalogManifestUrlConfig\"")
        buildConfigField("String", "TAG_TRANSLATION_MANIFEST_URL", "\"$tagTranslationManifestUrlConfig\"")
        // Format: MmPPbb
        // M: Major, m: minor, P: Patch, b: build
        versionCode = computedVersionCode
        multiDexEnabled = true
        versionName = nreaderVersion
        vectorDrawables.useSupportLibrary = true
        proguardFiles("proguard-rules.pro")
    }
    flavorDimensions += "sdk"
    productFlavors {
        create("minApi28") {
            dimension = "sdk"
            targetSdk = 36
            minSdk = 28
        }
        create("minApi26") {
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
            buildConfigField("String", "UPDATE_ENDPOINT", "\"$updateEndpointConfig\"")
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
    namespace = "com.theskyc.nreader"
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
        val copyTagCatalog = tasks.register<CopyTagCatalog>("copy${variant.name.capitalized()}TagCatalog") {
            catalogFile.set(rootProject.layout.projectDirectory.file("data/tags.json"))
            revisionFile.set(rootProject.layout.projectDirectory.file("data/tagsVersion"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTagCatalog, CopyTagCatalog::outputDirectory)
        var suffix = if (variant.flavorName == "minApi26") "-minApi26" else "-minApi28"
        if (variant.buildType == "debug") {
            suffix += "-debug"
        } else if (variant.buildType == "RelWithDebInfo") {
            suffix += "-relwithdebinfo"
        } else {
            suffix += "-release"
        }
        var renameTask =
            tasks.register<CreateRenamedApk>("createRenamedApk${variant.flavorName?.capitalized()}${variant.buildType?.capitalized()}") {
                this.apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
                this.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                this.versionName.set(
                    variant.outputs.single().versionName.get()
                        .removeSuffix("-debug")
                        .removeSuffix("-relwithdebinfo")
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

tasks.register("printNReaderVersion") {
    doLast { println(nreaderVersion) }
}

tasks.register("printNReaderMetadata") {
    doLast {
        println("versionName=$nreaderVersion")
        println("versionCode=$computedVersionCode")
        println("applicationId=$nreaderApplicationId")
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.12.0")
// Android
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.work:work-runtime:2.12.0")
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

abstract class CopyTagCatalog : DefaultTask() {
    @get:InputFile
    abstract val catalogFile: RegularFileProperty

    @get:InputFile
    abstract val revisionFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val output = outputDirectory.dir("tag-catalog").get().asFile
        val catalog = catalogFile.get().asFile
        val revision = revisionFile.get().asFile
        require(catalog.length() in 1..16L * 1024 * 1024) { "Bundled tag catalog is out of bounds" }
        require(revision.length() in 1..32L) { "Bundled tag catalog revision is out of bounds" }
        val revisionText = revision.readText(Charsets.US_ASCII).trim { it <= ' ' }
        require(revisionText.matches(Regex("[1-9][0-9]*")) && revisionText.toIntOrNull() != null) {
            "Invalid bundled tag catalog revision"
        }
        catalog.copyTo(output.resolve("tags.json"), overwrite = true)
        revision.copyTo(output.resolve("tagsVersion"), overwrite = true)
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
        val newName = "NReader-${versionName.get()}${suffix.get()}.apk"
        val newFile = apkFolder.asFile.get().resolve(newName)
        File(builtArtifacts.elements.single().outputFile).copyTo(newFile, overwrite = true)
        if (!suffix.get().contains("-debug")) {
            val releaseFolder =
                apkFolder.get().asFile.parentFile.parentFile.resolve("release").resolve(newName)
            newFile.copyTo(releaseFolder)
        }
    }
}
