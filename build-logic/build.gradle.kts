plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // The kotlin gradle plugin is needed on the build-logic classpath so that
    // the convention plugins can configure KotlinMultiplatformExtension.
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("mailKotlinMultiplatform") {
            id = "pw.binom.mail.kotlin-multiplatform"
            implementationClass = "pw.binom.mail.buildlogic.KotlinMultiplatformConventionPlugin"
        }
        register("mailProtocol") {
            id = "pw.binom.mail.protocol"
            implementationClass = "pw.binom.mail.buildlogic.ProtocolConventionPlugin"
        }
        register("mailClient") {
            id = "pw.binom.mail.client"
            implementationClass = "pw.binom.mail.buildlogic.ClientConventionPlugin"
        }
        register("mailServer") {
            id = "pw.binom.mail.server"
            implementationClass = "pw.binom.mail.buildlogic.ServerConventionPlugin"
        }
        register("mailMime") {
            id = "pw.binom.mail.mime"
            implementationClass = "pw.binom.mail.buildlogic.MimeConventionPlugin"
        }
        register("mailJvm") {
            id = "pw.binom.mail.jvm"
            implementationClass = "pw.binom.mail.buildlogic.JvmConventionPlugin"
        }
    }
}