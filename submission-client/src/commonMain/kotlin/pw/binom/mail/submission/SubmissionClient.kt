package pw.binom.mail.submission

import io.ktor.network.selector.SelectorManager
import pw.binom.mail.sasl.Sasl
import pw.binom.mail.smtp.SmtpClient
import pw.binom.mail.smtp.SmtpException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * High-level Submission (RFC 6409) client.
 *
 * Submission is structurally SMTP with mandatory STARTTLS and AUTH. The
 * high-level [connect] performs:
 *   1. connect
 *   2. EHLO
 *   3. (optional) STARTTLS upgrade
 *   4. EHLO again
 *   5. AUTH PLAIN or SCRAM-SHA-256
 *   6. return a [SmtpClient] ready for MAIL FROM
 */
public object SubmissionClient {
    /**
     * Connects and authenticates using AUTH PLAIN. STARTTLS is used if the
     * server advertises it.
     */
    public suspend fun connect(
        selectorManager: SelectorManager,
        host: String,
        port: Int,
        user: String,
        password: String,
        localDomain: String = "client",
        requireTls: Boolean = false,
        trustAll: Boolean = false,
    ): SmtpClient {
        val client = SmtpClient.connect(selectorManager, host, port)
        val caps = client.ehlo(localDomain)
        if (caps.has("STARTTLS")) {
            val r = client.startTls(host, trustAll = trustAll)
            if (!r.isPositive) throw SmtpException("STARTTLS rejected: ${r.code} ${r.text}")
            // After STARTTLS, the server expects a fresh EHLO
            client.ehlo(localDomain)
        } else if (requireTls) {
            throw SmtpException("server does not advertise STARTTLS but TLS required")
        }
        authenticate(client, user, password, localDomain)
        return client
    }

    private suspend fun authenticate(
        client: SmtpClient,
        user: String,
        password: String,
        localDomain: String,
    ) {
        // Try SCRAM-SHA-256 first, then PLAIN
        val caps = client.capabilities
        if (caps.has("SCRAM-SHA-256") || caps.has("SCRAM-SHA-256-PLUS")) {
            try {
                authenticateScram(client, user, password, localDomain)
                return
            } catch (e: Throwable) {
                // fall through to PLAIN
            }
        }
        authenticatePlain(client, user, password)
    }

    private suspend fun authenticatePlain(client: SmtpClient, user: String, password: String) {
        @OptIn(ExperimentalEncodingApi::class)
        val initial = Sasl.plainInitialResponse(user, password)
        val r = client.auth("PLAIN", initial)
        if (!r.isPositive) {
            val code = r.code
            if (code == 334) {
                // Server wants us to send the response in a separate step
                @OptIn(ExperimentalEncodingApi::class)
                val response = Sasl.plainInitialResponse(user, password)
                val r2 = client.authData(response)
                if (!r2.isPositive) throw SmtpException("AUTH PLAIN failed: ${r2.code} ${r2.text}")
            } else {
                throw SmtpException("AUTH PLAIN failed: ${r.code} ${r.text}")
            }
        }
    }

    private suspend fun authenticateScram(
        client: SmtpClient,
        user: String,
        password: String,
        localDomain: String,
    ) {
        val clientNonce = generateNonce()
        val clientFirst = Sasl.scramClientFirst(user, nonce = clientNonce)
        val r1 = client.auth("SCRAM-SHA-256", clientFirst)
        if (r1.code != 334) throw SmtpException("expected 334 continuation, got ${r1.code}")
        // Server response is the first text after "+" (continuation) or after the "334 " prefix
        val serverFirst = Sasl.parseScramServerFirst(r1.text)
        val clientFirstBare = clientFirst.substringAfter(',').let { rest ->
            "n=${rest.substringAfter("n=").substringBefore(",")},r=${rest.substringAfter("r=")}"
        }
        val final = Sasl.scramClientFinal(clientFirstBare, serverFirst, clientNonce, user, password)
        var r2 = client.authData(Base64.encode(final.message.encodeToByteArray()))
        var serverFinal: String? = null
        if (r2.code == 334) {
            serverFinal = decodeServerFinal(r2.text)
            r2 = client.authData("")
        }
        if (!r2.isPositive) throw SmtpException("SCRAM-SHA-256 failed: ${r2.code} ${r2.text}")
        val finalMessage = serverFinal ?: decodeServerFinal(r2.text)
        if (finalMessage != null && finalMessage.contains("v=") &&
            !Sasl.scramVerifyServerFinal(finalMessage, final.serverSignature)
        ) {
            throw SmtpException("SCRAM-SHA-256 server signature verification failed")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeServerFinal(text: String): String? = runCatching {
        Base64.decode(text.trim()).decodeToString()
    }.getOrNull()

    private fun generateNonce(): String {
        val r = Random.Default
        val sb = StringBuilder(20)
        repeat(20) { sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ"[r.nextInt(26)]) }
        return sb.toString()
    }
}
