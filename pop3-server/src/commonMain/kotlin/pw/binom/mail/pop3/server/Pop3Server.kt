package pw.binom.mail.pop3.server

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
import pw.binom.mail.pop3.Pop3Authenticator
import pw.binom.mail.pop3.Pop3Mailbox
import pw.binom.tls.socket.SSLContext

/**
 * POP3 server (RFC 1939). Coroutine-friendly, in-process.
 */
public class Pop3Server internal constructor(
    private val serverSocket: ServerSocket,
    public val mailboxProvider: (String) -> Pop3Mailbox,
    public val auth: Pop3Authenticator,
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
            mailboxProvider: (String) -> Pop3Mailbox = { pw.binom.mail.pop3.InMemoryPop3Mailbox() },
            auth: Pop3Authenticator = Pop3Authenticator { _, _ -> false },
            greeting: String = "POP3 server ready",
            tls: SSLContext? = null,
        ): Pop3Server = coroutineScope {
            val serverSocket: ServerSocket = aSocket(selectorManager).tcp().bind(host, port)
            val job = launch(SupervisorJob()) {
                try {
                    while (isActive && !serverSocket.isClosed) {
                        val socket: Socket = try { serverSocket.accept() } catch (_: Throwable) { break }
                        launch {
                            val session = pw.binom.mail.pop3.Pop3Session(socket, greeting, mailboxProvider, auth, tls)
                            session.run()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            Pop3Server(serverSocket, mailboxProvider, auth, greeting, tls, job)
        }
    }
}
