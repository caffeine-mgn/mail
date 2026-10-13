package pw.binom.mail.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Adds kotlinx-serialization to commonMain. Used by the MIME module which
 * is a pure data model with JSON for headers / structured fields.
 */
abstract class MimeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("pw.binom.mail.kotlin-multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.configureEach {
                dependencies {
                    if (name == "commonMain") {
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                    }
                }
            }
        }
    }
}
