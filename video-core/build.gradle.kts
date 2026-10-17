plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

group = "org.connecttag.libs.kotlin"
version = "1.0.0"

kotlin {
    jvmToolchain(17)

    android {
        namespace = "org.connecttag.video"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
            implementation(kotlin("test"))
        }
        named("androidMain") {
            dependencies {
                api(libs.media3.exoplayer)
                api(libs.media3.exoplayer.rtsp)
            }
        }
        named("desktopMain") {
            dependencies {
                api(libs.ffmpeg)
                api(libs.javacpp)
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("ConnectTag Video Streaming (KMP)")
            description.set("Ultra-low latency Kotlin Multiplatform video streaming & decoding SDK (RFC 2326 RTSP server, RTP H.264 packetizer, SKYV/MJPEG, Media3/FFmpeg decoders)")
            url.set("https://github.com/connecttag/video-streaming-lib-kmp")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("connecttagye")
                    name.set("Connect Tag")
                    email.set("connecttagye@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/connecttag/video-streaming-lib-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com:connecttag/video-streaming-lib-kmp.git")
                url.set("https://github.com/connecttag/video-streaming-lib-kmp/tree/main")
            }
        }
    }
}
