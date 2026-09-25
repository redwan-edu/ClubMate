package com.example.clubmate.e2ee

import android.util.Log
import com.example.clubmate.crypto.DoubleRatchet
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.crypto.RatchetHeader
import com.example.clubmate.crypto.X3dh
import com.example.clubmate.e2ee.E2eeManager.E2eeException
import com.example.clubmate.e2ee.E2eeManager.Identity
import com.example.clubmate.e2ee.E2eeManager.KeyKind
import com.example.clubmate.e2ee.E2eeManager.OpenedBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

/**
 * Double Ratchet sessions for 1:1 chats (and their incognito messages).
 *
 * - The first message to a contact runs X3DH against their identity key and signed prekey, and
 *   carries a [PreKeyInfo] until the contact replies.
 * - Every message then uses a fresh key from the Double Ratchet; keys are deleted after use.
 * - Because a message can be decrypted only once, the decrypted text is kept in an encrypted
 *   on-device [ChatStore]. Senders keep their own plaintext the same way. Each stored text is tied to
 *   a hash of the message's authenticated metadata, so a message can't later be shown again with
 *   different metadata (another chat, time, type, or as incognito/normal).
 * - All work on one chat is serialised by a lock, and a sending step is saved to disk *before* the
 *   ciphertext leaves the device, so a crash can never make the app reuse a message key.
 */
object RatchetSessions {

    private const val TAG = "Ratchet"

    class Envelope(val header: RatchetHeader, val ciphertext: ByteArray, val preKey: PreKeyInfo?)

    private lateinit var backend: StoreBackend
    private lateinit var storageKey: () -> ByteArray

    private val cache = ConcurrentHashMap<String, ChatStore>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    // incognito plaintext is never written to disk: "storeName|storeId" -> stored entry
    private val transient = ConcurrentHashMap<String, String>()

    internal fun init(backend: StoreBackend, storageKey: () -> ByteArray) {
        this.backend = backend
        this.storageKey = storageKey
    }

    internal fun onSignedOut() {
        cache.clear()
        transient.clear()
    }

    /** Encrypts [plaintext] for [peerUid]; starts a new session first if needed. */
    internal suspend fun encrypt(
        chatId: String,
        me: Identity,
        peerUid: String,
        storeId: String,
        plaintext: String,
        extraAad: ByteArray,
        persist: Boolean
    ): Envelope {
        val name = storeName(me.uid, chatId)
        return lock(name).withLock {
            val store = load(name)
            val peerIdentityB64 = if (peerUid == me.uid) me.dhPublicB64
            else E2eeManager.peerKey(peerUid, KeyKind.DH, fresh = true)
            val peerIdentity = peerIdentityB64?.let { E2eeManager.decode(it) }
                ?: throw E2eeException(E2eeManager.TEXT_PEER_NEEDS_UPDATE)

            val sessions = store.sessions.toMutableList()
            val current = sessions.firstOrNull()?.takeIf {
                it.state.canSend && it.peerIdentity.contentEquals(peerIdentity) && it.myIdentity.contentEquals(me.dhPublic)
            }
            val session = current ?: startSession(me, peerUid, peerIdentity).also { sessions.add(0, it) }

            val encrypted = try {
                DoubleRatchet.encrypt(session.state, plaintext.toByteArray(Charsets.UTF_8), extraAad)
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Encryption failed", e)
                throw E2eeException("Message could not be encrypted")
            }
            sessions[0] = session.with(encrypted.state)
            while (sessions.size > ChatStore.MAX_SESSIONS) sessions.removeAt(sessions.lastIndex)

            val messages = LinkedHashMap(store.messages)
            val entry = record(extraAad, plaintext)
            if (persist) messages[storeId] = entry else transient["$name|$storeId"] = entry
            // Saved before sending: if this fails, nothing is sent and no key is ever reused.
            if (!save(name, ChatStore(sessions, messages))) {
                transient.remove("$name|$storeId")
                throw E2eeException("Couldn't save the encryption state. Is the phone's storage full?")
            }
            Envelope(encrypted.header, encrypted.ciphertext, session.pendingPreKey)
        }
    }

    /** Decrypts a contact's message (or returns the stored text if it was decrypted before). */
    internal suspend fun decrypt(
        chatId: String,
        me: Identity,
        senderId: String,
        storeId: String,
        envelope: Envelope,
        extraAad: ByteArray,
        persist: Boolean
    ): OpenedBytes {
        val name = storeName(me.uid, chatId)
        return lock(name).withLock {
            val store = load(name)
            (store.messages[storeId] ?: transient["$name|$storeId"])?.let { entry ->
                // decrypted before: only valid if the metadata is still exactly the same
                return@withLock textOf(entry, extraAad)?.let { OpenedBytes.Ok(it.toByteArray(Charsets.UTF_8)) }
                    ?: OpenedBytes.Failed(E2eeManager.TEXT_UNDECRYPTABLE)
            }

            val candidates = when (val preKey = envelope.preKey) {
                null -> store.sessions.toList()
                else -> {
                    val existing = store.sessions.firstOrNull { it.baseKey.contentEquals(preKey.ephemeralKey) }
                    if (existing != null) listOf(existing)
                    else listOf(respondToSession(me, senderId, preKey) ?: return@withLock failedStart(me, senderId, preKey))
                }
            }

            for (session in candidates) {
                val decrypted = try {
                    DoubleRatchet.decrypt(session.state, envelope.header, envelope.ciphertext, extraAad)
                } catch (e: GeneralSecurityException) {
                    continue
                }
                // The contact has now heard from us or answered us: stop sending the prekey block,
                // and make this the session we use (Signal's rule, so both sides converge).
                val sessions = store.sessions.filterNot { it === session }.toMutableList()
                sessions.add(0, session.with(decrypted.state, pendingPreKey = null))
                while (sessions.size > ChatStore.MAX_SESSIONS) sessions.removeAt(sessions.lastIndex)

                val entry = record(extraAad, String(decrypted.plaintext, Charsets.UTF_8))
                val messages = LinkedHashMap(store.messages)
                if (persist) messages[storeId] = entry else transient["$name|$storeId"] = entry
                save(name, ChatStore(sessions, messages))
                return@withLock OpenedBytes.Ok(decrypted.plaintext)
            }
            Log.w(TAG, "No session could decrypt $storeId")
            OpenedBytes.Failed(E2eeManager.TEXT_UNDECRYPTABLE)
        }
    }

    /**
     * The text of a message this device sent (the sender can't decrypt their own ciphertext), or
     * null if it isn't on this device or its metadata was changed since it was sent.
     */
    internal suspend fun sentText(chatId: String, myUid: String, storeId: String, extraAad: ByteArray): String? {
        val name = storeName(myUid, chatId)
        return lock(name).withLock {
            (load(name).messages[storeId] ?: transient["$name|$storeId"])?.let { textOf(it, extraAad) }
        }
    }

    /** Removes deleted messages' text from this device. */
    internal suspend fun forget(chatId: String, myUid: String, storeIds: List<String>) {
        val name = storeName(myUid, chatId)
        lock(name).withLock {
            storeIds.forEach { transient.remove("$name|$it") }
            val store = load(name)
            if (storeIds.any { store.messages.containsKey(it) }) {
                val messages = LinkedHashMap(store.messages).also { m -> storeIds.forEach { m.remove(it) } }
                save(name, ChatStore(store.sessions.toMutableList(), messages))
            }
        }
    }

    /** Removes all stored text of a deleted chat (the sessions are kept for future messages). */
    internal suspend fun forgetChat(chatId: String, myUid: String) {
        val name = storeName(myUid, chatId)
        lock(name).withLock {
            transient.keys.removeAll { it.startsWith("$name|") }
            val store = load(name)
            save(name, ChatStore(store.sessions.toMutableList(), LinkedHashMap()))
        }
    }

    // ---------------------------------------------------------------- session setup

    private suspend fun startSession(me: Identity, peerUid: String, peerIdentity: ByteArray): RatchetSession {
        val bundle = E2eeManager.fetchPreKeyBundle(peerUid)
            ?: throw E2eeException(E2eeManager.TEXT_PEER_NEEDS_UPDATE)
        return try {
            val init = X3dh.initiate(me.dhPrivate, peerIdentity, bundle.second)
            val ad = X3dh.associatedData(me.uid, me.dhPublic, peerUid, peerIdentity)
            RatchetSession(
                state = DoubleRatchet.initInitiator(init.sharedSecret, bundle.second, ad),
                peerIdentity = peerIdentity,
                myIdentity = me.dhPublic,
                baseKey = init.ephemeralPublic,
                pendingPreKey = PreKeyInfo(me.dhPublic, init.ephemeralPublic, bundle.first)
            ).also { Log.i(TAG, "Started a new session with $peerUid") }
        } catch (e: GeneralSecurityException) {
            throw E2eeException("The other user's keys are invalid")
        }
    }

    private suspend fun respondToSession(me: Identity, senderId: String, preKey: PreKeyInfo): RatchetSession? {
        // The initiator's identity key must be one the directory has shown for them.
        if (!E2eeManager.isTrustedPeerKey(me, senderId, E2eeManager.encode(preKey.identityKey), KeyKind.DH)) return null
        val signedPreKey = E2eeManager.signedPreKeyPrivate(me.uid, preKey.preKeyId) ?: return null
        return try {
            val sharedSecret = X3dh.respond(me.dhPrivate, signedPreKey, preKey.identityKey, preKey.ephemeralKey)
            val ad = X3dh.associatedData(senderId, preKey.identityKey, me.uid, me.dhPublic)
            RatchetSession(
                state = DoubleRatchet.initResponder(sharedSecret, signedPreKey, ad),
                peerIdentity = preKey.identityKey,
                myIdentity = me.dhPublic,
                baseKey = preKey.ephemeralKey,
                pendingPreKey = null
            )
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private suspend fun failedStart(me: Identity, senderId: String, preKey: PreKeyInfo): OpenedBytes {
        val trusted = E2eeManager.isTrustedPeerKey(me, senderId, E2eeManager.encode(preKey.identityKey), KeyKind.DH)
        Log.w(TAG, "Couldn't start a session from $senderId (trusted identity: $trusted)")
        return OpenedBytes.Failed(if (trusted) E2eeManager.TEXT_UNDECRYPTABLE else E2eeManager.TEXT_UNTRUSTED_KEY)
    }

    // ---------------------------------------------------------------- storage

    // Stored text = 32 hex chars of SHA-256(metadata) + the text itself.
    private fun record(extraAad: ByteArray, text: String): String = metadataTag(extraAad) + text

    private fun textOf(entry: String, extraAad: ByteArray): String? {
        val tag = metadataTag(extraAad)
        return if (entry.length >= tag.length && entry.startsWith(tag)) entry.substring(tag.length) else null
    }

    private fun metadataTag(extraAad: ByteArray): String =
        E2eeCrypto.sha256(extraAad).take(16).joinToString("") { "%02x".format(it) }

    private fun lock(name: String) = locks.getOrPut(name) { Mutex() }

    private fun storeName(myUid: String, chatId: String): String =
        E2eeCrypto.sha256("$myUid|$chatId".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    private fun load(name: String): ChatStore {
        cache[name]?.let { return it }
        val data = try {
            backend.read(name)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read chat store", e)
            null
        }
        val store = data?.let {
            try {
                ChatStore.open(storageKey(), name, it)
            } catch (e: GeneralSecurityException) {
                // e.g. the Keystore key was lost; old history is unreadable, new messages still work
                Log.e(TAG, "Chat store unreadable; starting fresh", e)
                null
            }
        } ?: ChatStore()
        cache[name] = store
        return store
    }

    private fun save(name: String, store: ChatStore): Boolean = try {
        backend.write(name, ChatStore.seal(storageKey(), name, store))
        cache[name] = store
        true
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't save chat store", e)
        false
    }
}
