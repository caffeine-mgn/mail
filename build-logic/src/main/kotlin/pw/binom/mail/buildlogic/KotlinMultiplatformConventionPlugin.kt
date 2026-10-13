package pw.binom.mail.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * KMP base convention. All mail modules are Kotlin Multiplatform with a `jvm`
 * target plus the full native target set (Linux, MinGW, macOS, iOS). Only the
 * JVM target and the host-native targets are actually built on a given machine;
 * cross-targets that the host cannot produce are simply not executed.
 *
 * Code lives in commonMain so it stays portable across every target. No JS/WASM.
 *
 * Cross-module dependencies are declared in `commonMain.dependencies { ... }`,
 * which is the KMP way: KMP forbids `java-library` (incompatible with the KMP
 * plugin) and the top-level `dependencies { }` block has no KMP-aware
 * configuration wiring.
 */
abstract class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)

            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }

            linuxX64()
            linuxArm64()
            mingwX64()

            macosArm64()
            iosArm64()
            iosSimulatorArm64()
            iosX64()

            sourceSets.configureEach {
                dependencies {
                    when (name) {
                        "commonMain" -> {
                            implementation("org.jetbrains.kotlin:kotlin-stdlib")
                            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                        }
                        "commonTest" -> {
                            implementation("org.jetbrains.kotlin:kotlin-test")
                            implementation("org.jetbrains.kotlin:kotlin-test-annotations-common")
                        }
                    }
                }
            }
        }

        // JVM tests exercise commonMain via Testcontainers; make sure the Docker
        // endpoint is visible to the forked test JVM (podman rootless socket here).
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            val dockerHost = System.getenv("DOCKER_HOST")
                ?: "unix://${System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/1000"}/podman/podman.sock"
            environment("DOCKER_HOST", dockerHost)
        }
    }
}
