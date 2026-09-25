package com.example.clubmate.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption primitives for 1:1 chats (basic Diffie-Hellman mode).
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
    private fun encodeFields(vararg fields: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (field in fields) {
                out.writeInt(field.size)
                out.write(field)
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun requireKeySize(key: ByteArray, name: String) {
        if (key.size != KEY_SIZE) throw GeneralSecurityException("Invalid $name length: ${key.size}")
    }
}
