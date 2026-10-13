package pw.binom.mail.smtp.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pw.binom.tls.socket.SSLContext

/**
 * SMTP server (RFC 5321). Coroutine-friendly, in-process.
 *
 * Usage:
 *   val mailbox = InMemorySmtpMailbox()
 *   val server = SmtpServer.start(selectorManager, port = 0, mailbox = mailbox)
 *   // ... clients connect to server.localPort ...
 *   server.close()
 */
public class SmtpServer internal constructor(
    private val serverSocket: ServerSocket,
    public val mailbox: SmtpMailbox,
    public val localDomain: String,
    public val greeting: String,
    public val auth: SmtpAuthenticator?,
    public val requireAuth: Boolean,
    public val tls: SSLContext?,
    private val acceptJob: Job,
) : AutoCloseable {
    public val localPort: Int
        get() = (serverSocket.localAddress as? io.ktor.network.sockets.InetSocketAddress)?.port
            ?: serverSocket.localAddress.toString().substringAfterLast(':').substringBefore(']').toInt()
    public val isRunning: Boolean get() = !serverSocket.isClosed

    public suspend fun await() {
        acceptJob.join()
    }

    override fun close() {
        try { serverSocket.dispose() } catch (_: Throwable) {}
        acceptJob.cancel()
    }

    public companion object {
        public suspend fun start(
            selectorManager: SelectorManager,
            port: Int = 0,
            host: String = "127.0.0.1",
            mailbox: SmtpMailbox = InMemorySmtpMailbox(),
            localDomain: String = "localhost",
            greeting: String = "mail",
            auth: SmtpAuthenticator? = null,
            requireAuth: Boolean = false,
            tls: SSLContext? = null,
        ): SmtpServer = coroutineScope {
            val serverSocket: ServerSocket = aSocket(selectorManager).tcp().bind(host, port)
            val job = launch(SupervisorJob()) {
                try {
                    while (isActive && !serverSocket.isClosed) {
                        val socket: Socket = try { serverSocket.accept() } catch (_: Throwable) { break }
                        launch {
                            val session = SmtpSession(
                                socket = socket,
                                serverGreeting = greeting,
                                localDomain = localDomain,
                                mailbox = mailbox,
                                auth = auth,
                                requireAuth = requireAuth,
                                tls = tls,
                                coroutineContext = coroutineContext,
                            )
                            session.run()
                        }
                    }
                } catch (_: Throwable) {
                    // Server socket closed
                }
            }
            SmtpServer(serverSocket, mailbox, localDomain, greeting, auth, requireAuth, tls, job)
        }
    }
}
