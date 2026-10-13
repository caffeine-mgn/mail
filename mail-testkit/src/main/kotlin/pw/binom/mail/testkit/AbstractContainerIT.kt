package pw.binom.mail.testkit

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Base class for container-backed integration tests. The shared GreenMail container
 * starts once per JVM; concrete tests talk to it with the module's own clients,
 * which exercises the commonMain code on the JVM target.
 */
@Testcontainers
public abstract class AbstractContainerIT {

    public companion object {
        @JvmField
        @Container
        public val greenMail: GreenMailContainer = GreenMailContainer()
    }

    protected val host: String get() = greenMail.host
    protected val smtpPort: Int get() = greenMail.smtpPort
    protected val smtpsPort: Int get() = greenMail.smtpsPort
    protected val pop3Port: Int get() = greenMail.pop3Port
    protected val imapPort: Int get() = greenMail.imapPort

    protected val password: String get() = GreenMailContainer.PASSWORD
    protected fun address(login: String): String = GreenMailContainer.address(login)

    protected fun <T> runTest(block: suspend (SelectorManager) -> T): T = runBlocking {
        SelectorManager().use { selector ->
            block(selector)
        }
    }
}
