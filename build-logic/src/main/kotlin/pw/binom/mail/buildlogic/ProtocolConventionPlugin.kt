package pw.binom.mail.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Thin overlay: only enables KMP. Use for protocol FSM modules (SMTP, POP3, IMAP,
 * Submission) which are pure logic and have no I/O.
 */
abstract class ProtocolConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("pw.binom.mail.kotlin-multiplatform")
    }
}
