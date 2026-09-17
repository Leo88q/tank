package io.github.leo88q.orbitfall.mwa

import android.util.Base64
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** Minimal Solana tooling for building unsigned legacy transactions. */
object SolTx {

    const val PROGRAM_ID = "AeuAXhwzbULEoR3gi66RpFZNwDZx6i17i7gSgP1gUqeH"
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val DEVNET_RPC = "https://api.devnet.solana.com"

    // ---------------- base58 ----------------
    private const val ALPHA = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun b58decode(s: String): ByteArray {
        var num = BigInteger.ZERO
        for (c in s) {
            val v = ALPHA.indexOf(c)
            require(v >= 0) { "bad base58 char" }
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(v.toLong()))
        }
        var bytes = num.toByteArray()
        if (bytes.size > 33 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
        require(bytes.size <= 32) { "base58 too long" }
        val out = ByteArray(32)
        System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        var lead = 0
        while (lead < s.length && s[lead] == '1') lead++
        for (i in 0 until lead) out[i] = 0
        return out
    }

    // ---------------- ed25519 on-curve check (for PDA search) ----------------
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    private val BX = recoverX(BY, 0).let { x -> if (x.mod(BigInteger.valueOf(2)) != BigInteger.ZERO) P.subtract(x) else x }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger {
        val y2 = y.modPow(BigInteger.valueOf(2), P)
        val x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.modPow(BigInteger.valueOf(2), P) != x2) {
            x = x.multiply(BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)).mod(P)
        }
        if (x.mod(BigInteger.valueOf(2)).toInt() != sign) x = P.subtract(x)
        return x
    }

    fun isOnCurve(b: ByteArray): Boolean {
        return try {
            val v = BigInteger(1, b.reversedArray())
            val y = v.and(BigInteger.valueOf(2).pow(255).subtract(BigInteger.ONE))
            if (y >= P) return false
            val x = recoverX(y, v.shiftRight(255).toInt())
            val x2 = x.modPow(BigInteger.valueOf(2), P)
            val y2 = y.modPow(BigInteger.valueOf(2), P)
            // edwards curve -x^2 + y^2 = 1 + d x^2 y^2 (mod p)
            val lhs = y2.subtract(x2).mod(P)
            val rhs = BigInteger.ONE.add(D.multiply(x2).mod(P).multiply(y2).mod(P)).mod(P)
            lhs == rhs
        } catch (e: Exception) {
            false
        }
    }

    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Int> {
        for (nonce in 255 downTo 0) {
            val md = MessageDigest.getInstance("SHA-512")
            for (s in seeds) md.update(s)
            md.update(byteArrayOf(nonce.toByte()))
            md.update(programId)
            md.update("ProgramDerivedAddress".toByteArray())
            val h = md.digest()
            val candidate = littleModL(h)
            if (candidate != null && !isOnCurve(candidate)) {
                return candidate to nonce
            }
        }
        throw IllegalStateException("PDA not found")
    }

    /** PDA bytes = sha512 digest interpreted little-endian mod L is NOT how PDAs work:
     *  a PDA is the raw 32-byte digest (little-endian encoded point candidate) itself.
     *  We return the raw digest bytes (LE form) and rely on isOnCurve check. */
    private fun littleModL(h: ByteArray): ByteArray? {
        // raw 32-byte little-endian candidate
        return h.copyOfRange(0, 32)
    }

    // ---------------- anchor helpers ----------------
    fun discriminator(name: String): ByteArray {
        val d = MessageDigest.getInstance("SHA-256").digest("global:$name".toByteArray())
        return d.copyOfRange(0, 8)
    }

    fun leU64(v: Long): ByteArray {
        val out = ByteArray(8)
        var x = v
        for (i in 0..7) { out[i] = (x and 0xff).toByte(); x = x ushr 8 }
        return out
    }

    fun compactU16(v: Int): ByteArray {
        // shortvec encoding
        var x = v
        val out = ArrayList<Byte>()
        while (true) {
            var elem = x and 0x7f
            x = x shr 7
            if (x == 0) { out.add(elem.toByte()); break }
            elem = elem or 0x80
            out.add(elem.toByte())
        }
        return out.toByteArray()
    }

    data class Meta(val key: ByteArray, val signer: Boolean, val writable: Boolean)
    data class Ix(val program: ByteArray, val accounts: List<Meta>, val data: ByteArray)

    fun serializeMessage(feePayer: ByteArray, blockhash: ByteArray, ixs: List<Ix>): ByteArray {
        val signers = LinkedHashSet<String>()
        val roSigners = LinkedHashSet<String>()
        val writable = LinkedHashSet<String>()
        val readonly = LinkedHashSet<String>()
        fun key(m: Meta) = Base58.encode(m.key)
        for (ix in ixs) {
            readonly.add(Base58.encode(ix.program))
            for (m in ix.accounts) {
                when {
                    m.signer && m.writable -> signers.add(key(m))
                    m.signer -> roSigners.add(key(m))
                    m.writable -> writable.add(key(m))
                    else -> readonly.add(key(m))
                }
            }
        }
        val payerKey = Base58.encode(feePayer)
        signers.add(payerKey) // payer first
        val ordered = mutableListOf<Meta>()
        fun metaFor(k: String): Meta {
            for (ix in ixs) {
                for (m in ix.accounts) if (key(m) == k) return m
                if (Base58.encode(ix.program) == k) return Meta(ix.program, false, false)
            }
            return Meta(feePayer, true, true)
        }
        for (k in signers) ordered.add(metaFor(k).copy(signer = true, writable = true))
        for (k in roSigners) if (k !in signers) ordered.add(metaFor(k).copy(signer = true, writable = false))
        for (k in writable) if (k !in signers && k !in roSigners) ordered.add(metaFor(k).copy(signer = false, writable = true))
        for (k in readonly) if (k !in signers && k !in roSigners && k !in writable) ordered.add(metaFor(k).copy(signer = false, writable = false))

        val indexOf = ordered.map { Base58.encode(it.key) }
        val numReq = ordered.count { it.signer }
        val roSigned = ordered.count { it.signer && !it.writable }
        val roUnsigned = ordered.count { !it.signer && !it.writable }

        val out = ArrayList<Byte>()
        out.add(numReq.toByte())
        out.add(roSigned.toByte())
        out.add(roUnsigned.toByte())
        out.addAll(compactU16(ordered.size).toList())
        for (m in ordered) out.addAll(m.key.toList())
        out.addAll(compactU16(ixs.size).toList())
        for (ix in ixs) {
            out.add(indexOf.indexOf(Base58.encode(ix.program)).toByte())
            out.addAll(compactU16(ix.accounts.size).toList())
            for (m in ix.accounts) out.add(indexOf.indexOf(Base58.encode(m.key)).toByte())
            out.addAll(compactU16(ix.data.size).toList())
            out.addAll(ix.data.toList())
        }
        out.addAll(blockhash.toList())
        return out.toByteArray()
    }

    // ---------------- rpc ----------------
    fun latestBlockhash(rpc: String = DEVNET_RPC): ByteArray {
        val url = URL(rpc)
        val con = url.openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json")
        val body = """{"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"confirmed"}]}"""
        OutputStreamWriter(con.outputStream).use { it.write(body) }
        val resp = con.inputStream.bufferedReader().readText()
        val json = JSONObject(resp)
        val value = json.getJSONObject("result").getJSONObject("value")
        return b58decode(value.getString("blockhash"))
    }

    fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}

/** Builders for the orbitfall-match program instructions (devnet). */
object MatchTx {
    private val prog = SolTx.b58decode(SolTx.PROGRAM_ID)
    private val system = SolTx.b58decode(SolTx.SYSTEM_PROGRAM)

    fun matchPda(creator: ByteArray): ByteArray =
        SolTx.findProgramAddress(listOf("match".toByteArray(), creator), prog).first

    fun vaultPda(creator: ByteArray): ByteArray =
        SolTx.findProgramAddress(listOf("vault".toByteArray(), creator), prog).first

    fun create(wallet: ByteArray, stake: Long, timeoutSec: Long): ByteArray {
        val ms = matchPda(wallet); val vault = vaultPda(wallet)
        val data = SolTx.discriminator("create_match") + SolTx.leU64(stake) + SolTx.leU64(timeoutSec)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true),
            SolTx.Meta(vault, false, true),
            SolTx.Meta(system, false, false)
        ), data)
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }

    fun join(wallet: ByteArray, creator: ByteArray): ByteArray {
        val ms = matchPda(creator); val vault = vaultPda(creator)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true),
            SolTx.Meta(vault, false, true),
            SolTx.Meta(system, false, false)
        ), SolTx.discriminator("join_match"))
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }

    fun consent(wallet: ByteArray, creator: ByteArray, winner: Int): ByteArray {
        val ms = matchPda(creator)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true)
        ), SolTx.discriminator("consent") + byteArrayOf(winner.toByte()))
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }

    fun settle(wallet: ByteArray, creator: ByteArray, joiner: ByteArray): ByteArray {
        val ms = matchPda(creator); val vault = vaultPda(creator)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true),
            SolTx.Meta(creator, false, true),
            SolTx.Meta(joiner, false, true),
            SolTx.Meta(vault, false, true),
            SolTx.Meta(system, false, false)
        ), SolTx.discriminator("settle"))
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }

    fun refund(wallet: ByteArray, creator: ByteArray, joiner: ByteArray): ByteArray {
        val ms = matchPda(creator); val vault = vaultPda(creator)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true),
            SolTx.Meta(creator, false, true),
            SolTx.Meta(joiner, false, true),
            SolTx.Meta(vault, false, true),
            SolTx.Meta(system, false, false)
        ), SolTx.discriminator("timeout_refund"))
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }

    fun cancel(wallet: ByteArray, creator: ByteArray): ByteArray {
        val ms = matchPda(creator); val vault = vaultPda(creator)
        val ix = SolTx.Ix(prog, listOf(
            SolTx.Meta(wallet, true, true),
            SolTx.Meta(ms, false, true),
            SolTx.Meta(vault, false, true),
            SolTx.Meta(system, false, false)
        ), SolTx.discriminator("cancel"))
        return SolTx.serializeMessage(wallet, SolTx.latestBlockhash(), listOf(ix))
    }
}
