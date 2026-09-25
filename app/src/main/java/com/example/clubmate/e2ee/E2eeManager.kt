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
 * Glue between the app and [E2eeCrypto]: owns this device's key pair, publishes the public key to
 * `user/{uid}/publicKey`, looks up contacts' public keys, and seals/opens chat messages.
 *
 * Encrypted messages in Firebase carry `v = 1`, the Base64 ciphertext in `ct`, and the two public
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
    const val NOT_ENCRYPTED_PREFIX = "⚠️ Not encrypted: "

    class E2eeException(message: String) : Exception(message)

    class Sealed(val ciphertext: String, val senderKey: String, val receiverKey: String)

    sealed class Opened {
        class Plain(val text: String) : Opened()
        class Failed(val reason: String) : Opened()
    }

    private class Identity(val uid: String, val privateKey: ByteArray, val publicKey: ByteArray) {
        val publicKeyB64: String = encode(publicKey)
    }

    private lateinit var vault: KeyVault
    private val userRef get() = FirebaseDatabase.getInstance().getReference("user")

    @Volatile
    private var identity: Identity? = null
    private var publishedUid: String? = null

    /** Latest public key the directory shows for each contact (Base64). */
    private val currentPeerKeys = ConcurrentHashMap<String, String>()

    /** Derived AES keys, cached per (my key, contact, contact's key). */
    private val conversationKeys = ConcurrentHashMap<String, ByteArray>()

    /** (contact|key) pairs already looked up in the directory without a match. */
    private val rejectedPeerKeys = ConcurrentHashMap.newKeySet<String>()

    private val peerWatchers = ConcurrentHashMap<String, ValueEventListener>()

    fun init(context: Context) {
        vault = KeyVault(context.applicationContext)
    }

    /** Public key (Base64) of [uid] on this device; creates the key pair if needed. */
    fun publicKeyFor(uid: String): String = identityFor(uid).publicKeyB64

    /**
     * Makes sure this device has a key pair for [uid] and that its public half is published.
     * Safe to call repeatedly; the upload only happens once per sign-in.
     */
    @Synchronized
    fun onSignedIn(uid: String) {
        val id = identityFor(uid)
        if (publishedUid == uid) return
        publishedUid = uid

        val update = mapOf<String, Any?>(
            "publicKey" to id.publicKeyB64,
            // Remove the old password-encrypted private key: private keys must never be on the server.
            "encryptedPrivateKey" to null
        )
        userRef.child(uid).updateChildren(update)
            .addOnSuccessListener {
                Log.d(TAG, "Published public key ${E2eeCrypto.fingerprint(id.publicKey)}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to publish public key", e)
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
        peerWatchers.forEach { (peerUid, listener) ->
            userRef.child(peerUid).child("publicKey").removeEventListener(listener)
        }
        peerWatchers.clear()
    }

    /** Keeps the contact's current public key up to date while a chat with them is in use. */
    fun watchPeer(peerUid: String) {
        if (peerUid.isEmpty() || peerWatchers.containsKey(peerUid)) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val keyB64 = snapshot.value as? String
                if (keyB64 != null && decodeKey(keyB64) != null) {
                    rememberPeerKey(peerUid, keyB64)
                } else {
                    currentPeerKeys.remove(peerUid)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Stopped watching key of $peerUid: ${error.message}")
            }
        }
        if (peerWatchers.putIfAbsent(peerUid, listener) == null) {
            userRef.child(peerUid).child("publicKey").addValueEventListener(listener)
        }
    }

    // ---------------------------------------------------------------- chat messages

    /** Returns a copy of [message] that is safe to store in Firebase (no readable content). */
    suspend fun sealMessage(chatId: String, message: Message): Message {
        val isImage = message.messageType == MessageType.Image
        val sealed = seal(
            context = CONTEXT_CHAT,
            chatId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = message.messageType.name,
            timestamp = message.timestamp,
            content = if (isImage) message.imageRef else message.messageText
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

        val opened = open(
            context = CONTEXT_CHAT,
            chatId = chatId,
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
            is Opened.Plain -> if (message.messageType == MessageType.Image) {
                message.copy(imageRef = opened.text, messageText = "")
            } else {
                message.copy(messageText = opened.text, imageRef = "")
            }

            is Opened.Failed -> message.copy(messageText = opened.reason, imageRef = "")
        }
    }

    suspend fun sealIncognito(chatId: String, message: IncognitoMessage): IncognitoMessage {
        val sealed = seal(
            context = CONTEXT_INCOGNITO,
            chatId = chatId,
            messageId = message.messageId,
            senderId = message.senderId,
            receiverId = message.receiverId,
            messageType = MessageType.Text.name,
            timestamp = message.timestamp,
            content = message.messageText
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

        val opened = open(
            context = CONTEXT_INCOGNITO,
            chatId = chatId,
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
            is Opened.Plain -> message.copy(messageText = opened.text)
            is Opened.Failed -> message.copy(messageText = opened.reason)
        }
    }

    // ---------------------------------------------------------------- core seal / open

    private suspend fun seal(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        content: String
    ): Sealed {
        onSignedIn(senderId)
        val me = identityFor(senderId)

        // Always ask the directory first so a contact's new key is picked up immediately.
        val peerKeyB64 = (if (receiverId == senderId) me.publicKeyB64
        else fetchPeerKey(receiverId) ?: currentPeerKeys[receiverId])
            ?: throw E2eeException(
                "Can't send securely yet: the other user needs to sign in to the latest ClubMate first"
            )
        val peerKey = decodeKey(peerKeyB64)
            ?: throw E2eeException("The other user's encryption key is invalid")

        return try {
            val aad = E2eeCrypto.messageAad(
                context, chatId, messageId, senderId, receiverId, messageType, timestamp,
                me.publicKey, peerKey
            )
            val ciphertext = E2eeCrypto.encrypt(
                conversationKey(me, receiverId, peerKey, peerKeyB64),
                content.toByteArray(Charsets.UTF_8),
                aad
            )
            Sealed(encode(ciphertext), me.publicKeyB64, peerKeyB64)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            throw E2eeException("Message could not be encrypted")
        }
    }

    private suspend fun open(
        context: String,
        chatId: String,
        messageId: String,
        senderId: String,
        receiverId: String,
        messageType: String,
        timestamp: Long,
        senderKeyB64: String,
        receiverKeyB64: String,
        ciphertextB64: String,
        myUid: String
    ): Opened {
        val me = identityFor(myUid)
        val iAmSender = senderId == myUid
        if (!iAmSender && receiverId != myUid) return Opened.Failed(TEXT_UNDECRYPTABLE)

        val peerUid = if (iAmSender) receiverId else senderId
        val myKeyInMessage = if (iAmSender) senderKeyB64 else receiverKeyB64
        val peerKeyInMessage = if (iAmSender) receiverKeyB64 else senderKeyB64

        // Encrypted for a key pair this device doesn't have (e.g. the app was reinstalled).
        if (myKeyInMessage != me.publicKeyB64) return Opened.Failed(TEXT_UNDECRYPTABLE)

        val peerKey = decodeKey(peerKeyInMessage) ?: return Opened.Failed(TEXT_UNTRUSTED_KEY)
        if (!isTrustedPeerKey(me, peerUid, peerKeyInMessage)) {
            Log.w(TAG, "Rejected message $messageId: unknown key for $peerUid")
            return Opened.Failed(TEXT_UNTRUSTED_KEY)
        }

        return try {
            val aad = E2eeCrypto.messageAad(
                context, chatId, messageId, senderId, receiverId, messageType, timestamp,
                if (iAmSender) me.publicKey else peerKey,
                if (iAmSender) peerKey else me.publicKey
            )
            val plaintext = E2eeCrypto.decrypt(
                conversationKey(me, peerUid, peerKey, peerKeyInMessage),
                decode(ciphertextB64),
                aad
            )
            Opened.Plain(String(plaintext, Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Rejected message $messageId: authentication failed")
            Opened.Failed(TEXT_UNDECRYPTABLE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected message $messageId: malformed ciphertext")
            Opened.Failed(TEXT_UNDECRYPTABLE)
        }
    }

    // ---------------------------------------------------------------- keys

    @Synchronized
    private fun identityFor(uid: String): Identity {
        identity?.let { if (it.uid == uid) return it }
        val privateKey = vault.loadPrivateKey(uid)
            ?: E2eeCrypto.generatePrivateKey().also {
                vault.savePrivateKey(uid, it)
                Log.i(TAG, "Generated a new key pair for this device")
            }
        conversationKeys.clear()
        return Identity(uid, privateKey, E2eeCrypto.publicKeyOf(privateKey)).also { identity = it }
    }

    /**
     * A contact's key is trusted if the directory (`user/{uid}/publicKey`) has shown it for that
     * contact, now or in the past. Keys that only appear inside a message are never trusted.
     */
    private suspend fun isTrustedPeerKey(me: Identity, peerUid: String, keyB64: String): Boolean {
        if (peerUid == me.uid) return keyB64 == me.publicKeyB64
        if (currentPeerKeys[peerUid] == keyB64 || vault.isKnownPeerKey(peerUid, keyB64)) return true

        val lookup = "$peerUid|$keyB64"
        if (lookup in rejectedPeerKeys) return false
        // The contact may have changed keys since we last looked: ask the directory once.
        if (fetchPeerKey(peerUid) == keyB64) return true
        rejectedPeerKeys.add(lookup)
        return false
    }

    private suspend fun fetchPeerKey(peerUid: String): String? = suspendCoroutine { cont ->
        userRef.child(peerUid).child("publicKey").get()
            .addOnSuccessListener { snapshot ->
                val keyB64 = snapshot.value as? String
                if (keyB64 != null && decodeKey(keyB64) != null) {
                    rememberPeerKey(peerUid, keyB64)
                    cont.resume(keyB64)
                } else {
                    // Missing, or still the old RSA key from before E2EE was enabled.
                    cont.resume(null)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not fetch key of $peerUid: ${e.message}")
                cont.resume(null)
            }
    }

    private fun rememberPeerKey(peerUid: String, keyB64: String) {
        val previous = currentPeerKeys.put(peerUid, keyB64)
        if (previous != null && previous != keyB64) {
            Log.i(TAG, "Encryption key of $peerUid changed")
        }
        rejectedPeerKeys.remove("$peerUid|$keyB64")
        vault.addKnownPeerKey(peerUid, keyB64)
    }

    private fun conversationKey(
        me: Identity,
        peerUid: String,
        peerKey: ByteArray,
        peerKeyB64: String
    ): ByteArray = conversationKeys.getOrPut("${me.publicKeyB64}|$peerUid|$peerKeyB64") {
        E2eeCrypto.conversationKey(me.uid, me.privateKey, peerUid, peerKey)
    }

    private fun decodeKey(keyB64: String): ByteArray? = try {
        decode(keyB64).takeIf { it.size == E2eeCrypto.KEY_SIZE }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
