package pw.binom.mail.smtp.server

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import pw.binom.mail.smtp.SmtpClient
import pw.binom.tls.socket.SSLContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real SMTP server and client over a TLS (STARTTLS) connection on Kotlin/Native,
 * proving server-side TLS works on a non-JVM target via the OpenSSL-backed pw.binom.tls library.
 */
@OptIn(ExperimentalEncodingApi::class)
class SmtpTlsNativeTest {
    private val certDer = Base64.decode(CERT_B64)
    private val keyDer = Base64.decode(KEY_B64)

    @Test
    fun deliversMessageOverStartTls() {
        runBlocking(Dispatchers.Default) {
            val selector = SelectorManager(Dispatchers.Default)
            val mailbox = InMemorySmtpMailbox()
            val server = SmtpServer.start(
                selector, port = 0, host = "127.0.0.1", mailbox = mailbox,
                tls = SSLContext.server(listOf(certDer), keyDer),
            )
            try {
                val client = SmtpClient.connect(selector, "127.0.0.1", server.localPort)
                val caps = client.ehlo("test.local")
                assertTrue(caps.has("STARTTLS"), "STARTTLS not advertised")
                val upgrade = client.startTls("localhost", trustAll = true)
                assertTrue(upgrade.isPositive, "STARTTLS failed: ${upgrade.text}")
                val caps2 = client.ehlo("test.local")
                assertTrue(!caps2.has("STARTTLS"), "STARTTLS still advertised over tls")
                assertTrue(client.mail("sender@test.local").isPositive)
                assertTrue(client.rcpt("rcpt@test.local").isPositive)
                assertTrue(
                    client.data("Subject: hi\r\n\r\nHello native tls\r\n".encodeToByteArray()).isPositive,
                )
                client.quit()
                val stored = mailbox.all()
                assertEquals(1, stored.size)
                assertTrue(stored[0].data.decodeToString().contains("Hello native tls"))
            } finally {
                server.close()
                selector.close()
            }
        }
    }

    private companion object {
        const val CERT_B64 = "MIIDCTCCAfGgAwIBAgIUZT6ckCNfVhY4/vJJKTpm2SUc+AAwDQYJKoZIhvcNAQELBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTAxMjE3MTIyOFoXDTM2MTAwOTE3MTIyOFowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAttM65ncrtozsAxJjp42M88slyAD9FlmGZM+aDN1YVFMsH78gHJzeqKYiTluQrK61icat/n64NZ8Mr6rIWR94nCS71OovOJFpPjlnRHMYlVIb0PQ33bdj+HWRTB7V3TT/7AiYLTM5/1MREwQgH/cK4ZawdAtQNlCH77WbTw25ahhmytC+vbEls7vBuCdiWhDoJNIwFZLqK42tyuUS6OXOCXlEqBBfIpbcRT64GoMssvA8C3CIOup3ZMHkDxxB2R5hBAsI4pNNsBLQDR4d2ccCJULBOiXjD/gXNwug+YJAl1M9w32XJ3htdEEQGpwjzjLCJl6QMXbPwIE0lJK+y0VcOQIDAQABo1MwUTAdBgNVHQ4EFgQUKnrmZR87F/HxWn/TAoF0pU/9owQwHwYDVR0jBBgwFoAUKnrmZR87F/HxWn/TAoF0pU/9owQwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEABX5rBa3pPcFRaVpAkcv0zkkO8cplODl7KOKf9NRtOJNdQDVL9BnRT03UwtdQiZCOze5tRUTjWVsxIB8u8ihE3H06M00fLMWLTPJyqUZ8SLbY7Az+KHNotuXQLW/XyzldOOrGp50aM3DSoZ3IJjRecXK0Zsu1r+10+6TbAFiMthcGxymEh4ruuwm2o47RzZamO+E2sfahEw150fmp64GseeXB3gSCYogiYhOHhQGblQv+BlCQ4yem+huoQtFdbfbNmX4iiJlM5Lpc+a1s3O/kMOT1bX649eSIpVHcHoNncyqmkGmBvYYxL1aYlP1Z4NgHt86RffOG7C8pUK1FdNZbWA=="
        const val KEY_B64 = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC20zrmdyu2jOwDEmOnjYzzyyXIAP0WWYZkz5oM3VhUUywfvyAcnN6opiJOW5CsrrWJxq3+frg1nwyvqshZH3icJLvU6i84kWk+OWdEcxiVUhvQ9Dfdt2P4dZFMHtXdNP/sCJgtMzn/UxETBCAf9wrhlrB0C1A2UIfvtZtPDblqGGbK0L69sSWzu8G4J2JaEOgk0jAVkuorja3K5RLo5c4JeUSoEF8iltxFPrgagyyy8DwLcIg66ndkweQPHEHZHmEECwjik02wEtANHh3ZxwIlQsE6JeMP+Bc3C6D5gkCXUz3DfZcneG10QRAanCPOMsImXpAxds/AgTSUkr7LRVw5AgMBAAECggEATdz9Z10WvWscMxy4W4pSHxyEbT9aipO7RKEUC2mTrmozzxt/qh/3Rl1nG/csURchUtnOjaZbft7LsN8r76UTIXEVnBLW5KEtcOZSwFjCfgnXtfDlACUUSyOLK5+wVsM1QcnRQeLgzwiKK46kvehhdn1OjkWpNlCq7GDskckXEAM2rdcZEr05rJqrUqmjSlBBOMs8tbM0tyi7KxCT1lnAW/JXEm4d7Bx3OBgRh9spbewCO1QVLyIhT5fgRsRwgBSK+uG+j4R6BUyTiSTsTVIgQazDurIgOeCvZKQqxPRF8idoNg5EIkyEOqR+FPnU654Cxl0T8KPWexrvSepbr4X/DwKBgQDpGfQR5yQ02vV+/QY0vdNie1g23yz7PwEQu9baolbBrHnxsJ6PQF6Kjk3HbMe7tO9ecNzMwO5JgoJiC6Me8JYQ7EjIMyKoiMX9U9aIeehgcJM9Ln5FT2PnbcIN2pVxSfTluAvfZ8ktegjB9rmHgM30DTWtqABDasS4RV+ewiipwwKBgQDIyO0oYmVCdZ1N8MWBiH0DjxW4eBCV4DRXNTjQlX7W97FGrax2eEbq8YrDvxe9WNi0joSOsWcwC9mQYURgoCEoE6SmrIEzMNeujEdA+RFS4VRSLbYNrSBNb5Tj34rn41WLdmEEyFK0qi8zOvLXUaxOIRXleqg8TUkCJ89TSflGUwKBgCmyPclyCjT2MdHMWu7WQdis7aOrGjW7GGqw3wbu4Qv12T5VdJd1UTQBroIMwFGgVO0UzTpHDJjTopT9NemT6ZQPYBmHpBx3ksr00zxIajvOB1h7V5T4VYuRQ5QM1IyWSRtKZ71HqGHdIXHou1k0viJ4tnBgy+NDVvsLVcZlPrbXAoGAarSFfPnV0J0K7VnwzOdJcPPA2llMjyopBtOtDwTz9LCMEQDJb+h53/BgC6lpp2IpQMOg5OiY6yrRA+79VNCk7lSKI134B8mERioc0AXN1BsWIdpTLy97xEtxeCzb+GKTmttdshn4AjWTgglo909bX+YradpmwC1eiHu5rgzZIisCgYEAxGxT0x67Fdg8mpq/SSAnM5ewkUn7D3tK5XB0r0BJYvYtsaRNgGiBtgSKyVQHrEuAEZyEI3LHtFn1iN/si7lGQ38rPsO+jYl0H+XkHg/Ivw2oMaOBn8dmc+Oa7onzN7sGKMYQs2dlae1PB/hOGZLKyQdoEYJYa2WShCEFPGtAzlg="
    }
}
