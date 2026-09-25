package com.example.clubmate.e2ee

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.util.MessageType
import com.example.clubmate.util.chat.Message
import com.example.clubmate.viewmodel.IncognitoMessage
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Glue between the app and [E2eeCrypto]: owns this device's key pairs, publishes the public keys to
 * `user/{uid}` (`publicKey` = X25519, `signingKey` = Ed25519), looks up contacts' public keys, and
 * seals/opens 1:1 chat messages. [GroupE2ee] and [ChannelE2ee] build on the helpers here.
 *
 * Encrypted 1:1 messages in Firebase carry `v = 1`, the Base64 ciphertext in `ct`, and the two public
 * keys used (`senderKey`, `receiverKey`). The readable fields `messageText` and `imageRef` are always
 * stored empty.
 */
object E2eeManager {

    private const val TAG = "E2EE"

    const val CONTEXT_CHAT = "chat"
    const val CONTEXT_INCOGNITO = "incognito"

    const val TEXT_UNDECRYPTABLE = "🔒 This message can't be decrypted on this device"
    const val TEXT_UNTRUSTED_KEY = "🔒 Message blocked: the sender's key isn't recognised"
    const val TEXT_UNSUPPORTED = "🔒 This message needs a newer version of ClubMate"
    const val TEXT_NO_GROUP_KEY = "🔒 Sent before you joined, or to an old key of yours"
    const val NOT_ENCRYPTED_PREFIX = "⚠️ Not encrypted: "

    class E2eeException(message: String) : Exception(message)

    class Sealed(val ciphertext: String, val senderKey: String, val receiverKey: String)

    /** Ciphertext sealed with a shared (group/channel) key and signed by the sender. */
    class SignedCiphertext(val ciphertext: String, val signKey: String, val signature: String)

    internal sealed class OpenedBytes {
        class Ok(val bytes: ByteArray) : OpenedBytes()
        class Failed(val reason: String) : OpenedBytes()
    }

    internal sealed class OpenedFields {
        class Ok(val fields: List<String>) : OpenedFields()
        class Failed(val reason: String) : OpenedFields()
    }

    /** The two public keys every user publishes, by their field name in `user/{uid}`. */
    internal enum class KeyKind(val field: String) { DH("publicKey"), SIGNING("signingKey") }

    internal class Identity(
        val uid: String,
        val dhPrivate: ByteArray,
        val dhPublic: ByteArray,
        val signPrivate: ByteArray,
        val signPublic: ByteArray
    ) {
        val dhPublicB64: String = encode(dhPublic)
        val signPublicB64: String = encode(signPublic)
    }

    private lateinit var vault: KeyVault
    private val userRef get() = FirebaseDatabase.getInstance().getReference("user")

    @Volatile
    private var identity: Identity? = null
    private var publishedUid: String? = null

    /** Latest public key the directory shows, keyed by "field|uid" (Base64). */
    private val currentPeerKeys = ConcurrentHashMap<String, String>()

    /** Derived pairwise AES keys, cached per (my key, contact, contact's key). */
    private val conversationKeys = ConcurrentHashMap<String, ByteArray>()

    /** "field|uid|key" triples already looked up in the directory without a match. */
    private val rejectedPeerKeys = ConcurrentHashMap.newKeySet<String>()

    private val peerWatchers = ConcurrentHashMap<String, ValueEventListener>()

    fun init(context: Context) {
        vault = KeyVault(context.applicationContext)
        SecureImages.init(context)
    }

    /** X25519 public key (Base64) of [uid] on this device; creates the key pairs if needed. */
    fun publicKeyFor(uid: String): String = identityFor(uid).dhPublicB64

    /** Ed25519 public key (Base64) of [uid] on this device; creates the key pairs if needed. */
    fun signingKeyFor(uid: String): String = identityFor(uid).signPublicB64

    /**
     * Makes sure this device has key pairs for [uid] and that the public halves are published.
     * Safe to call repeatedly; the upload only happens once per sign-in.
     */
    @Synchronized
    fun onSignedIn(uid: String) {
        val id = identityFor(uid)
        if (publishedUid == uid) return
        publishedUid = uid

        val update = mapOf<String, Any?>(
            KeyKind.DH.field to id.dhPublicB64,
            KeyKind.SIGNING.field to id.signPublicB64,
            // Remove the old password-encrypted private key: private keys must never be on the server.
            "encryptedPrivateKey" to null
        )
        userRef.child(uid).updateChildren(update)
            .addOnSuccessListener {
                Log.d(TAG, "Published public keys ${E2eeCrypto.fingerprint(id.dhPublic)}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to publish public keys", e)
                synchronized(this) { if (publishedUid == uid) publishedUid = null }
            }
    }

    @Synchronized
    fun onSignedOut() {
        identity = null
        publishedUid = null
        currentPeerKeys.clear()
        conversationKeys.clear()
        rejectedPeerKeys.clear()
        peerWatchers.forEach { (fieldAndUid, listener) ->
            val (field, peerUid) = fieldAndUid.split("|", limit = 2)
            userRef.child(peerUid).child(field).removeEventListener(listener)
        }
        peerWatchers.clear()
        GroupE2ee.onSignedOut()
        ChannelE2ee.onSignedOut()
    }

    /** Keeps a contact's current public keys up to date while chats/groups with them are in use. */
    fun watchPeer(peerUid: String) {
        if (peerUid.isEmpty()) return
        for (kind in KeyKind.values()) {
            val watchKey = "${kind.field}|$peerUid"
            if (peerWatchers.containsKey(watchKey)) continue
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val keyB64 = snapshot.value as? String
                    if (keyB64 != null && decodeKey(keyB64) != null) {
                        rememberPeerKey(peerUid, keyB64, kind)
                    } else {
                        currentPeerKeys.remove(watchKey)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Stopped watching ${kind.field} of $peerUid: ${error.message}")
                }
            }
            if (peerWatchers.putIfAbsent(watchKey, listener) == null) {
                userRef.child(peerUid).child(kind.field).addValueEventListener(listener)
            }
        }
    }

    // ---------------------------------------------------------------- 1:1 chat messages

    /** Returns a copy of [message] that is safe to store in Firebase (no readable content). */
    suspend fun sealMessage(chatId: String, message: Message): Message {
        val isImage = message.messageType == MessageType.Image
        val sealed = sealPairwise(
            context = CONTEXT_CHAT,
            scopeId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = message.messageType.name,
            timestamp = message.timestamp,
            content = (if (isImage) message.imageRef else message.messageText).toByteArray(Charsets.UTF_8)
        )
        return message.copy(
            messageText = "",
            imageRef = "",
            v = E2eeCrypto.VERSION,
            ct = sealed.ciphertext,
            senderKey = sealed.senderKey,
            receiverKey = sealed.receiverKey
        )
    }

    /** Returns a copy of [message] with its content decrypted (or a placeholder) for display. */
    suspend fun openMessage(chatId: String, message: Message, myUid: String): Message {
        if (message.v == 0) {
            return if (message.messageText.isNotEmpty()) {
                message.copy(messageText = NOT_ENCRYPTED_PREFIX + message.messageText)
            } else message
        }
        if (message.v != E2eeCrypto.VERSION) {
            return message.copy(messageText = TEXT_UNSUPPORTED, imageRef = "")
        }

        val opened = openPairwise(
            context = CONTEXT_CHAT,
            scopeId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = message.messageType.name,
            timestamp = message.timestamp,
            senderKeyB64 = message.senderKey,
            receiverKeyB64 = message.receiverKey,
            ciphertextB64 = message.ct,
            myUid = myUid
        )
        return when (opened) {
            is OpenedBytes.Ok -> {
                val text = String(opened.bytes, Charsets.UTF_8)
                if (message.messageType == MessageType.Image) {
                    message.copy(imageRef = text, messageText = "")
                } else {
                    message.copy(messageText = text, imageRef = "")
                }
            }

            is OpenedBytes.Failed -> message.copy(messageText = opened.reason, imageRef = "")
        }
    }

    suspend fun sealIncognito(chatId: String, message: IncognitoMessage): IncognitoMessage {
        val sealed = sealPairwise(
            context = CONTEXT_INCOGNITO,
            scopeId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = MessageType.Text.name,
            timestamp = message.timestamp,
            content = message.messageText.toByteArray(Charsets.UTF_8)
        )
        return message.copy(
            messageText = "",
            v = E2eeCrypto.VERSION,
            ct = sealed.ciphertext,
            senderKey = sealed.senderKey,
            receiverKey = sealed.receiverKey
        )
    }

    suspend fun openIncognito(chatId: String, message: IncognitoMessage, myUid: String): IncognitoMessage {
        if (message.v == 0) {
            return message.copy(messageText = NOT_ENCRYPTED_PREFIX + message.messageText)
        }
        if (message.v != E2eeCrypto.VERSION) return message.copy(messageText = TEXT_UNSUPPORTED)

        val opened = openPairwise(
            context = CONTEXT_INCOGNITO,
            scopeId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = MessageType.Text.name,
            timestamp = message.timestamp,
            senderKeyB64 = message.senderKey,
            receiverKeyB64 = message.receiverKey,
            ciphertextB64 = message.ct,
            myUid = myUid
        )
        return when (opened) {
            is OpenedBytes.Ok -> message.copy(messageText = String(opened.bytes, Charsets.UTF_8))
            is OpenedBytes.Failed -> message.copy(messageText = opened.reason)
        }
    }

    // ---------------------------------------------------------------- pairwise (DH) sealing

    /**
     * Encrypts [content] from [senderId] to [receiverId] with their shared Diffie-Hellman key.
     * The receiver's key is taken from the directory unless [receiverKeyB64] is given.
     */
    internal suspend fun sealPairwise(
        context: String,
        scopeId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        content: ByteArray,
        receiverKeyB64: String? = null
    ): Sealed {
        val me = requireIdentity(senderId)

        // Ask the directory first so a contact's new key is picked up immediately.
        val peerKeyB64 = receiverKeyB64
            ?: (if (receiverId == senderId) me.dhPublicB64
            else fetchPeerKey(receiverId, KeyKind.DH) ?: currentPeerKeys["${KeyKind.DH.field}|$receiverId"])
            ?: throw E2eeException(
                "Can't send securely yet: the other user needs to sign in to the latest ClubMate first"
            )
        val peerKey = decodeKey(peerKeyB64)
            ?: throw E2eeException("The other user's encryption key is invalid")

        return try {
            val aad = E2eeCrypto.messageAad(
                context, scopeId, messageId, senderId, receiverId, messageType, timestamp,
                me.dhPublic, peerKey
            )
            val ciphertext = E2eeCrypto.encrypt(
                conversationKey(me, receiverId, peerKey, peerKeyB64), content, aad
            )
            Sealed(encode(ciphertext), me.dhPublicB64, peerKeyB64)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            throw E2eeException("Message could not be encrypted")
        }
    }

    /** Reverses [sealPairwise] for either participant, after checking both keys are legitimate. */
    internal suspend fun openPairwise(
        context: String,
        scopeId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        senderKeyB64: String,
        receiverKeyB64: String,
        ciphertextB64: String,
        myUid: String
    ): OpenedBytes {
        val me = identityFor(myUid)
        val iAmSender = senderId == myUid
        if (!iAmSender && receiverId != myUid) return OpenedBytes.Failed(TEXT_UNDECRYPTABLE)

        val peerUid = if (iAmSender) receiverId else senderId
        val myKeyInMessage = if (iAmSender) senderKeyB64 else receiverKeyB64
        val peerKeyInMessage = if (iAmSender) receiverKeyB64 else senderKeyB64

        // Encrypted for a key pair this device doesn't have (e.g. the app was reinstalled).
        if (myKeyInMessage != me.dhPublicB64) return OpenedBytes.Failed(TEXT_UNDECRYPTABLE)

        val peerKey = decodeKey(peerKeyInMessage) ?: return OpenedBytes.Failed(TEXT_UNTRUSTED_KEY)
        if (!isTrustedPeerKey(me, peerUid, peerKeyInMessage, KeyKind.DH)) {
            Log.w(TAG, "Rejected $messageId: unknown key for $peerUid")
            return OpenedBytes.Failed(TEXT_UNTRUSTED_KEY)
        }

        return try {
            val aad = E2eeCrypto.messageAad(
                context, scopeId, messageId, senderId, receiverId, messageType, timestamp,
                if (iAmSender) me.dhPublic else peerKey,
                if (iAmSender) peerKey else me.dhPublic
            )
            OpenedBytes.Ok(
                E2eeCrypto.decrypt(
                    conversationKey(me, peerUid, peerKey, peerKeyInMessage), decode(ciphertextB64), aad
                )
            )
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Rejected $messageId: authentication failed")
            OpenedBytes.Failed(TEXT_UNDECRYPTABLE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected $messageId: malformed ciphertext")
            OpenedBytes.Failed(TEXT_UNDECRYPTABLE)
        }
    }

    // ---------------------------------------------------------------- shared-key (group/channel) sealing

    /** Encrypts [fields] with a shared [key] and signs the result with the sender's Ed25519 key. */
    internal suspend fun sealSigned(
        key: ByteArray,
        context: String,
        scopeId: String,
        epochId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        timestamp: Long,
        fields: List<String>
    ): SignedCiphertext {
        val me = requireIdentity(senderId)
        return try {
            val aad = E2eeCrypto.contentAad(
                context, scopeId, epochId, messageId, senderId, messageType, timestamp, me.signPublic
            )
            val ciphertext = E2eeCrypto.encrypt(key, E2eeCrypto.encodeStrings(fields), aad)
            val signature = E2eeCrypto.sign(me.signPrivate, E2eeCrypto.signedData(aad, ciphertext))
            SignedCiphertext(encode(ciphertext), me.signPublicB64, encode(signature))
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            throw E2eeException("Message could not be encrypted")
        }
    }

    /**
     * Reverses [sealSigned]: checks the signing key belongs to [senderId], verifies the signature,
     * then decrypts. Fails unless exactly [fieldCount] fields come out.
     */
    internal suspend fun openSigned(
        key: ByteArray,
        context: String,
        scopeId: String,
        epochId: String,
        messageId: String,
        senderId: String,
        messageType: String,
        timestamp: Long,
        ciphertextB64: String,
        signKeyB64: String,
        signatureB64: String,
        myUid: String,
        fieldCount: Int
    ): OpenedFields {
        val me = identityFor(myUid)
        val signKey = decodeKey(signKeyB64) ?: return OpenedFields.Failed(TEXT_UNTRUSTED_KEY)
        if (!isTrustedPeerKey(me, senderId, signKeyB64, KeyKind.SIGNING)) {
            Log.w(TAG, "Rejected $messageId: unknown signing key for $senderId")
            return OpenedFields.Failed(TEXT_UNTRUSTED_KEY)
        }

        return try {
            val ciphertext = decode(ciphertextB64)
            val aad = E2eeCrypto.contentAad(
                context, scopeId, epochId, messageId, senderId, messageType, timestamp, signKey
            )
            if (!E2eeCrypto.verify(signKey, decode(signatureB64), E2eeCrypto.signedData(aad, ciphertext))) {
                Log.w(TAG, "Rejected $messageId: bad signature")
                return OpenedFields.Failed(TEXT_UNDECRYPTABLE)
            }
            val fields = E2eeCrypto.decodeStrings(E2eeCrypto.decrypt(key, ciphertext, aad))
            if (fields.size != fieldCount) OpenedFields.Failed(TEXT_UNDECRYPTABLE) else OpenedFields.Ok(fields)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Rejected $messageId: authentication failed")
            OpenedFields.Failed(TEXT_UNDECRYPTABLE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected $messageId: malformed ciphertext")
            OpenedFields.Failed(TEXT_UNDECRYPTABLE)
        }
    }

    // ---------------------------------------------------------------- keys

    /** This device's identity for [uid], making sure its public keys are published. */
    internal fun requireIdentity(uid: String): Identity {
        onSignedIn(uid)
        return identityFor(uid)
    }

    @Synchronized
    internal fun identityFor(uid: String): Identity {
        identity?.let { if (it.uid == uid) return it }

        val dhPrivate = vault.loadPrivateKey(uid)
            ?: E2eeCrypto.generatePrivateKey().also {
                vault.savePrivateKey(uid, it)
                Log.i(TAG, "Generated a new key-exchange key pair for this device")
            }
        val signPrivate = vault.loadSigningKey(uid)
            ?: E2eeCrypto.generateSigningKeyPair().privateKey.also {
                vault.saveSigningKey(uid, it)
                Log.i(TAG, "Generated a new signing key pair for this device")
            }

        conversationKeys.clear()
        return Identity(
            uid, dhPrivate, E2eeCrypto.publicKeyOf(dhPrivate),
            signPrivate, E2eeCrypto.signingPublicKeyOf(signPrivate)
        ).also { identity = it }
    }

    /**
     * A contact's public key, from the watched cache if available. With [fresh] the directory is
     * asked first. Returns null if the contact has not published a valid key.
     */
    internal suspend fun peerKey(peerUid: String, kind: KeyKind, fresh: Boolean): String? {
        val cached = currentPeerKeys["${kind.field}|$peerUid"]
        return if (fresh || cached == null) fetchPeerKey(peerUid, kind) ?: cached else cached
    }

    /**
     * A contact's key is trusted if the directory (`user/{uid}`) has shown it for that contact, now
     * or in the past. Keys that only appear inside a message are never trusted.
     */
    private suspend fun isTrustedPeerKey(
        me: Identity,
        peerUid: String,
        keyB64: String,
        kind: KeyKind
    ): Boolean {
        if (peerUid == me.uid) {
            return keyB64 == (if (kind == KeyKind.DH) me.dhPublicB64 else me.signPublicB64)
        }
        if (currentPeerKeys["${kind.field}|$peerUid"] == keyB64 ||
            vault.isKnownPeerKey(peerUid, keyB64, kind.field)
        ) return true

        val lookup = "${kind.field}|$peerUid|$keyB64"
        if (lookup in rejectedPeerKeys) return false
        // The contact may have changed keys since we last looked: ask the directory once.
        if (fetchPeerKey(peerUid, kind) == keyB64) return true
        rejectedPeerKeys.add(lookup)
        return false
    }

    private suspend fun fetchPeerKey(peerUid: String, kind: KeyKind): String? = suspendCoroutine { cont ->
        userRef.child(peerUid).child(kind.field).get()
            .addOnSuccessListener { snapshot ->
                val keyB64 = snapshot.value as? String
                if (keyB64 != null && decodeKey(keyB64) != null) {
                    rememberPeerKey(peerUid, keyB64, kind)
                    cont.resume(keyB64)
                } else {
                    // Missing, or still the old RSA key from before E2EE was enabled.
                    cont.resume(null)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not fetch ${kind.field} of $peerUid: ${e.message}")
                cont.resume(null)
            }
    }

    private fun rememberPeerKey(peerUid: String, keyB64: String, kind: KeyKind) {
        val previous = currentPeerKeys.put("${kind.field}|$peerUid", keyB64)
        if (previous != null && previous != keyB64) {
            Log.i(TAG, "${kind.field} of $peerUid changed")
        }
        rejectedPeerKeys.remove("${kind.field}|$peerUid|$keyB64")
        vault.addKnownPeerKey(peerUid, keyB64, kind.field)
    }

    private fun conversationKey(
        me: Identity,
        peerUid: String,
        peerKey: ByteArray,
        peerKeyB64: String
    ): ByteArray = conversationKeys.getOrPut("${me.dhPublicB64}|$peerUid|$peerKeyB64") {
        E2eeCrypto.conversationKey(me.uid, me.dhPrivate, peerUid, peerKey)
    }

    private fun decodeKey(keyB64: String): ByteArray? = try {
        decode(keyB64).takeIf { it.size == E2eeCrypto.KEY_SIZE }
    } catch (e: IllegalArgumentException) {
        null
    }

    internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    internal fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
