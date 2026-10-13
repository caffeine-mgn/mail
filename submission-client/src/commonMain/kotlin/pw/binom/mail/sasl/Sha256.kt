package pw.binom.mail.sasl

/**
 * Minimal pure-Kotlin SHA-256 (FIPS 180-4).
 *
 * Implemented in common code so SCRAM-SHA-256 works on every KMP target
 * (the JVM-only `java.security.MessageDigest` is not available on native).
 */
internal object Sha256 {
    private val K: IntArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    private val H0: IntArray = longArrayOf(
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ).map { it.toInt() }.toIntArray()

    fun digest(data: ByteArray): ByteArray {
        val ml = data.size.toLong() * 8
        val padLen = ((56 - (data.size + 1) % 64) + 64) % 64
        val msg = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[msg.size - 1 - i] = ((ml ushr (8 * i)) and 0xFF).toByte()
        }

        var h0 = H0[0]
        var h1 = H0[1]
        var h2 = H0[2]
        var h3 = H0[3]
        var h4 = H0[4]
        var h5 = H0[5]
        var h6 = H0[6]
        var h7 = H0[7]

        val w = IntArray(64)
        var offset = 0
        while (offset < msg.size) {
            for (t in 0 until 16) {
                val j = offset + t * 4
                w[t] = ((msg[j].toInt() and 0xFF) shl 24) or
                    ((msg[j + 1].toInt() and 0xFF) shl 16) or
                    ((msg[j + 2].toInt() and 0xFF) shl 8) or
                    (msg[j + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (t in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[t] + w[t]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        intToBytes(h0, out, 0)
        intToBytes(h1, out, 4)
        intToBytes(h2, out, 8)
        intToBytes(h3, out, 12)
        intToBytes(h4, out, 16)
        intToBytes(h5, out, 20)
        intToBytes(h6, out, 24)
        intToBytes(h7, out, 28)
        return out
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun intToBytes(v: Int, out: ByteArray, off: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }
}
