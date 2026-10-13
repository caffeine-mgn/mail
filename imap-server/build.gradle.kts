import org.gradle.api.tasks.testing.Test

plugins {
    id("pw.binom.mail.server")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":imap-protocol"))
                implementation(project(":mime"))
                implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
                api("pw.binom:tls:0.1.0-SNAPSHOT")
            }
        }
        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        jvmTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.junit.jupiter:junit-jupiter:5.11.4")
                implementation(project(":imap-client"))
            }
        }
        linuxX64Test {
            dependencies {
                implementation(project(":imap-client"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
