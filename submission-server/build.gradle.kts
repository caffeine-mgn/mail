import org.gradle.api.tasks.testing.Test

plugins {
    id("pw.binom.mail.server")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":smtp-server"))
                implementation(project(":smtp-protocol"))
                implementation(project(":smtp-client"))
            }
        }
        jvmTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.junit.jupiter:junit-jupiter:5.11.4")
                implementation(project(":submission-client"))
            }
        }
    }
}
