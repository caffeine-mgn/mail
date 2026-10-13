package pw.binom.mail.testkit

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Testcontainers wrapper around the GreenMail standalone image. Exposes a single
 * container running SMTP/POP3/IMAP (plain + TLS) with a set of pre-provisioned
 * users — one per module under test, so their mailboxes stay isolated.
 */
public class GreenMailContainer :
    GenericContainer<GreenMailContainer>(DockerImageName.parse(IMAGE)) {

    public companion object {
        public const val PASSWORD: String = "secret"
        public const val DOMAIN: String = "localhost"

        /** Dedicated login per protocol module — keeps INBOXes isolated. */
        public const val SMTP_USER: String = "smtp"
        public const val POP3_USER: String = "pop3"
        public const val IMAP_USER: String = "imap"
        public const val SUBMISSION_USER: String = "sub"

        public fun address(login: String): String = "$login@$DOMAIN"

        private val USERS: List<String> = listOf(SMTP_USER, POP3_USER, IMAP_USER, SUBMISSION_USER)

        public const val SMTP_PORT: Int = 3025
        public const val SMTPS_PORT: Int = 3465
        public const val POP3_PORT: Int = 3110
        public const val POP3S_PORT: Int = 3995
        public const val IMAP_PORT: Int = 3143
        public const val IMAPS_PORT: Int = 3993

        private const val IMAGE: String = "greenmail/standalone:2.1.4"
    }

    init {
        val usersOption = USERS.joinToString(",") { "$it:$PASSWORD@$DOMAIN" }
        withEnv(
            "GREENMAIL_OPTS",
            "-Dgreenmail.setup.test.all -Dgreenmail.hostname=0.0.0.0 " +
                "-Dgreenmail.users=$usersOption",
        )
        withExposedPorts(SMTP_PORT, SMTPS_PORT, POP3_PORT, POP3S_PORT, IMAP_PORT, IMAPS_PORT)
        waitingFor(Wait.forLogMessage(".*Starting GreenMail API server.*", 1))
    }

    public val smtpPort: Int get() = getMappedPort(SMTP_PORT)
    public val smtpsPort: Int get() = getMappedPort(SMTPS_PORT)
    public val pop3Port: Int get() = getMappedPort(POP3_PORT)
    public val pop3sPort: Int get() = getMappedPort(POP3S_PORT)
    public val imapPort: Int get() = getMappedPort(IMAP_PORT)
    public val imapsPort: Int get() = getMappedPort(IMAPS_PORT)
}
