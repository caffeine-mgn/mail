package pw.binom.mail.sasl

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaslTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun plainInitialResponse() {
        val response = Sasl.plainInitialResponse("alice", "secret")
        val raw = Base64.decode(response)
        val str = raw.decodeToString()
        assertEquals("\u0000alice\u0000secret", str)
    }

    @Test
    fun plainRoundTrip() {
        val response = Sasl.plainInitialResponse("alice", "secret")
        val (authzid, authcid, password) = Sasl.parsePlainResponse(response)
        assertNull(authzid)
        assertEquals("alice", authcid)
        assertEquals("secret", password)
    }

    @Test
    fun plainWithAuthzid() {
        val response = Sasl.plainInitialResponse("alice", "secret", authzid = "bob")
        val (authzid, authcid, password) = Sasl.parsePlainResponse(response)
        assertEquals("bob", authzid)
        assertEquals("alice", authcid)
        assertEquals("secret", password)
    }

    @Test
    fun scramServerFirst() {
        val s = Sasl.parseScramServerFirst("r=abc123def456,s=c2FsdA==,i=4096")
        assertEquals("abc123def456", s.nonce)
        assertEquals("c2FsdA==", s.salt)
        assertEquals(4096, s.iterations)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun scramKnownVector() {
        val clientFirstBare = "n=user,r=rOprNGfwEbeRWgbNEkqO"
        val serverFirst =
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
        val sf = Sasl.parseScramServerFirst(serverFirst)
        val result = Sasl.scramClientFinal(clientFirstBare, sf, "rOprNGfwEbeRWgbNEkqO", "user", "pencil")
        assertEquals(
            "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0," +
                "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
            result.message,
        )
        assertEquals(
            "6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=",
            Base64.encode(result.serverSignature),
            "ServerSignature must match RFC 7677 vector",
        )
        assertTrue(
            Sasl.scramVerifyServerFinal("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=", result.serverSignature),
            "server signature must verify against RFC 7677 vector",
        )
    }
}
