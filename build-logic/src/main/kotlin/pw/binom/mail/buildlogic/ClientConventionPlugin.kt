package pw.binom.mail.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Adds ktor-network / ktor-network-tls / ktor-utils to commonMain. Used by
 * client modules (smtp-client, pop3-client, etc.) — these are the modules
 * that establish outbound connections.
 *
 * TODO: TLS support on native targets will require additional setup (mbedTLS or
 * similar). For now this layer is JVM-friendly.
 */
abstract class ClientConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("pw.binom.mail.kotlin-multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.configureEach {
                dependencies {
                    if (name == "commonMain") {
                        implementation("io.ktor:ktor-network:3.5.2")
                        implementation("io.ktor:ktor-network-tls:3.5.2")
                        implementation("io.ktor:ktor-utils:3.5.2")
                    }
                }
            }
        }
    }
}
