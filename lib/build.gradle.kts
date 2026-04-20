import io.deepmedia.tools.deployer.model.Secret
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import java.util.Properties

plugins {
    id("com.android.library")
    kotlin("android")
    id("io.deepmedia.tools.deployer")
    `maven-publish`
    id("org.jetbrains.dokka") version "1.9.20"
}

android {
    namespace = "com.vero.transcoder"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        targetSdk = 23
    }
    publishing {
        singleVariant("release")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.otaliastudios.opengl:egloo:0.6.1")
    api("androidx.annotation:annotation:1.8.2")

    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.mockito:mockito-android:2.28.2")

    dokkaPlugin("org.jetbrains.dokka:android-documentation-plugin:1.9.20")
}

val javadocs = tasks.register<Jar>("dokkaJavadocJar") {
    dependsOn(tasks.dokkaJavadoc)
    from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

// Ugly workaround because the snapshot publication has different version and maven-publish
// is then unable to determine the right coordinates for lib-legacy dependency on this project
publishing.publications.withType<MavenPublicationInternal>().configureEach {
    isAlias = name != "localReleaseComponent"
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)

group = "com.vero"
version = "0.11.3-vero.1"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("veroRelease") {
                from(components["release"])
                groupId = "com.vero"
                artifactId = "vero-transcoder"
                version = "0.11.3-vero.1"
            }
        }

        repositories {
            val repoUrl = localProperty("VERO_MAVEN_URL")
            if (!repoUrl.isNullOrBlank()) {
                maven {
                    name = "vero"
                    url = uri(repoUrl)
                    credentials {
                        username = localProperty("VERO_MAVEN_USERNAME")
                        password = localProperty("VERO_MAVEN_PASSWORD")
                    }
                }
            }
        }
    }
}

deployer {
    content {
        component {
            fromSoftwareComponent("release")
            kotlinSources()
            docs(javadocs)
        }
    }

    projectInfo {
        groupId = "com.vero"
        artifactId = "vero-transcoder"
        release.version = "0.11.3-vero.1" // change :lib-legacy and README
        description = "Accelerated video compression and transcoding on Android using MediaCodec APIs (no FFMPEG/LGPL licensing issues). Supports cropping to any dimension, concatenation, audio processing and much more."
        url = "https://opensource.deepmedia.io/transcoder"
        scm.fromGithub("deepmedia", "Transcoder")
        license(apache2)
        developer("Mattia Iavarone", "mattia@deepmedia.io", "DeepMedia", "https://deepmedia.io")
    }

    signing {
        key = secret("SIGNING_KEY")
        password = secret("SIGNING_PASSWORD")
    }

    // use "deployLocal" to deploy to local maven repository
    localSpec {
        directory.set(rootProject.layout.buildDirectory.get().dir("inspect"))
        signing {
            key = absent()
            password = absent()
        }
    }

    // use "deployNexus" to deploy to OSSRH / maven central
    nexusSpec {
        auth.user = secret("SONATYPE_USER")
        auth.password = secret("SONATYPE_PASSWORD")
        syncToMavenCentral = true
    }

    // use "deployNexusSnapshot" to deploy to sonatype snapshots repo
    nexusSpec("snapshot") {
        auth.user = secret("SONATYPE_USER")
        auth.password = secret("SONATYPE_PASSWORD")
        repositoryUrl = ossrhSnapshots1
        release.version = "latest-SNAPSHOT"
    }

    // use "deployGithub" to deploy to github packages
    githubSpec {
        repository = "Transcoder"
        owner = "deepmedia"
        auth {
            user = secret("GHUB_USER")
            token = secret("GHUB_PERSONAL_ACCESS_TOKEN")
        }
    }
}
