import io.deepmedia.tools.deployer.model.Secret
import java.util.Properties

plugins {
    id("com.android.library")
    id("io.deepmedia.tools.deployer")
    `maven-publish`
}

android {
    namespace = "com.vero.transcoder.legacy"
    compileSdk = 34
    defaultConfig.minSdk = 21
    publishing { singleVariant("release") }
}

dependencies {
    api(project(":lib"))
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
            create<MavenPublication>("veroLegacyRelease") {
                from(components["release"])
                groupId = "com.vero"
                artifactId = "vero-transcoder-legacy"
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
            emptyDocs()
            emptySources()
        }
    }

    projectInfo {
        groupId = "com.vero"
        artifactId = "vero-transcoder-legacy"
        release.version = "0.11.3-vero.1" // change :lib and README
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
