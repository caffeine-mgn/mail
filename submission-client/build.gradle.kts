import org.gradle.api.tasks.testing.Test

plugins {
    id("pw.binom.mail.client")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":smtp-client"))
                implementation(project(":smtp-protocol"))
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
                implementation(project(":mail-testkit"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
