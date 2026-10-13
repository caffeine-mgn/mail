import org.gradle.api.tasks.testing.Test

plugins {
    id("pw.binom.mail.jvm")
}

dependencies {
    api("org.testcontainers:testcontainers:1.20.4")
    api("org.testcontainers:junit-jupiter:1.20.4")
    api("org.junit.jupiter:junit-jupiter:5.11.4")
    api("org.jetbrains.kotlin:kotlin-test")
    api("org.jetbrains.kotlin:kotlin-test-junit5")
    api("io.ktor:ktor-network:3.5.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
