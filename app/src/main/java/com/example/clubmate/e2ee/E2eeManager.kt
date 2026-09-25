package com.example.clubmate.e2ee

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.crypto.RatchetHeader
import com.example.clubmate.crypto.X3dh
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
 * Glue between the app and the crypto code: owns this device's key pairs, publishes the public keys
 * to `user/{uid}` (`publicKey` = X25519 identity key, `signingKey` = Ed25519, `signedPreKey` = X3DH
 * prekey signed with the Ed25519 key), looks up contacts' keys, and seals/opens 1:1 chat messages.
 * [GroupE2ee] and [ChannelE2ee] build on the helpers here.
 *
 * 1:1 messages (`v = 2`) use X3DH + the Double Ratchet via [RatchetSessions]: `ct` is the
 * ciphertext, `dh`/`pn`/`n` the ratchet header and `preIk`/`preEk`/`preSpk` the X3DH prekey block of
 * a session's first messages. Messages from the earlier static-key version (`v = 1`) and plain-text
 * messages (`v = 0`) can still be read. The readable fields `messageText` and `imageRef` are always
 * stored empty.
 */
object E2eeManager {

    private const val TAG = "E2EE"

    const val CONTEXT_CHAT = "chat"
    const val CONTEXT_INCOGNITO = "incognito"

    /** Protocol version of 1:1 messages sent by this app (Double Ratchet). */
    const val RATCHET_VERSION = 2

    private const val SIGNED_PREKEY_FIELD = "signedPreKey"
    private const val SIGNED_PREKEY_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    private const val SIGNED_PREKEYS_KEPT = 4

    const val TEXT_UNDECRYPTABLE = "🔒 This message can't be decrypted on this device"
    const val TEXT_UNTRUSTED_KEY = "🔒 Message blocked: the sender's key isn't recognised"
    const val TEXT_UNSUPPORTED = "🔒 This message needs a newer version of ClubMate"
    const val TEXT_NO_GROUP_KEY = "🔒 Sent before you joined, or to an old key of yours"
    const val TEXT_NOT_ON_DEVICE = "🔒 This message is no longer available on this device"
    const val TEXT_PEER_NEEDS_UPDATE =
        "Can't send securely yet: the other user needs to sign in to the latest ClubMate first"
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
        RatchetSessions.init(FileStoreBackend(context.applicationContext)) { vault.storageKey() }
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
            SIGNED_PREKEY_FIELD to signedPreKey(id),
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
        RatchetSessions.onSignedOut()
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
        val envelope = sealRatchet(
            context = CONTEXT_CHAT,
            chatId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = message.messageType.name,
            timestamp = message.timestamp,
            content = if (isImage) message.imageRef else message.messageText,
            persist = true
        )
        return message.copy(
            messageText = "",
            imageRef = "",
            v = RATCHET_VERSION,
            ct = encode(envelope.ciphertext),
            senderKey = "",
            receiverKey = "",
            dh = encode(envelope.header.dh),
            pn = envelope.header.previousChainLength,
            n = envelope.header.messageNumber,
            preIk = envelope.preKey?.identityKey?.let { encode(it) } ?: "",
            preEk = envelope.preKey?.ephemeralKey?.let { encode(it) } ?: "",
            preSpk = envelope.preKey?.preKeyId ?: 0
        )
    }

    /** Returns a copy of [message] with its content decrypted (or a placeholder) for display. */
    suspend fun openMessage(chatId: String, message: Message, myUid: String): Message {
        val opened = when (message.v) {
            0 -> return if (message.messageText.isNotEmpty()) {
                message.copy(messageText = NOT_ENCRYPTED_PREFIX + message.messageText)
            } else message

            1 -> openPairwise( // messages from the earlier static Diffie-Hellman version
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

            RATCHET_VERSION -> openRatchet(
                context = CONTEXT_CHAT,
                chatId = chatId,
                messageId = message.messageId,
                senderId = message.senderId,
                receiverId = message.receiverId,
                messageType = message.messageType.name,
                timestamp = message.timestamp,
                dh = message.dh, pn = message.pn, n = message.n, ct = message.ct,
                preIk = message.preIk, preEk = message.preEk, preSpk = message.preSpk,
                myUid = myUid,
                persist = true
            )

            else -> return message.copy(messageText = TEXT_UNSUPPORTED, imageRef = "")
        }
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

    /** Incognito messages use the same ratchet session, but their text is never saved to disk. */
    suspend fun sealIncognito(chatId: String, message: IncognitoMessage): IncognitoMessage {
        val envelope = sealRatchet(
            context = CONTEXT_INCOGNITO,
            chatId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = MessageType.Text.name,
            timestamp = message.timestamp,
            content = message.messageText,
            persist = false
        )
        return message.copy(
            messageText = "",
            v = RATCHET_VERSION,
            ct = encode(envelope.ciphertext),
            senderKey = "",
            receiverKey = "",
            dh = encode(envelope.header.dh),
            pn = envelope.header.previousChainLength,
            n = envelope.header.messageNumber,
            preIk = envelope.preKey?.identityKey?.let { encode(it) } ?: "",
            preEk = envelope.preKey?.ephemeralKey?.let { encode(it) } ?: "",
            preSpk = envelope.preKey?.preKeyId ?: 0
        )
    }

    suspend fun openIncognito(chatId: String, message: IncognitoMessage, myUid: String): IncognitoMessage {
        val opened = when (message.v) {
            0 -> return message.copy(messageText = NOT_ENCRYPTED_PREFIX + message.messageText)
            1 -> openPairwise(
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

            RATCHET_VERSION -> openRatchet(
                context = CONTEXT_INCOGNITO,
                chatId = chatId,
                messageId = message.messageId,
                senderId = message.senderId,
                receiverId = message.receiverId,
                messageType = MessageType.Text.name,
                timestamp = message.timestamp,
                dh = message.dh, pn = message.pn, n = message.n, ct = message.ct,
                preIk = message.preIk, preEk = message.preEk, preSpk = message.preSpk,
                myUid = myUid,
                persist = false
            )

            else -> return message.copy(messageText = TEXT_UNSUPPORTED)
        }
        return when (opened) {
            is OpenedBytes.Ok -> message.copy(messageText = String(opened.bytes, Charsets.UTF_8))
            is OpenedBytes.Failed -> message.copy(messageText = opened.reason)
        }
    }

    /** Call when a message was deleted, so its decrypted text is removed from this device too. */
    suspend fun forgetMessage(chatId: String, myUid: String, messageId: String) =
        RatchetSessions.forget(
            chatId, myUid, listOf(storeId(CONTEXT_CHAT, messageId), storeId(CONTEXT_INCOGNITO, messageId))
        )

    /** Call when a whole chat was deleted. */
    suspend fun forgetChat(chatId: String, myUid: String) = RatchetSessions.forgetChat(chatId, myUid)

    // ---------------------------------------------------------------- Double Ratchet (1:1)

    private suspend fun sealRatchet(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        content: String,
        persist: Boolean
    ): RatchetSessions.Envelope {
        val me = requireIdentity(senderId)
        return RatchetSessions.encrypt(
            chatId, me, receiverId, storeId(context, messageId), content,
            ratchetAad(context, chatId, messageId, senderId, receiverId, messageType, timestamp),
            persist
        )
    }

    private suspend fun openRatchet(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        dh: String,
        pn: Int,
        n: Int,
        ct: String,
        preIk: String,
        preEk: String,
        preSpk: Int,
        myUid: String,
        persist: Boolean
    ): OpenedBytes {
        val aad = ratchetAad(context, chatId, messageId, senderId, receiverId, messageType, timestamp)
        // Senders can't decrypt their own ratchet messages: the text was kept when it was sent.
        if (senderId == myUid) {
            return RatchetSessions.sentText(chatId, myUid, storeId(context, messageId), aad)
                ?.let { OpenedBytes.Ok(it.toByteArray(Charsets.UTF_8)) }
                ?: OpenedBytes.Failed(TEXT_NOT_ON_DEVICE)
        }
        if (receiverId != myUid) return OpenedBytes.Failed(TEXT_UNDECRYPTABLE)

        val envelope = try {
            RatchetSessions.Envelope(
                header = RatchetHeader(decode(dh), pn, n),
                ciphertext = decode(ct),
                preKey = if (preEk.isNotEmpty()) PreKeyInfo(decode(preIk), decode(preEk), preSpk) else null
            )
        } catch (e: IllegalArgumentException) {
            return OpenedBytes.Failed(TEXT_UNDECRYPTABLE)
        }
        return RatchetSessions.decrypt(
            chatId, identityFor(myUid), senderId, storeId(context, messageId), envelope, aad, persist
        )
    }

    // Stored text is kept per context, so an incognito message can't reappear as a normal one.
    private fun storeId(context: String, messageId: String) = "$context/$messageId"

    // Message metadata bound to each ratchet ciphertext (identities are bound by the session itself).
    private fun ratchetAad(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long
    ): ByteArray = E2eeCrypto.messageAad(
        context, chatId, messageId, senderId, receiverId, messageType, timestamp, ByteArray(0), ByteArray(0)
    )

    /** A contact's signed prekey (id, key), only if its signature by their signing key checks out. */
    internal suspend fun fetchPreKeyBundle(peerUid: String): Pair<Int, ByteArray>? {
        val raw = fetchNode(userRef.child(peerUid).child(SIGNED_PREKEY_FIELD)) as? Map<*, *> ?: return null
        val id = (raw["id"] as? Number)?.toInt() ?: return null
        val key = (raw["key"] as? String)?.let { decodeKey(it) } ?: return null
        val signature = (raw["sig"] as? String)?.let {
            try {
                decode(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: return null
        val signingKey = peerKey(peerUid, KeyKind.SIGNING, fresh = true)?.let { decode(it) } ?: return null
        if (!E2eeCrypto.verify(signingKey, signature, X3dh.signedPreKeyData(peerUid, id, key))) {
            Log.w(TAG, "Signed prekey of $peerUid has an invalid signature")
            return null
        }
        return id to key
    }

    internal fun signedPreKeyPrivate(uid: String, id: Int): ByteArray? = vault.loadSignedPreKey(uid, id)

    // The current signed prekey (rotated weekly; a few old ones are kept for late first messages).
    private fun signedPreKey(me: Identity): Map<String, Any> {
        val now = System.currentTimeMillis()
        var current = vault.currentSignedPreKey(me.uid)
        var privateKey = current?.let { vault.loadSignedPreKey(me.uid, it.first) }
        if (current == null || privateKey == null || now - current.second > SIGNED_PREKEY_LIFETIME_MS) {
            val id = (current?.first ?: 0) + 1
            privateKey = E2eeCrypto.generatePrivateKey()
            vault.saveSignedPreKey(me.uid, id, privateKey, now, SIGNED_PREKEYS_KEPT)
            current = id to now
            Log.i(TAG, "New signed prekey #$id")
        }
        val publicKey = E2eeCrypto.publicKeyOf(privateKey)
        val signature = E2eeCrypto.sign(me.signPrivate, X3dh.signedPreKeyData(me.uid, current.first, publicKey))
        return mapOf("id" to current.first, "key" to encode(publicKey), "sig" to encode(signature))
    }

    private suspend fun fetchNode(ref: com.google.firebase.database.DatabaseReference): Any? =
        suspendCoroutine { cont ->
            ref.get()
                .addOnSuccessListener { cont.resume(it.value) }
                .addOnFailureListener {
                    Log.w(TAG, "Read failed: ${it.message}")
                    cont.resume(null)
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
            ?: throw E2eeException(TEXT_PEER_NEEDS_UPDATE)
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
    internal suspend fun isTrustedPeerKey(
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
