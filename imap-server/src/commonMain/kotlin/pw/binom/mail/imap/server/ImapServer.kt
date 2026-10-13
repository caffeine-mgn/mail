package pw.binom.mail.imap.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pw.binom.mail.imap.ImapAuthenticator
import pw.binom.mail.imap.ImapMailbox
import pw.binom.mail.imap.ImapSession
import pw.binom.tls.socket.SSLContext

/**
 * IMAP server (RFC 3501). Coroutine-friendly, in-process.
 */
public class ImapServer internal constructor(
    private val serverSocket: ServerSocket,
    public val authenticator: ImapAuthenticator,
    private val userMailboxesProvider: (String) -> List<String>,
    private val mailboxProvider: (String, String) -> ImapMailbox?,
    public val greeting: String,
    public val tls: SSLContext?,
    private val acceptJob: Job,
) : AutoCloseable {
    public val localPort: Int
        get() = (serverSocket.localAddress as? io.ktor.network.sockets.InetSocketAddress)?.port
            ?: serverSocket.localAddress.toString().substringAfterLast(':').substringBefore(']').toInt()

    public val isRunning: Boolean get() = !serverSocket.isClosed

    public suspend fun await() = acceptJob.join()

    override fun close() {
        try { serverSocket.dispose() } catch (_: Throwable) {}
        acceptJob.cancel()
    }

    public companion object {
        public suspend fun start(
            selectorManager: SelectorManager,
            port: Int = 0,
            host: String = "127.0.0.1",
            authenticator: ImapAuthenticator = ImapAuthenticator { _, _ -> false },
            greeting: String = "IMAP4rev1 server ready",
            userMailboxes: (String) -> List<String> = { emptyList() },
            mailboxProvider: (String, String) -> ImapMailbox? = { _, _ -> null },
            tls: SSLContext? = null,
        ): ImapServer = coroutineScope {
            val serverSocket: ServerSocket = aSocket(selectorManager).tcp().bind(host, port)
            val job = launch(SupervisorJob()) {
                try {
                    while (isActive && !serverSocket.isClosed) {
                        val socket: Socket = try { serverSocket.accept() } catch (_: Throwable) { break }
                        launch {
                            val session = ImapSession(
                                socket = socket,
                                greeting = greeting,
                                authenticator = authenticator,
                                getMailbox = mailboxProvider,
                                userMailboxes = userMailboxes,
                                tls = tls,
                            )
                            session.run()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            ImapServer(serverSocket, authenticator, userMailboxes, mailboxProvider, greeting, tls, job)
        }
    }
}
