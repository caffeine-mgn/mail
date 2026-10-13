package pw.binom.mail.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Adds ktor-network / ktor-utils to commonMain. Used by server modules
 * (smtp-server, pop3-server, etc.) — these are the modules that accept
 * inbound connections.
 */
abstract class ServerConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("pw.binom.mail.kotlin-multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.configureEach {
                dependencies {
                    if (name == "commonMain") {
                        implementation("io.ktor:ktor-network:3.5.2")
                        implementation("io.ktor:ktor-utils:3.5.2")
                    }
                }
            }
        }
    }
}
