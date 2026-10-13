plugins {
    id("pw.binom.mail.server")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":smtp-server"))
                api(project(":submission-server"))
                api(project(":imap-server"))
                api(project(":pop3-server"))
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
                implementation(project(":smtp-client"))
                implementation(project(":imap-client"))
                implementation(project(":pop3-client"))
                implementation(project(":smtp-protocol"))
                implementation(project(":imap-protocol"))
                implementation(project(":pop3-protocol"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the in-memory all-in-one mail server (SMTP/Submission/IMAP/POP3)"
    dependsOn("jvmMainClasses")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("pw.binom.mail.server.MainKt")
    standardInput = System.`in`
}
