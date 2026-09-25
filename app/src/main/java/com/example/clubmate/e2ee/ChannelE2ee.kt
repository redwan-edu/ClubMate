package com.example.clubmate.e2ee

import android.util.Log
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.e2ee.E2eeManager.OpenedFields
import com.example.clubmate.viewmodel.ChannelMap
import com.example.clubmate.viewmodel.VanishingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end encryption for password-protected private channels.
 *
 * The channel password is the shared secret. Its key is derived on the device with
 * PBKDF2-HMAC-SHA256 (random 16-byte salt, [E2eeCrypto.CHANNEL_ITERATIONS] iterations) followed by
 * HKDF. Firebase only stores:
 *
 * ```
 * private_channels/{channelId} = { createdAt, v: 1, salt, iterations, verifier }
 * ```
 *
 * The verifier is derived from the same secret as the key (but is not the key), so the app can
 * tell whether a password is right without the password ever being stored. Messages are
 * AES-256-GCM encrypted with the channel key and signed with the sender's Ed25519 key.
 *
 * Channels created before encryption stored the password in plain text (`setPassword`); the first
 * member to join with the right password upgrades such a channel and deletes the stored password.
 */
object ChannelE2ee {

    private const val TAG = "ChannelE2EE"
    private const val CONTEXT_CHANNEL = "channel"

    const val MIN_PASSWORD_LENGTH = 8
    const val TYPE_TEXT = "Text"
    const val TYPE_IMAGE = "Image"

    // Bounds on the stored iteration count, so a tampered channel can't make the app hang.
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000

    class NewChannel(val fields: Map<String, Any>, val key: ByteArray)

    sealed class Unlock {
        /** [upgrade] is non-null for a legacy channel: write it to the channel node. */
        class Ok(val key: ByteArray, val upgrade: Map<String, Any?>?) : Unlock()
        object WrongPassword : Unlock()
    }

    // "channelId|salt|iterations|sha256(password)" -> derived keys (PBKDF2 is deliberately slow)
    private val derived = ConcurrentHashMap<String, E2eeCrypto.ChannelKeys>()

    internal fun onSignedOut() = derived.clear()

    /** Creates the key material for a new channel. Runs off the main thread. */
    suspend fun create(channelId: String, password: String): NewChannel = withContext(Dispatchers.Default) {
        val salt = E2eeCrypto.generateSalt()
        val iterations = E2eeCrypto.CHANNEL_ITERATIONS
        val keys = derive(channelId, password, salt, iterations)
        NewChannel(
            fields = mapOf(
                "v" to E2eeCrypto.VERSION,
                "salt" to E2eeManager.encode(salt),
                "iterations" to iterations,
                "verifier" to E2eeManager.encode(keys.verifier)
            ),
            key = keys.encryptionKey
        )
    }

    /** Checks [password] against [channel] and returns the channel key. Runs off the main thread. */
    suspend fun unlock(channelId: String, channel: ChannelMap, password: String): Unlock =
        withContext(Dispatchers.Default) {
            if (password.isEmpty()) return@withContext Unlock.WrongPassword

            if (channel.v >= E2eeCrypto.VERSION && channel.salt.isNotEmpty()) {
                if (channel.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                    Log.w(TAG, "Channel $channelId has an invalid iteration count")
                    return@withContext Unlock.WrongPassword
                }
                try {
                    val keys = derive(channelId, password, E2eeManager.decode(channel.salt), channel.iterations)
                    if (E2eeCrypto.constantTimeEquals(keys.verifier, E2eeManager.decode(channel.verifier))) {
                        Unlock.Ok(keys.encryptionKey, null)
                    } else Unlock.WrongPassword
                } catch (e: IllegalArgumentException) {
                    Unlock.WrongPassword
                } catch (e: GeneralSecurityException) {
                    Unlock.WrongPassword
                }
            } else {
                // Legacy channel with a plain-text password: check it, then upgrade the channel.
                if (channel.setPassword.isEmpty() || channel.setPassword != password) {
                    Unlock.WrongPassword
                } else {
                    val fresh = create(channelId, password)
                    Unlock.Ok(fresh.key, fresh.fields + ("setPassword" to null))
                }
            }
        }

    suspend fun sealMessage(channelId: String, key: ByteArray, message: VanishingMessage): VanishingMessage {
        val isImage = message.imageUrl.isNotEmpty()
        val kind = if (isImage) TYPE_IMAGE else TYPE_TEXT
        val sealed = E2eeManager.sealSigned(
            key = key,
            context = CONTEXT_CHANNEL,
            scopeId = channelId,
            epochId = "",
            messageId = message.messageId,
            senderId = message.senderId,
            messageType = kind,
            timestamp = message.timestampSent,
            fields = listOf(if (isImage) message.imageUrl else message.messageText)
        )
        return message.copy(
            messageText = "",
            imageUrl = "",
            v = E2eeCrypto.VERSION,
            kind = kind,
            ct = sealed.ciphertext,
            signKey = sealed.signKey,
            sig = sealed.signature
        )
    }

    suspend fun openMessage(
        channelId: String,
        key: ByteArray,
        message: VanishingMessage,
        myUid: String
    ): VanishingMessage {
        if (message.v == 0) {
            return if (message.messageText.isNotEmpty()) {
                message.copy(messageText = E2eeManager.NOT_ENCRYPTED_PREFIX + message.messageText)
            } else message
        }
        if (message.v != E2eeCrypto.VERSION) {
            return message.copy(messageText = E2eeManager.TEXT_UNSUPPORTED, imageUrl = "")
        }

        val opened = E2eeManager.openSigned(
            key = key,
            context = CONTEXT_CHANNEL,
            scopeId = channelId,
            epochId = "",
            messageId = message.messageId,
            senderId = message.senderId,
            messageType = message.kind,
            timestamp = message.timestampSent,
            ciphertextB64 = message.ct,
            signKeyB64 = message.signKey,
            signatureB64 = message.sig,
            myUid = myUid,
            fieldCount = 1
        )
        return when (opened) {
            is OpenedFields.Ok -> if (message.kind == TYPE_IMAGE) {
                message.copy(imageUrl = opened.fields[0], messageText = "")
            } else {
                message.copy(messageText = opened.fields[0], imageUrl = "")
            }

            is OpenedFields.Failed -> message.copy(messageText = opened.reason, imageUrl = "")
        }
    }

    private fun derive(
        channelId: String,
        password: String,
        salt: ByteArray,
        iterations: Int
    ): E2eeCrypto.ChannelKeys {
        val passwordHash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val cacheKey = "$channelId|${E2eeManager.encode(salt)}|$iterations|${E2eeManager.encode(passwordHash)}"
        return derived.getOrPut(cacheKey) {
            E2eeCrypto.deriveChannelKeys(channelId, password, salt, iterations)
        }
    }
}
