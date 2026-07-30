package com.gios.lightremote.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 key agreement (RFC 7748) and Ed25519 signatures (RFC 8032).
 *
 * Written against BigInteger rather than pulled in from BouncyCastle. The platform's
 * own Ed25519/XDH providers only appeared in API 33 and their raw-key handling differs
 * between Conscrypt and OpenJDK, which would make the pairing handshake behave one way
 * in a unit test and another on the phone. BouncyCastle would fix that but costs ~8 MB
 * of APK for the dozen operations one pairing needs. This is a few hundred lines with
 * RFC test vectors behind it, and pairing does maybe 15 scalar multiplications total,
 * so the arbitrary-precision arithmetic is not worth optimising.
 *
 * Not constant time. That is a deliberate, bounded trade: the only secrets involved are
 * the ephemeral X25519 key and the long-term Ed25519 key, both used against an Apple TV
 * on the local network, and there is no remote timing oracle to attack here.
 */
object Curve25519 {

    private val TWO = BigInteger.valueOf(2)

    /** p = 2^255 - 19 */
    val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** Group order: q = 2^252 + 27742317777372353535851937790883648493 */
    val Q: BigInteger = TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665 / 121666 mod p */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** I = sqrt(-1) mod p, used to recover x from y. */
    private val SQRT_M1: BigInteger =
        TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    // ------------------------------------------------------------------ helpers

    /** Little-endian, fixed width. HAP puts every scalar and point on the wire this way. */
    fun toLeBytes(value: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var v = value.mod(TWO.pow(size * 8))
        for (i in 0 until size) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    fun fromLeBytes(bytes: ByteArray): BigInteger {
        var v = BigInteger.ZERO
        for (i in bytes.indices.reversed()) {
            v = v.shiftLeft(8).or(BigInteger.valueOf((bytes[i].toInt() and 0xFF).toLong()))
        }
        return v
    }

    // ------------------------------------------------------------------ X25519

    private val A24 = BigInteger.valueOf(121665)

    /**
     * Clamp a 32-byte seed into a valid X25519 scalar: clear the low three bits so it is
     * a multiple of the cofactor, and pin bit 254 so the ladder always runs full length.
     */
    private fun clampScalar(seed: ByteArray): BigInteger {
        require(seed.size == 32) { "X25519 scalar must be 32 bytes" }
        val k = seed.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return fromLeBytes(k)
    }

    /** Montgomery ladder from RFC 7748 section 5. */
    private fun scalarMultMontgomery(scalar: BigInteger, uCoord: BigInteger): BigInteger {
        var x1 = uCoord
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = uCoord
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = if (scalar.testBit(t)) 1 else 0
            if ((swap xor bit) == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = bit

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).let { it.multiply(it) }.mod(P)
            z3 = da.subtract(cb).let { it.multiply(it) }.multiply(x1).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            val tmp = x2; x2 = x3; x3 = tmp
            val tz = z2; z2 = z3; z3 = tz
        }
        return x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P)
    }

    private val BASE_U = BigInteger.valueOf(9)

    fun x25519PublicKey(privateKey: ByteArray): ByteArray =
        toLeBytes(scalarMultMontgomery(clampScalar(privateKey), BASE_U), 32)

    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        // RFC 7748: the high bit of the u-coordinate is ignored on input.
        val masked = peerPublicKey.copyOf()
        masked[31] = (masked[31].toInt() and 127).toByte()
        val shared = scalarMultMontgomery(clampScalar(privateKey), fromLeBytes(masked))
        val out = toLeBytes(shared, 32)
        // An all-zero shared secret means a small-order peer key; refuse it rather than
        // deriving session keys everyone can predict.
        require(out.any { it != 0.toByte() }) { "X25519 produced an all-zero shared secret" }
        return out
    }

    // ------------------------------------------------------------------ Ed25519

    /** A point on the twisted Edwards curve -x^2 + y^2 = 1 + d*x^2*y^2, affine. */
    private data class Point(val x: BigInteger, val y: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    private val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        Point(recoverX(y, 0)!!, y)
    }

    private fun add(p1: Point, p2: Point): Point {
        val a = p1.x.multiply(p2.y).mod(P)
        val b = p1.y.multiply(p2.x).mod(P)
        val c = p1.y.multiply(p2.y).mod(P)
        val e = p1.x.multiply(p2.x).mod(P)
        val dxy = D.multiply(e).multiply(c).mod(P)
        val x = a.add(b).multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P)
        // a = -1, so the numerator is y1*y2 + x1*x2.
        val y = c.add(e).multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P)
        return Point(x, y)
    }

    private fun scalarMult(k: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    /** Recover x from y and the wanted sign bit, or null if y is not on the curve. */
    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val num = y2.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (den.signum() == 0) return null
        val xx = num.multiply(den.modInverse(P)).mod(P)
        if (xx.signum() == 0) {
            return if (sign == 0) BigInteger.ZERO else null
        }
        // Candidate square root via the (p+3)/8 exponent, then fix up by sqrt(-1).
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun encodePoint(p: Point): ByteArray {
        val out = toLeBytes(p.y, 32)
        if (p.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decodePoint(data: ByteArray): Point? {
        if (data.size != 32) return null
        val copy = data.copyOf()
        val sign = (copy[31].toInt() shr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = fromLeBytes(copy)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    /** Expand a 32-byte Ed25519 seed into (scalar, prefix) per RFC 8032 section 5.1.5. */
    private fun expandSeed(seed: ByteArray): Pair<BigInteger, ByteArray> {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val h = Digest.sha512(seed)
        val lower = h.copyOfRange(0, 32)
        lower[0] = (lower[0].toInt() and 248).toByte()
        lower[31] = (lower[31].toInt() and 63).toByte()
        lower[31] = (lower[31].toInt() or 64).toByte()
        return fromLeBytes(lower) to h.copyOfRange(32, 64)
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        val (scalar, _) = expandSeed(seed)
        return encodePoint(scalarMult(scalar, BASE))
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val (scalar, prefix) = expandSeed(seed)
        val publicKey = encodePoint(scalarMult(scalar, BASE))
        val r = fromLeBytes(Digest.sha512(prefix, message)).mod(Q)
        val bigR = encodePoint(scalarMult(r, BASE))
        val k = fromLeBytes(Digest.sha512(bigR, publicKey, message)).mod(Q)
        val s = r.add(k.multiply(scalar)).mod(Q)
        return bigR + toLeBytes(s, 32)
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val bigR = signature.copyOfRange(0, 32)
        val s = fromLeBytes(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val pointR = decodePoint(bigR) ?: return false
        val pointA = decodePoint(publicKey) ?: return false
        val k = fromLeBytes(Digest.sha512(bigR, publicKey, message)).mod(Q)
        // Cofactorless check, which is what RFC 8032's verify equation specifies:
        // [s]B == R + [k]A
        val left = scalarMult(s, BASE)
        val right = add(pointR, scalarMult(k, pointA))
        return left == right
    }

    // ------------------------------------------------------------------ key generation

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
