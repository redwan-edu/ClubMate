package com.example.clubmate.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption primitives for ClubMate (basic Diffie-Hellman mode).
 *
 * 1:1 chats
 * ---------
 *
 * Every user owns one long-term X25519 key pair. The private key never leaves the device and the
 * public key is published in Firebase. For two users A and B:
 *
 *     shared = X25519(a, B) = X25519(b, A)                      (Diffie-Hellman)
 *     key    = HKDF-SHA256(shared, salt, info = both uids + both public keys)
 *
 * Both participants derive the same 32-byte AES key without it ever being sent anywhere. Each message
 * is then sealed with AES-256-GCM using a fresh random 12-byte nonce. The associated data (AAD) binds
 * the ciphertext to its chat, message id, sender, receiver, type, timestamp and the exact public keys
 * used, so the server cannot move, re-attribute or silently alter a message.
 *
 * Groups
 * ------
 * A group shares a random 32-byte group key. It is handed to each member encrypted with the 1:1 key
 * above (so only members can unwrap it) and replaced whenever the membership changes. Group messages
 * are AES-256-GCM encrypted with the group key and signed with the sender's Ed25519 key, so members
 * cannot impersonate each other.
 *
 * Private channels
 * ----------------
 * The channel key is derived from the channel password with PBKDF2-HMAC-SHA256 (random salt, many
 * iterations) followed by HKDF. Only the salt and a verifier derived from the same secret are stored.
 *
 * Images
 * ------
 * Image files are encrypted on the device with a fresh random AES-256-GCM key before upload. That key
 * travels only inside the (already encrypted) message, so the file host sees random bytes.
 *
 * This file is pure Kotlin/JCA + Tink (no Android APIs) so it can be unit-tested on the JVM.
 */
object E2eeCrypto {

    /** Protocol version written to every encrypted message (`v` field). */
    const val VERSION = 1

    /** X25519 private/public key length and AES-256 key length, in bytes. */
    const val KEY_SIZE = 32

    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val TAG_SIZE = TAG_BITS / 8

    private val HKDF_SALT = sha256("ClubMate-E2EE-v1-salt".toByteArray(Charsets.UTF_8))
    private const val HKDF_INFO_LABEL = "ClubMate-E2EE-v1-conversation-key"
    private const val AAD_LABEL = "ClubMate-E2EE-v1-message"
    private const val CONTENT_AAD_LABEL = "ClubMate-E2EE-v1-content"
    private const val SIGNATURE_LABEL = "ClubMate-E2EE-v1-signature"
    private const val CHANNEL_KEY_LABEL = "ClubMate-E2EE-v1-channel-key"
    private const val CHANNEL_VERIFIER_LABEL = "ClubMate-E2EE-v1-channel-verifier"
    private const val ATTACHMENT_AAD_LABEL = "ClubMate-E2EE-v1-attachment"

    /** Ed25519 signature length, in bytes. */
    const val SIGNATURE_SIZE = 64

    /** Salt length for password-derived channel keys, in bytes. */
    const val SALT_SIZE = 16

    /** PBKDF2 iterations for new private channels (stored with the channel so it can be raised later). */
    const val CHANNEL_ITERATIONS = 200_000

    private val random = SecureRandom()

    fun generatePrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        requireKeySize(privateKey, "private key")
        return X25519.publicFromPrivate(privateKey)
    }

    /**
     * Derives the symmetric key shared by [myUid] and [theirUid]. Both sides get the same result
     * because the HKDF info is built from the two (uid, public key) pairs in a fixed (sorted) order.
     */
    fun conversationKey(
        myUid: String,
        myPrivateKey: ByteArray,
        theirUid: String,
        theirPublicKey: ByteArray
    ): ByteArray {
        requireKeySize(myPrivateKey, "private key")
        requireKeySize(theirPublicKey, "public key")

        val shared = X25519.computeSharedSecret(myPrivateKey, theirPublicKey)
        // An all-zero result means the peer sent a low-order point: refuse to use it.
        if (shared.all { it == 0.toByte() }) {
            throw GeneralSecurityException("Invalid public key (low-order point)")
        }

        val mine = myUid to publicKeyOf(myPrivateKey)
        val theirs = theirUid to theirPublicKey
        val (first, second) = if (mine.first <= theirs.first) mine to theirs else theirs to mine

        val info = encodeFields(
            HKDF_INFO_LABEL.toByteArray(Charsets.UTF_8),
            first.first.toByteArray(Charsets.UTF_8), first.second,
            second.first.toByteArray(Charsets.UTF_8), second.second
        )
        return Hkdf.computeHkdf("HMACSHA256", shared, HKDF_SALT, info, KEY_SIZE)
    }

    /** Associated data that every encrypted message is bound to. */
    fun messageAad(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        senderPublicKey: ByteArray,
        receiverPublicKey: ByteArray
    ): ByteArray = encodeFields(
        AAD_LABEL.toByteArray(Charsets.UTF_8),
        context.toByteArray(Charsets.UTF_8),
        chatId.toByteArray(Charsets.UTF_8),
        messageId.toByteArray(Charsets.UTF_8),
        senderId.toByteArray(Charsets.UTF_8),
        receiverId.toByteArray(Charsets.UTF_8),
        messageType.toByteArray(Charsets.UTF_8),
        timestamp.toString().toByteArray(Charsets.UTF_8),
        senderPublicKey,
        receiverPublicKey
    )

    /** AES-256-GCM encryption. Returns `nonce || ciphertext || tag`. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        return encryptWithNonce(key, nonce, plaintext, aad)
    }

    /** Reverses [encrypt]. Throws [GeneralSecurityException] if anything was tampered with. */
    fun decrypt(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        requireKeySize(key, "message key")
        if (sealed.size < NONCE_SIZE + TAG_SIZE) {
            throw GeneralSecurityException("Ciphertext too short")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_SIZE)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
    }

    // ------------------------------------------------------------------ signatures (Ed25519)

    class SigningKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateSigningKeyPair(): SigningKeyPair {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        return SigningKeyPair(pair.privateKey, pair.publicKey)
    }

    fun signingPublicKeyOf(privateKey: ByteArray): ByteArray {
        requireKeySize(privateKey, "signing key")
        return Ed25519Sign.KeyPair.newKeyPairFromSeed(privateKey).publicKey
    }

    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        requireKeySize(privateKey, "signing key")
        return Ed25519Sign(privateKey).sign(data)
    }

    /** Returns true only for a valid signature by [publicKey] over exactly [data]. */
    fun verify(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        if (publicKey.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            Ed25519Verify(publicKey).verify(signature, data)
            true
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    // ------------------------------------------------------------------ group / channel content

    /** A fresh random AES-256 key (used as a group key). */
    fun generateSymmetricKey(): ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) }

    /**
     * Associated data for content encrypted with a shared symmetric key (group or channel).
     * [scopeId] is the group or channel id, [epochId] the group-key generation ("" for channels).
     */
    fun contentAad(
        context: String,
        scopeId: String,
        epochId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        timestamp: Long,
        senderSigningKey: ByteArray
    ): ByteArray = encodeFields(
        CONTENT_AAD_LABEL.toByteArray(Charsets.UTF_8),
        context.toByteArray(Charsets.UTF_8),
        scopeId.toByteArray(Charsets.UTF_8),
        epochId.toByteArray(Charsets.UTF_8),
        messageId.toByteArray(Charsets.UTF_8),
        senderId.toByteArray(Charsets.UTF_8),
        messageType.toByteArray(Charsets.UTF_8),
        timestamp.toString().toByteArray(Charsets.UTF_8),
        senderSigningKey
    )

    /** The bytes a sender signs: the associated data together with the ciphertext. */
    fun signedData(aad: ByteArray, ciphertext: ByteArray): ByteArray =
        encodeFields(SIGNATURE_LABEL.toByteArray(Charsets.UTF_8), aad, ciphertext)

    /** Packs several strings (e.g. a notice title and body) into one plaintext. */
    fun encodeStrings(values: List<String>): ByteArray =
        encodeFields(*values.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())

    /** Reverses [encodeStrings]. Throws [GeneralSecurityException] on malformed input. */
    fun decodeStrings(bytes: ByteArray): List<String> {
        val values = mutableListOf<String>()
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (input.available() > 0) {
                    val length = input.readInt()
                    if (length < 0 || length > input.available()) {
                        throw GeneralSecurityException("Malformed payload")
                    }
                    val field = ByteArray(length)
                    input.readFully(field)
                    values += String(field, Charsets.UTF_8)
                }
            }
        } catch (e: IOException) {
            throw GeneralSecurityException("Malformed payload", e)
        }
        return values
    }

    // ------------------------------------------------------------------ attachments (images)

    class SealedAttachment(val key: ByteArray, val data: ByteArray)

    /** Encrypts a file with its own fresh random key. */
    fun encryptAttachment(plain: ByteArray): SealedAttachment {
        val key = generateSymmetricKey()
        return SealedAttachment(key, encrypt(key, plain, ATTACHMENT_AAD_LABEL.toByteArray(Charsets.UTF_8)))
    }

    /** Reverses [encryptAttachment]. Throws [GeneralSecurityException] if the file was altered. */
    fun decryptAttachment(key: ByteArray, data: ByteArray): ByteArray =
        decrypt(key, data, ATTACHMENT_AAD_LABEL.toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------------ password-derived channel keys

    class ChannelKeys(val encryptionKey: ByteArray, val verifier: ByteArray)

    fun generateSalt(): ByteArray = ByteArray(SALT_SIZE).also { random.nextBytes(it) }

    /**
     * Derives a private channel's AES key and password verifier from its password. Deliberately
     * slow (PBKDF2) so that guessing passwords from the stored salt/verifier is expensive.
     */
    fun deriveChannelKeys(
        channelId: String,
        password: String,
        salt: ByteArray,
        iterations: Int
    ): ChannelKeys {
        if (password.isEmpty()) throw GeneralSecurityException("Empty password")
        if (salt.size < SALT_SIZE) throw GeneralSecurityException("Salt too short")
        if (iterations < 1) throw GeneralSecurityException("Invalid iteration count")

        val master = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, iterations, KEY_SIZE)
        val channel = channelId.toByteArray(Charsets.UTF_8)
        val key = Hkdf.computeHkdf(
            "HMACSHA256", master, HKDF_SALT,
            encodeFields(CHANNEL_KEY_LABEL.toByteArray(Charsets.UTF_8), channel), KEY_SIZE
        )
        val verifier = Hkdf.computeHkdf(
            "HMACSHA256", master, HKDF_SALT,
            encodeFields(CHANNEL_VERIFIER_LABEL.toByteArray(Charsets.UTF_8), channel), KEY_SIZE
        )
        return ChannelKeys(key, verifier)
    }

    /** Constant-time comparison (does not leak how many leading bytes matched). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** PBKDF2 with HMAC-SHA256 (RFC 8018), written out because Android < 8 lacks it in the JCA. */
    internal fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = mac.macLength
        val blocks = (length + hashLength - 1) / hashLength
        val output = ByteArray(blocks * hashLength)
        val u = ByteArray(hashLength)
        val t = ByteArray(hashLength)

        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte()
                )
            )
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until hashLength) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(output, (block - 1) * hashLength)
        }
        return output.copyOf(length)
    }

    /** Short human-readable fingerprint of a public key, e.g. for logs or a verification screen. */
    fun fingerprint(publicKey: ByteArray): String =
        sha256(publicKey).take(8).joinToString(" ") { "%02X".format(it) }

    // Visible for tests so known-answer vectors can use a fixed nonce. Never call with a reused nonce.
    internal fun encryptWithNonce(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        requireKeySize(key, "message key")
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Unambiguous encoding: each field is written as a 4-byte big-endian length followed by its bytes. */
    internal fun encodeFields(vararg fields: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (field in fields) {
                out.writeInt(field.size)
                out.write(field)
            }
        }
        return bytes.toByteArray()
    }

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun requireKeySize(key: ByteArray, name: String) {
        if (key.size != KEY_SIZE) throw GeneralSecurityException("Invalid $name length: ${key.size}")
    }
}
