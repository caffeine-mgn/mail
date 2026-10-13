plugins {
    id("pw.binom.mail.protocol")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":smtp-protocol"))
            }
        }
    }
}
