package pw.binom.mail.sasl

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * SASL mechanism implementations.
 */
public object Sasl {
    /**
     * Builds the initial response for AUTH PLAIN.
     *
     * Per RFC 4616, the format is `authzid\0authcid\0password` UTF-8 encoded,
     * then Base64-encoded. If [authzid] is null, the empty string is used
     * (so the leading `\0` is still present).
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun plainInitialResponse(authcid: String, password: String, authzid: String? = null): String {
        val n = "\u0000"
        val data = "${authzid ?: ""}$n$authcid$n$password".encodeToByteArray()
        return Base64.encode(data)
    }

    /**
     * Decodes a PLAIN initial response (Base64 of `authzid\0authcid\0password`).
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun parsePlainResponse(base64: String): Triple<String?, String, String> {
        val raw = Base64.decode(base64)
        val str = raw.decodeToString()
        val n = '\u0000'
        val first = str.indexOf(n)
        val second = str.indexOf(n, first + 1)
        val authzid = if (first == 0) null else str.substring(0, first)
        val authcid = str.substring(first + 1, second)
        val password = str.substring(second + 1)
        return Triple(authzid, authcid, password)
    }

    /**
     * SCRAM-SHA-256 client side: builds the client-first-message.
     *
     * @param authcid the authentication identity (username)
     * @param authzid optional authorization identity
     * @param nonce the client nonce
     */
    public fun scramClientFirst(authcid: String, authzid: String? = null, nonce: String): String {
        val gs2Header = if (authzid == null) "n,," else "n,$authzid,"
        val bare = "n=${escapeSasl(authcid)},r=$nonce"
        return gs2Header + bare
    }

    /**
     * Decodes the server-first-message, returning a [ScramFirstServer]
     * record.
     */
    public fun parseScramServerFirst(message: String): ScramFirstServer {
        val parts = message.split(',')
        var nonce: String? = null
        var salt: String? = null
        var iterations: Int = 0
        for (p in parts) {
            when {
                p.startsWith("r=") -> nonce = p.substring(2)
                p.startsWith("s=") -> salt = p.substring(2)
                p.startsWith("i=") -> iterations = p.substring(2).toInt()
            }
        }
        return ScramFirstServer(
            nonce = requireNotNull(nonce) { "missing nonce in server-first" },
            salt = requireNotNull(salt) { "missing salt in server-first" },
            iterations = iterations,
        )
    }

    /**
     * Builds the client-final-message and the ClientProof.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun scramClientFinal(
        clientFirstBare: String,
        serverFirst: ScramFirstServer,
        clientNonce: String,
        authcid: String,
        password: String,
    ): ScramClientFinal {
        val channelBinding = "c=" + (clientFirstBare.substringBefore(',').length + clientFirstBare.length).toString().let { padding ->
            // GS2 header + client-first-bare encoded as base64 would go here, but we use "n,," so the
            // channel binding is "c=biws" (base64 of "n,,").
            "biws"
        }
        val clientFinalNoProof = "c=biws,r=${serverFirst.nonce}"
        val authMessage = "$clientFirstBare,$serverFirst.nonceOrFirst,$clientFinalNoProof".let { all ->
            all
        }
        // For simplicity, the authMessage is: client-first-bare + "," + server-first + "," + client-final-without-proof
        val am = "$clientFirstBare,${serverFirst.raw},$clientFinalNoProof"
        val saltBytes = Base64.decode(serverFirst.salt)
        val saltedPassword = pbkdf2(password.encodeToByteArray(), saltBytes, serverFirst.iterations, 32)
        val clientKey = hmac(saltedPassword, "Client Key".encodeToByteArray())
        val storedKey = sha256(clientKey)
        val clientSignature = hmac(storedKey, am.encodeToByteArray())
        val clientProof = xor(clientKey, clientSignature)
        val proofB64 = Base64.encode(clientProof)
        val serverKey = hmac(saltedPassword, "Server Key".encodeToByteArray())
        val serverSignature = hmac(serverKey, am.encodeToByteArray())
        val finalMessage = "$clientFinalNoProof,p=$proofB64"
        return ScramClientFinal(
            message = finalMessage,
            saltedPassword = saltedPassword,
            storedKey = storedKey,
            serverSignature = serverSignature,
        )
    }

    /**
     * Verifies the server-final-message against the ServerSignature computed by
     * [scramClientFinal] (`ServerSignature = HMAC(HMAC(saltedPassword, "Server Key"), authMessage)`).
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun scramVerifyServerFinal(serverFinal: String, expectedServerSignature: ByteArray): Boolean {
        val parts = serverFinal.split(',')
        for (p in parts) {
            if (p.startsWith("v=")) {
                val received = Base64.decode(p.substring(2))
                return received.contentEquals(expectedServerSignature)
            }
        }
        return false
    }

    private fun escapeSasl(s: String): String =
        s.replace("=", "=3D").replace(",", "=2C")

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        // PBKDF2-HMAC-SHA-256 implementation
        val hmacLen = 32
        val blocks = (dkLen + hmacLen - 1) / hmacLen
        val result = ByteArray(blocks * hmacLen)
        for (i in 1..blocks) {
            val block = pbkdf2Block(password, salt, iterations, i)
            block.copyInto(result, (i - 1) * hmacLen, 0, hmacLen)
        }
        return result.copyOf(dkLen)
    }

    private fun pbkdf2Block(password: ByteArray, salt: ByteArray, iterations: Int, index: Int): ByteArray {
        val intIndex = ByteArray(4)
        intIndex[0] = ((index ushr 24) and 0xFF).toByte()
        intIndex[1] = ((index ushr 16) and 0xFF).toByte()
        intIndex[2] = ((index ushr 8) and 0xFF).toByte()
        intIndex[3] = (index and 0xFF).toByte()
        val u1 = hmac(password, salt + intIndex)
        var result = u1
        var u = u1
        for (i in 1 until iterations) {
            u = hmac(password, u)
            result = xor(result, u)
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val k = if (key.size > blockSize) sha256(key) else key
        val actualKey = if (k.size < blockSize) k.copyOf(blockSize) else k
        val ipad = ByteArray(blockSize) { (actualKey[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { (actualKey[it].toInt() xor 0x5C).toByte() }
        val inner = sha256(ipad + data)
        return sha256(opad + inner)
    }

    private fun sha256(data: ByteArray): ByteArray = Sha256.digest(data)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in a.indices) {
            out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return out
    }
}

public data class ScramFirstServer(
    val nonce: String,
    val salt: String,
    val iterations: Int,
) {
    val raw: String = "r=$nonce,s=$salt,i=$iterations"
    val nonceOrFirst: String = raw
}

public data class ScramClientFinal(
    val message: String,
    val saltedPassword: ByteArray,
    val storedKey: ByteArray,
    val serverSignature: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = saltedPassword.contentHashCode()
}
