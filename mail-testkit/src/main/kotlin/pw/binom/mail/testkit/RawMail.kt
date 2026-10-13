package pw.binom.mail.testkit

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal raw wire helpers used as an *independent* oracle/seeder in tests:
 * they do not share any code with the module under test.
 */
public object RawMail {

    /** Delivers [message] over plain SMTP and returns the server's final reply for DATA. */
    public fun deliver(host: String, port: Int, from: String, to: String, message: String): String {
        open(host, port).use { conn ->
            conn.expect("220")
            conn.send("EHLO test.local")
            conn.expect("250")
            conn.send("MAIL FROM:<$from>")
            conn.expect("250")
            conn.send("RCPT TO:<$to>")
            conn.expect("250")
            conn.send("DATA")
            conn.expect("354")
            for (line in message.split("\r\n", "\n")) {
                // dot-stuffing per RFC 5321 4.5.2
                conn.writeLine(if (line.startsWith(".")) "." + line else line)
            }
            conn.writeLine(".")
            val reply = conn.readReply()
            conn.send("QUIT")
            return reply
        }
    }

    /** Logs in over POP3 and returns the raw text of every message via RETR. */
    public fun pop3RetrieveAll(host: String, port: Int, login: String, password: String): List<String> {
        open(host, port).use { conn ->
            conn.expect("+OK")
            conn.send("USER $login")
            conn.expect("+OK")
            conn.send("PASS $password")
            conn.expect("+OK")
            conn.send("STAT")
            conn.expect("+OK")
            conn.send("LIST")
            conn.expect("+OK")
            val list = conn.readMultiline()
            val messages = ArrayList<String>()
            for (line in list) {
                val seq = line.trim().split(" ").first()
                conn.send("RETR $seq")
                conn.expect("+OK")
                messages += conn.readMultiline().joinToString("\r\n")
            }
            conn.send("QUIT")
            return messages
        }
    }

    private fun open(host: String, port: Int): Conn {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 15_000)
        socket.soTimeout = 15_000
        return Conn(socket)
    }

    private class Conn(private val socket: Socket) : AutoCloseable {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1))

        fun writeLine(line: String) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }

        fun send(line: String) = writeLine(line)

        fun readReply(): String {
            val first = reader.readLine() ?: error("connection closed")
            val text = StringBuilder(first)
            var code = first.take(3)
            // multi-line reply: "NNN-..." continues until "NNN ..."
            while (first.length > 3 && first[3] == '-') {
                val next = reader.readLine() ?: break
                text.append('\n').append(next)
                if (next.startsWith(code + " ")) break
            }
            return text.toString()
        }

        fun expect(prefix: String): String {
            val reply = readReply()
            check(reply.startsWith(prefix)) { "expected '$prefix' but got: $reply" }
            return reply
        }

        /** POP3 multi-line body terminated by a single "." line (dot-unstuffed). */
        fun readMultiline(): List<String> {
            val lines = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line == ".") break
                lines += if (line.startsWith("..")) line.substring(1) else line
            }
            return lines
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
