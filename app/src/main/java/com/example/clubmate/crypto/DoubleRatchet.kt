package com.example.clubmate.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X3DH session setup (without one-time prekeys), following Signal's "The X3DH Key Agreement
 * Protocol". Each user publishes an identity key IK (X25519) and a signed prekey SPK (X25519, signed
 * with their Ed25519 key). An initiator with ephemeral key EK computes
 *
 *     DH1 = DH(IK_A, SPK_B)   DH2 = DH(EK_A, IK_B)   DH3 = DH(EK_A, SPK_B)
 *     SK  = HKDF(0xFF*32 || DH1 || DH2 || DH3)
 *
 * and the responder computes the same SK from the other side of each exchange.
 */
object X3dh {

    private const val INFO = "ClubMate-X3DH-v1"
    private const val SIGNED_PREKEY_LABEL = "ClubMate-SPK-v1"
    private const val AD_LABEL = "ClubMate-DR-AD-v1"

    class Initiated(val sharedSecret: ByteArray, val ephemeralPublic: ByteArray)

    /** The bytes a user signs with their Ed25519 key to vouch for their signed prekey. */
    fun signedPreKeyData(uid: String, preKeyId: Int, preKeyPublic: ByteArray): ByteArray =
        E2eeCrypto.encodeFields(
            SIGNED_PREKEY_LABEL.toByteArray(Charsets.UTF_8),
            uid.toByteArray(Charsets.UTF_8),
            preKeyId.toString().toByteArray(Charsets.UTF_8),
            preKeyPublic
        )

    fun initiate(myIdentityPrivate: ByteArray, theirIdentity: ByteArray, theirSignedPreKey: ByteArray): Initiated =
        initiateWith(myIdentityPrivate, theirIdentity, theirSignedPreKey, X25519.generatePrivateKey())

    internal fun initiateWith(
        myIdentityPrivate: ByteArray,
        theirIdentity: ByteArray,
        theirSignedPreKey: ByteArray,
        ephemeralPrivate: ByteArray
    ): Initiated {
        val dh1 = dh(myIdentityPrivate, theirSignedPreKey)
        val dh2 = dh(ephemeralPrivate, theirIdentity)
        val dh3 = dh(ephemeralPrivate, theirSignedPreKey)
        return Initiated(kdf(dh1, dh2, dh3), X25519.publicFromPrivate(ephemeralPrivate))
    }

    fun respond(
        myIdentityPrivate: ByteArray,
        mySignedPreKeyPrivate: ByteArray,
        theirIdentity: ByteArray,
        theirEphemeral: ByteArray
    ): ByteArray {
        val dh1 = dh(mySignedPreKeyPrivate, theirIdentity)
        val dh2 = dh(myIdentityPrivate, theirEphemeral)
        val dh3 = dh(mySignedPreKeyPrivate, theirEphemeral)
        return kdf(dh1, dh2, dh3)
    }

    /** Associated data binding a session to both parties' identities (initiator first). */
    fun associatedData(
        initiatorUid: String,
        initiatorIdentity: ByteArray,
        responderUid: String,
        responderIdentity: ByteArray
    ): ByteArray = E2eeCrypto.encodeFields(
        AD_LABEL.toByteArray(Charsets.UTF_8),
        initiatorUid.toByteArray(Charsets.UTF_8), initiatorIdentity,
        responderUid.toByteArray(Charsets.UTF_8), responderIdentity
    )

    private fun kdf(vararg dhs: ByteArray): ByteArray {
        val input = ByteArrayOutputStream()
        input.write(ByteArray(32) { 0xFF.toByte() })
        dhs.forEach { input.write(it) }
        return Hkdf.computeHkdf(
            "HMACSHA256", input.toByteArray(), ByteArray(32), INFO.toByteArray(Charsets.UTF_8), 32
        )
    }

    internal fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (privateKey.size != 32 || publicKey.size != 32) throw GeneralSecurityException("Invalid key length")
        val out = X25519.computeSharedSecret(privateKey, publicKey)
        if (out.all { it == 0.toByte() }) throw GeneralSecurityException("Invalid public key (low-order point)")
        return out
    }
}

/** Header sent in the clear with every ratchet message (it is authenticated, not encrypted). */
class RatchetHeader(val dh: ByteArray, val previousChainLength: Int, val messageNumber: Int) {
    fun encode(): ByteArray = E2eeCrypto.encodeFields(
        "ClubMate-DR-header-v1".toByteArray(Charsets.UTF_8),
        dh,
        previousChainLength.toString().toByteArray(Charsets.UTF_8),
        messageNumber.toString().toByteArray(Charsets.UTF_8)
    )
}

/**
 * The state of one Double Ratchet session (Signal's "The Double Ratchet Algorithm", section 3).
 * Instances are never modified in place by [DoubleRatchet]: every operation returns a new state, so
 * a message that fails to decrypt can never corrupt the session.
 */
class RatchetState internal constructor(
    internal val dhsPrivate: ByteArray,
    internal val dhsPublic: ByteArray,
    internal val dhr: ByteArray?,
    internal val rootKey: ByteArray,
    internal val sendingChain: ByteArray?,
    internal val receivingChain: ByteArray?,
    internal val sendCount: Int,
    internal val receiveCount: Int,
    internal val previousSendCount: Int,
    internal val skipped: LinkedHashMap<String, ByteArray>,
    val associatedData: ByteArray
) {
    internal fun copy(
        dhsPrivate: ByteArray = this.dhsPrivate,
        dhsPublic: ByteArray = this.dhsPublic,
        dhr: ByteArray? = this.dhr,
        rootKey: ByteArray = this.rootKey,
        sendingChain: ByteArray? = this.sendingChain,
        receivingChain: ByteArray? = this.receivingChain,
        sendCount: Int = this.sendCount,
        receiveCount: Int = this.receiveCount,
        previousSendCount: Int = this.previousSendCount,
        skipped: LinkedHashMap<String, ByteArray> = LinkedHashMap(this.skipped)
    ) = RatchetState(
        dhsPrivate, dhsPublic, dhr, rootKey, sendingChain, receivingChain,
        sendCount, receiveCount, previousSendCount, skipped, associatedData
    )

    /** True once this side can send (always for the initiator; after the first reply for the responder). */
    val canSend: Boolean get() = sendingChain != null

    fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(FORMAT)
            writeBytes(out, dhsPrivate)
            writeBytes(out, dhsPublic)
            writeBytes(out, dhr)
            writeBytes(out, rootKey)
            writeBytes(out, sendingChain)
            writeBytes(out, receivingChain)
            out.writeInt(sendCount)
            out.writeInt(receiveCount)
            out.writeInt(previousSendCount)
            writeBytes(out, associatedData)
            out.writeInt(skipped.size)
            for ((id, key) in skipped) {
                out.writeUTF(id)
                writeBytes(out, key)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val FORMAT = 1

        fun deserialize(data: ByteArray): RatchetState = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readByte().toInt() != FORMAT) throw GeneralSecurityException("Unknown state format")
                val dhsPrivate = readBytes(input)!!
                val dhsPublic = readBytes(input)!!
                val dhr = readBytes(input)
                val rootKey = readBytes(input)!!
                val sendingChain = readBytes(input)
                val receivingChain = readBytes(input)
                val sendCount = input.readInt()
                val receiveCount = input.readInt()
                val previousSendCount = input.readInt()
                val ad = readBytes(input)!!
                val skipped = LinkedHashMap<String, ByteArray>()
                repeat(input.readInt()) { skipped[input.readUTF()] = readBytes(input)!! }
                RatchetState(
                    dhsPrivate, dhsPublic, dhr, rootKey, sendingChain, receivingChain,
                    sendCount, receiveCount, previousSendCount, skipped, ad
                )
            }
        } catch (e: IOException) {
            throw GeneralSecurityException("Corrupt ratchet state", e)
        } catch (e: NullPointerException) {
            throw GeneralSecurityException("Corrupt ratchet state", e)
        }

        internal fun writeBytes(out: DataOutputStream, value: ByteArray?) {
            if (value == null) {
                out.writeInt(-1)
            } else {
                out.writeInt(value.size)
                out.write(value)
            }
        }

        internal fun readBytes(input: DataInputStream): ByteArray? {
            val length = input.readInt()
            if (length < 0) return null
            if (length > 1 shl 20) throw IOException("Field too large")
            return ByteArray(length).also { input.readFully(it) }
        }
    }
}

/**
 * The Double Ratchet: a symmetric-key ratchet (a new key for every message, old keys deleted, giving
 * forward secrecy) combined with a Diffie-Hellman ratchet (fresh X25519 exchange whenever the
 * conversation changes direction, giving post-compromise security).
 */
object DoubleRatchet {

    /** Most message keys that may be skipped in one chain (bounds work for a malicious header). */
    const val MAX_SKIP = 1000

    /** Most skipped message keys kept per session; the oldest are dropped first. */
    const val MAX_STORED_SKIPPED = 2000

    private val RK_INFO = "ClubMate-DR-RK-v1".toByteArray(Charsets.UTF_8)
    private val MK_INFO = "ClubMate-DR-MK-v1".toByteArray(Charsets.UTF_8)

    class Encrypted(val state: RatchetState, val header: RatchetHeader, val ciphertext: ByteArray)
    class Decrypted(val state: RatchetState, val plaintext: ByteArray)

    /** Initiator (Alice): the responder's signed prekey is their first ratchet key. */
    fun initInitiator(sharedSecret: ByteArray, responderRatchetKey: ByteArray, ad: ByteArray): RatchetState =
        initInitiatorWith(sharedSecret, responderRatchetKey, ad, X25519.generatePrivateKey())

    internal fun initInitiatorWith(
        sharedSecret: ByteArray,
        responderRatchetKey: ByteArray,
        ad: ByteArray,
        ratchetPrivate: ByteArray
    ): RatchetState {
        val (rootKey, sendingChain) = kdfRootKey(sharedSecret, X3dh.dh(ratchetPrivate, responderRatchetKey))
        return RatchetState(
            dhsPrivate = ratchetPrivate,
            dhsPublic = X25519.publicFromPrivate(ratchetPrivate),
            dhr = responderRatchetKey,
            rootKey = rootKey,
            sendingChain = sendingChain,
            receivingChain = null,
            sendCount = 0,
            receiveCount = 0,
            previousSendCount = 0,
            skipped = LinkedHashMap(),
            associatedData = ad
        )
    }

    /** Responder (Bob): starts from the key pair of the signed prekey the initiator used. */
    fun initResponder(
        sharedSecret: ByteArray,
        signedPreKeyPrivate: ByteArray,
        ad: ByteArray
    ): RatchetState = RatchetState(
        dhsPrivate = signedPreKeyPrivate,
        dhsPublic = X25519.publicFromPrivate(signedPreKeyPrivate),
        dhr = null,
        rootKey = sharedSecret,
        sendingChain = null,
        receivingChain = null,
        sendCount = 0,
        receiveCount = 0,
        previousSendCount = 0,
        skipped = LinkedHashMap(),
        associatedData = ad
    )

    /** Encrypts with the next sending key. [extraAad] binds message metadata (ids, timestamp...). */
    fun encrypt(state: RatchetState, plaintext: ByteArray, extraAad: ByteArray): Encrypted {
        val chain = state.sendingChain ?: throw GeneralSecurityException("This session can't send yet")
        val (nextChain, messageKey) = kdfChainKey(chain)
        val header = RatchetHeader(state.dhsPublic, state.previousSendCount, state.sendCount)
        val ciphertext = seal(messageKey, plaintext, aad(state, header, extraAad))
        return Encrypted(
            state.copy(sendingChain = nextChain, sendCount = state.sendCount + 1),
            header,
            ciphertext
        )
    }

    /**
     * Decrypts a message and returns the new state. The given [state] is never modified, so a
     * forged or corrupted message leaves the session exactly as it was. Replays fail because the
     * message key no longer exists.
     */
    fun decrypt(state: RatchetState, header: RatchetHeader, ciphertext: ByteArray, extraAad: ByteArray): Decrypted {
        if (header.dh.size != 32 || header.messageNumber < 0 || header.previousChainLength < 0) {
            throw GeneralSecurityException("Malformed header")
        }

        // 1. A message from earlier in a chain whose key was set aside.
        val skippedId = skippedId(header.dh, header.messageNumber)
        state.skipped[skippedId]?.let { messageKey ->
            val plaintext = open(messageKey, ciphertext, aad(state, header, extraAad))
            val remaining = LinkedHashMap(state.skipped).also { it.remove(skippedId) }
            return Decrypted(state.copy(skipped = remaining), plaintext)
        }

        var s = state
        // 2. A new ratchet key from the other side: finish the old chain, then take a DH ratchet step.
        if (s.dhr == null || !header.dh.contentEquals(s.dhr)) {
            s = skipMessageKeys(s, header.previousChainLength)
            s = dhRatchet(s, header.dh)
        }
        // 3. Advance the receiving chain up to this message.
        s = skipMessageKeys(s, header.messageNumber)
        val chain = s.receivingChain ?: throw GeneralSecurityException("No receiving chain for this key")
        val (nextChain, messageKey) = kdfChainKey(chain)
        val plaintext = open(messageKey, ciphertext, aad(state, header, extraAad))
        return Decrypted(s.copy(receivingChain = nextChain, receiveCount = s.receiveCount + 1), plaintext)
    }

    private fun skipMessageKeys(state: RatchetState, until: Int): RatchetState {
        val chain = state.receivingChain ?: return state
        if (until <= state.receiveCount) return state
        if (until - state.receiveCount > MAX_SKIP) throw GeneralSecurityException("Too many skipped messages")

        val skipped = LinkedHashMap(state.skipped)
        var ck = chain
        var n = state.receiveCount
        while (n < until) {
            val (nextChain, messageKey) = kdfChainKey(ck)
            skipped[skippedId(state.dhr!!, n)] = messageKey
            ck = nextChain
            n++
        }
        while (skipped.size > MAX_STORED_SKIPPED) skipped.remove(skipped.keys.first())
        return state.copy(receivingChain = ck, receiveCount = n, skipped = skipped)
    }

    private fun dhRatchet(state: RatchetState, theirRatchetKey: ByteArray): RatchetState {
        val (rootKey1, receivingChain) = kdfRootKey(state.rootKey, X3dh.dh(state.dhsPrivate, theirRatchetKey))
        val newPrivate = X25519.generatePrivateKey()
        val (rootKey2, sendingChain) = kdfRootKey(rootKey1, X3dh.dh(newPrivate, theirRatchetKey))
        return state.copy(
            dhsPrivate = newPrivate,
            dhsPublic = X25519.publicFromPrivate(newPrivate),
            dhr = theirRatchetKey,
            rootKey = rootKey2,
            sendingChain = sendingChain,
            receivingChain = receivingChain,
            previousSendCount = state.sendCount,
            sendCount = 0,
            receiveCount = 0
        )
    }

    // KDF_RK: HKDF with the root key as salt -> (new root key, chain key)
    internal fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.computeHkdf("HMACSHA256", dhOutput, rootKey, RK_INFO, 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    // KDF_CK: HMAC with constants 0x02 (next chain key) and 0x01 (message key)
    internal fun kdfChainKey(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val messageKey = mac.doFinal(byteArrayOf(0x01))
        val nextChain = mac.doFinal(byteArrayOf(0x02))
        return nextChain to messageKey
    }

    private fun aad(state: RatchetState, header: RatchetHeader, extraAad: ByteArray): ByteArray =
        E2eeCrypto.encodeFields(state.associatedData, header.encode(), extraAad)

    // Each message key is used exactly once, so the AES key and nonce are both derived from it.
    private fun messageCipher(mode: Int, messageKey: ByteArray, aad: ByteArray): Cipher {
        val material = Hkdf.computeHkdf("HMACSHA256", messageKey, ByteArray(32), MK_INFO, 44)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(material, 0, 32, "AES"), GCMParameterSpec(128, material, 32, 12))
            updateAAD(aad)
        }
    }

    private fun seal(messageKey: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        messageCipher(Cipher.ENCRYPT_MODE, messageKey, aad).doFinal(plaintext)

    private fun open(messageKey: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size < 16) throw GeneralSecurityException("Ciphertext too short")
        return messageCipher(Cipher.DECRYPT_MODE, messageKey, aad).doFinal(ciphertext)
    }

    private fun skippedId(dh: ByteArray, n: Int): String =
        dh.joinToString("") { "%02x".format(it) } + ":" + n
}
