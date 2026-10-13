package pw.binom.mail.test

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension

abstract class AbstractITTest {
    @RegisterExtension
    @JvmField
    protected val greenMail: GreenMailExtension = GreenMailExtension(
        arrayOf(
            ServerSetup(0, "127.0.0.1", ServerSetupTest.SMTP.protocol),
            ServerSetup(0, "127.0.0.1", ServerSetupTest.POP3.protocol),
            ServerSetup(0, "127.0.0.1", ServerSetupTest.IMAP.protocol),
        ),
    )

    @BeforeEach
    fun setUpUser() {
        greenMail.setUser("it@localhost", "it", "it")
    }

    protected val smtpPort: Int get() = greenMail.smtp.serverSetup.port
    protected val pop3Port: Int get() = greenMail.pop3.serverSetup.port
    protected val imapPort: Int get() = greenMail.imap.serverSetup.port
    protected val host: String get() = "127.0.0.1"

    protected fun <T> runTest(block: suspend (SelectorManager) -> T): T = runBlocking {
        SelectorManager().use { selector ->
            block(selector)
        }
    }
}
