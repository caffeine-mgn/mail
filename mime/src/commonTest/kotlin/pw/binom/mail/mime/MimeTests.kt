package pw.binom.mail.mime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ContentTypeTest {
    @Test
    fun simple() {
        val ct = ContentType.parse("text/plain")
        assertEquals("text", ct.type)
        assertEquals("plain", ct.subtype)
        assertTrue(ct.parameters.isEmpty())
    }

    @Test
    fun withCharset() {
        val ct = ContentType.parse("text/plain; charset=UTF-8")
        assertEquals("text", ct.type)
        assertEquals("plain", ct.subtype)
        // charset parameter is lower-cased per RFC 2978
        assertEquals("utf-8", ct.charset)
    }

    @Test
    fun multipleParams() {
        val ct = ContentType.parse("multipart/mixed; boundary=\"abc def\"; charset=us-ascii")
        assertEquals("multipart", ct.type)
        assertEquals("mixed", ct.subtype)
        assertEquals("abc def", ct.parameter("boundary"))
        assertEquals("us-ascii", ct.charset)
    }

    @Test
    fun toStringRoundTrip() {
        val original = ContentType("text", "html", mapOf("charset" to "utf-8"))
        val parsed = ContentType.parse(original.toString())
        assertEquals(original, parsed)
    }

    @Test
    fun toStringQuotesValueWithSpace() {
        val original = ContentType("multipart", "mixed", mapOf("boundary" to "abc def"))
        assertEquals("multipart/mixed; boundary=\"abc def\"", original.toString())
    }
}

class HeaderTest {
    @Test
    fun parsesSimpleHeaders() {
        val lines = listOf("From: alice@example.com", "To: bob@example.com", "Subject: Hello")
        val headers = HeaderParser.parse(lines)
        assertEquals(3, headers.size)
        assertEquals("alice@example.com", headers.firstValue("From"))
        assertEquals("bob@example.com", headers.firstValue("To"))
        assertEquals("Hello", headers.firstValue("Subject"))
    }

    @Test
    fun unfoldsObsFold() {
        val lines = listOf("Subject: Hello", " world")
        val headers = HeaderParser.parse(lines)
        assertEquals(1, headers.size)
        assertEquals("Hello world", headers.firstValue("Subject"))
    }

    @Test
    fun handlesCaseInsensitiveLookup() {
        val headers = HeaderParser.parse(listOf("Content-Type: text/plain"))
        assertEquals("text/plain", headers.firstValue("content-type"))
        assertEquals("text/plain", headers.firstValue("CONTENT-TYPE"))
        assertNotNull(headers.first("Content-Type"))
    }

    @Test
    fun rejectsHeaderWithoutColon() {
        try {
            HeaderParser.parse(listOf("nocolon"))
            fail("expected exception")
        } catch (e: MimeFormatException) {
            // expected
        }
    }
}

class AddressTest {
    @Test
    fun simpleAddress() {
        val addr = Address.parse("alice@example.com")
        assertTrue(addr is Address.Mailbox)
        addr as Address.Mailbox
        assertEquals("alice", addr.localPart)
        assertEquals("example.com", addr.domain)
        assertEquals("alice@example.com", addr.address)
    }

    @Test
    fun mailboxWithDisplayName() {
        val addr = Address.parse("Alice <alice@example.com>")
        assertTrue(addr is Address.Mailbox)
        addr as Address.Mailbox
        assertEquals("Alice", addr.name)
        assertEquals("alice", addr.localPart)
        assertEquals("example.com", addr.domain)
    }

    @Test
    fun quotedDisplayName() {
        val addr = Address.parse("\"Alice Smith\" <alice@example.com>")
        addr as Address.Mailbox
        assertEquals("Alice Smith", addr.name)
    }

    @Test
    fun parseList() {
        val list = Address.parseList("alice@example.com, Bob <bob@example.com>, charlie@example.com")
        assertEquals(3, list.size)
        assertEquals("alice@example.com", list[0].address)
        assertEquals("Bob", list[1].name)
        assertEquals("charlie@example.com", list[2].address)
    }

    @Test
    fun toStringRoundTrip() {
        val original = "Alice <alice@example.com>"
        val parsed = Address.parse(original) as Address.Mailbox
        assertEquals(original, parsed.toString())
    }
}

class EncodedWordTest {
    @Test
    fun decodeBase64() {
        val result = EncodedWord.decode("=?utf-8?B?SGVsbG8=?=")
        assertEquals("Hello", result)
    }

    @Test
    fun decodeQuotedPrintable() {
        // RFC 2047 Q: '_' is space, '=2C' is comma
        val result = EncodedWord.decode("=?utf-8?Q?Hello=2C_world?=")
        assertEquals("Hello, world", result)
    }

    @Test
    fun decodeQuotedPrintableUnderscore() {
        // '_' decodes to space (not literal underscore)
        val result = EncodedWord.decode("=?utf-8?Q?a_b_c?=")
        assertEquals("a b c", result)
    }

    @Test
    fun decodeMultipleWords() {
        // Two adjacent encoded-words (one space between them is consumed)
        val result = EncodedWord.decode("=?utf-8?B?SGVsbG8=?= =?utf-8?B?V29ybGQ=?=")
        assertEquals("HelloWorld", result)
    }

    @Test
    fun nonEncodedStaysAsIs() {
        assertEquals("plain text", EncodedWord.decode("plain text"))
    }

    @Test
    fun encodeB() {
        val encoded = EncodedWord.encodeB("Hello")
        assertEquals("=?utf-8?B?SGVsbG8=?=", encoded)
    }

    @Test
    fun encodeQ() {
        val encoded = EncodedWord.encodeQ("Hello, world")
        // space becomes underscore; comma is a printable ASCII so it's literal
        assertEquals("=?utf-8?Q?Hello,_world?=", encoded)
    }

    @Test
    fun encodeQSpecials() {
        // Characters that MUST be encoded: =, ?, _, non-ASCII
        val encoded = EncodedWord.encodeQ("a=b?c_dé")
        assertEquals("=?utf-8?Q?a=3Db=3Fc_d=C3=A9?=", encoded)
    }
}

class MimeParserTest {
    @Test
    fun parsesSimpleTextMessage() {
        val source = """
            From: alice@example.com
            To: bob@example.com
            Subject: Test
            MIME-Version: 1.0
            Content-Type: text/plain; charset=utf-8

            Hello, world!
        """.trimIndent().replace("\n", "\r\n")
        val msg = MimeParser.parse(source.encodeToByteArray())
        assertEquals("alice@example.com", msg.headers.firstValue("From"))
        assertEquals("Test", msg.headers.firstValue("Subject"))
        val body = msg.body as MimeMessage.Body.Single
        assertEquals("Hello, world!", body.content.decodeString("utf-8"))
    }

    @Test
    fun parsesQuotedPrintableBody() {
        val source = """
            From: a@x
            To: b@x
            Subject: t
            MIME-Version: 1.0
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            Hello=2C world=
            !
        """.trimIndent().replace("\n", "\r\n")
        val msg = MimeParser.parse(source.encodeToByteArray())
        val body = msg.body as MimeMessage.Body.Single
        assertEquals("Hello, world!", body.content.decodeString("utf-8"))
    }

    @Test
    fun parsesBase64Body() {
        val source = """
            From: a@x
            To: b@x
            Subject: t
            MIME-Version: 1.0
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: base64

            SGVsbG8sIHdvcmxkIQ==
        """.trimIndent().replace("\n", "\r\n")
        val msg = MimeParser.parse(source.encodeToByteArray())
        val body = msg.body as MimeMessage.Body.Single
        assertEquals("Hello, world!", body.content.decodeString("utf-8"))
    }

    @Test
    fun parsesMultipart() {
        val source = """
            From: a@x
            To: b@x
            Subject: t
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="BOUND"

            --BOUND
            Content-Type: text/plain; charset=utf-8

            Hello
            --BOUND
            Content-Type: text/html; charset=utf-8

            <p>Hello</p>
            --BOUND--
        """.trimIndent().replace("\n", "\r\n")
        val msg = MimeParser.parse(source.encodeToByteArray())
        val body = msg.body as MimeMessage.Body.Multipart
        assertEquals("BOUND", body.boundary)
        assertEquals(2, body.parts.size)
        val plain = body.parts[0]
        assertEquals("text/plain", plain.headers.contentType()?.mediaType)
        assertEquals("Hello", (plain.body as MimeMessage.Body.Single).content.decodeString("utf-8"))
        val html = body.parts[1]
        assertEquals("text/html", html.headers.contentType()?.mediaType)
        assertEquals("<p>Hello</p>", (html.body as MimeMessage.Body.Single).content.decodeString("utf-8"))
    }
}

class MimeWriterTest {
    @Test
    fun roundTripSimple() {
        val original = MimeMessage.text(
            from = "alice@example.com",
            to = "bob@example.com",
            subject = "Test",
            body = "Hello, world!",
        )
        val bytes = MimeWriter.write(original)
        val parsed = MimeParser.parse(bytes)
        assertEquals("alice@example.com", parsed.headers.firstValue("From"))
        assertEquals("Test", EncodedWord.decode(parsed.headers.firstValue("Subject")!!))
        assertEquals("Hello, world!", (parsed.body as MimeMessage.Body.Single).content.decodeString("utf-8"))
    }

    @Test
    fun roundTripMultipart() {
        val plain = MimeMessage(
            headers = Headers(
                listOf(
                    Header("Content-Type", "text/plain; charset=utf-8"),
                    Header("Content-Transfer-Encoding", "8bit"),
                ),
            ),
            body = MimeMessage.Body.Single("Hello".encodeToByteArray()),
        )
        val html = MimeMessage(
            headers = Headers(
                listOf(
                    Header("Content-Type", "text/html; charset=utf-8"),
                    Header("Content-Transfer-Encoding", "8bit"),
                ),
            ),
            body = MimeMessage.Body.Single("<p>Hello</p>".encodeToByteArray()),
        )
        val multi = MimeMessage(
            headers = Headers(
                listOf(
                    Header("From", "a@x"),
                    Header("To", "b@x"),
                    Header("MIME-Version", "1.0"),
                    Header("Content-Type", "multipart/alternative; boundary=BND"),
                ),
            ),
            body = MimeMessage.Body.Multipart(
                boundary = "BND",
                parts = listOf(plain, html),
            ),
        )
        val bytes = MimeWriter.write(multi)
        val parsed = MimeParser.parse(bytes)
        val body = parsed.body as MimeMessage.Body.Multipart
        assertEquals("BND", body.boundary)
        assertEquals(2, body.parts.size)
        assertEquals("Hello", (body.parts[0].body as MimeMessage.Body.Single).content.decodeString("utf-8"))
        assertEquals("<p>Hello</p>", (body.parts[1].body as MimeMessage.Body.Single).content.decodeString("utf-8"))
    }
}
