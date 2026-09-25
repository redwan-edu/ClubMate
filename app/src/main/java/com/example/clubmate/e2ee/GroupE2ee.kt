package com.example.clubmate.e2ee

import android.util.Log
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.e2ee.E2eeManager.E2eeException
import com.example.clubmate.e2ee.E2eeManager.KeyKind
import com.example.clubmate.e2ee.E2eeManager.OpenedBytes
import com.example.clubmate.e2ee.E2eeManager.OpenedFields
import com.example.clubmate.viewmodel.EventData
import com.example.clubmate.viewmodel.GroupActivity
import com.example.clubmate.viewmodel.GroupActivityType
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * End-to-end encryption for groups (activity messages and notice-board events).
 *
 * Every group has a random 32-byte **group key**. Each generation of it is an "epoch":
 *
 * ```
 * groups/{grpId}/e2ee/current            = epochId            (the epoch senders should use)
 * groups/{grpId}/e2ee/epochs/{epochId}   = { v, createdBy, createdAt, creatorKey,
 *                                            keys/{memberUid} = { ct, receiverKey } }
 * ```
 *
 * `keys/{memberUid}.ct` is the group key encrypted from the creator to that member with their 1:1
 * Diffie-Hellman key, so only listed members can unwrap it. Before sending, a member checks that the
 * current epoch was made for exactly the current participants and their current keys; if anyone
 * joined, left, was removed or reinstalled, a fresh epoch is created first. Removed members therefore
 * can't read anything sent after they left, and new members can't read what was sent before they
 * joined.
 *
 * Messages are AES-256-GCM encrypted with the group key and signed with the sender's Ed25519 key, so
 * a member can't post in someone else's name. The group's name, description, photo and member list
 * stay readable by the server (they are needed for search and joining).
 */
object GroupE2ee {

    private const val TAG = "GroupE2EE"
    private const val CONTEXT_ACTIVITY = "group-activity"
    private const val CONTEXT_EVENT = "group-event"
    private const val CONTEXT_GROUP_KEY = "group-key"
    private const val TYPE_GROUP_KEY = "GroupKey"

    internal class Envelope(val ciphertext: String, val receiverKey: String)

    internal class Epoch(
        val id: String,
        val createdBy: String,
        val createdAt: Long,
        val creatorKey: String,
        val envelopes: Map<String, Envelope>
    )

    private val groupsRef get() = FirebaseDatabase.getInstance().getReference("groups")

    private val epochs = ConcurrentHashMap<String, Epoch>() // "grpId|epochId"
    private val groupKeys = ConcurrentHashMap<String, ByteArray>() // "grpId|epochId"
    private val sendLocks = ConcurrentHashMap<String, Mutex>()
    private val memberWatchers = ConcurrentHashMap<String, ValueEventListener>()

    internal fun onSignedOut() {
        epochs.clear()
        groupKeys.clear()
        memberWatchers.forEach { (grpId, listener) ->
            groupsRef.child(grpId).child("participants").removeEventListener(listener)
        }
        memberWatchers.clear()
    }

    /** Keeps every member's public keys fresh while the group is open, so rotations use them. */
    fun watchMembers(grpId: String) {
        if (grpId.isEmpty() || memberWatchers.containsKey(grpId)) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                (snapshot.value as? Map<*, *>)?.keys?.forEach { uid ->
                    (uid as? String)?.let { E2eeManager.watchPeer(it) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Stopped watching members of $grpId: ${error.message}")
            }
        }
        if (memberWatchers.putIfAbsent(grpId, listener) == null) {
            groupsRef.child(grpId).child("participants").addValueEventListener(listener)
        }
    }

    // ---------------------------------------------------------------- activities (group chat)

    suspend fun sealActivity(grpId: String, activity: GroupActivity): GroupActivity {
        val message = activity.message
        val (epochId, key) = sendingKey(grpId, message.senderId)
        val isImage = activity.type == GroupActivityType.Image
        val sealed = E2eeManager.sealSigned(
            key = key,
            context = CONTEXT_ACTIVITY,
            scopeId = grpId,
            epochId = epochId,
            messageId = message.messageId,
            senderId = message.senderId,
            messageType = activity.type.name,
            timestamp = message.timestamp,
            fields = listOf(if (isImage) message.imageRef else message.messageText)
        )
        return activity.copy(
            message = message.copy(
                messageText = "",
                imageRef = "",
                v = E2eeCrypto.VERSION,
                epochId = epochId,
                ct = sealed.ciphertext,
                signKey = sealed.signKey,
                sig = sealed.signature
            )
        )
    }

    suspend fun openActivity(grpId: String, activity: GroupActivity, myUid: String): GroupActivity {
        val message = activity.message
        if (message.v == 0) {
            return if (message.messageText.isNotEmpty()) {
                activity.copy(message = message.copy(messageText = E2eeManager.NOT_ENCRYPTED_PREFIX + message.messageText))
            } else activity
        }
        if (message.v != E2eeCrypto.VERSION) {
            return activity.copy(message = message.copy(messageText = E2eeManager.TEXT_UNSUPPORTED, imageRef = ""))
        }

        val opened = openContent(
            grpId = grpId,
            epochId = message.epochId,
            context = CONTEXT_ACTIVITY,
            messageId = message.messageId,
            senderId = message.senderId,
            messageType = activity.type.name,
            timestamp = message.timestamp,
            ciphertext = message.ct,
            signKey = message.signKey,
            signature = message.sig,
            myUid = myUid,
            fieldCount = 1
        )
        return when (opened) {
            is OpenedFields.Ok -> activity.copy(
                message = if (activity.type == GroupActivityType.Image) {
                    message.copy(imageRef = opened.fields[0], messageText = "")
                } else {
                    message.copy(messageText = opened.fields[0], imageRef = "")
                }
            )

            is OpenedFields.Failed -> activity.copy(message = message.copy(messageText = opened.reason, imageRef = ""))
        }
    }

    // ---------------------------------------------------------------- notice board events

    suspend fun sealEvent(grpId: String, event: EventData, senderId: String): EventData {
        val (epochId, key) = sendingKey(grpId, senderId)
        val sealed = E2eeManager.sealSigned(
            key = key,
            context = CONTEXT_EVENT,
            scopeId = grpId,
            epochId = epochId,
            messageId = event.messageId,
            senderId = senderId,
            messageType = eventType(event),
            timestamp = event.timeStamp,
            fields = listOf(event.title, event.description)
        )
        return event.copy(
            title = "",
            description = "",
            senderId = senderId,
            v = E2eeCrypto.VERSION,
            epochId = epochId,
            ct = sealed.ciphertext,
            signKey = sealed.signKey,
            sig = sealed.signature
        )
    }

    suspend fun openEvent(grpId: String, event: EventData, myUid: String): EventData {
        if (event.v == 0) {
            return event.copy(title = E2eeManager.NOT_ENCRYPTED_PREFIX + event.title)
        }
        if (event.v != E2eeCrypto.VERSION) {
            return event.copy(title = E2eeManager.TEXT_UNSUPPORTED, description = "")
        }

        val opened = openContent(
            grpId = grpId,
            epochId = event.epochId,
            context = CONTEXT_EVENT,
            messageId = event.messageId,
            senderId = event.senderId,
            messageType = eventType(event),
            timestamp = event.timeStamp,
            ciphertext = event.ct,
            signKey = event.signKey,
            signature = event.sig,
            myUid = myUid,
            fieldCount = 2
        )
        return when (opened) {
            is OpenedFields.Ok -> event.copy(title = opened.fields[0], description = opened.fields[1])
            is OpenedFields.Failed -> event.copy(title = opened.reason, description = "")
        }
    }

    // The category and who may see a notice are bound to it, so the server can't change them.
    private fun eventType(event: EventData) = "${event.type.name}|${event.visibility.name}"

    // ---------------------------------------------------------------- reading

    private suspend fun openContent(
        grpId: String,
        epochId: String,
        context: String,
        messageId: String,
        senderId: String,
        messageType: String,
        timestamp: Long,
        ciphertext: String,
        signKey: String,
        signature: String,
        myUid: String,
        fieldCount: Int
    ): OpenedFields {
        val me = E2eeManager.identityFor(myUid)
        val epoch = loadEpoch(grpId, epochId) ?: return OpenedFields.Failed(E2eeManager.TEXT_NO_GROUP_KEY)
        val key = unwrapGroupKey(grpId, epoch, me) ?: return OpenedFields.Failed(E2eeManager.TEXT_NO_GROUP_KEY)

        // Only people the key was given to can have written with it.
        if (senderId !in epoch.envelopes) {
            Log.w(TAG, "Rejected $messageId: $senderId was not a member of epoch $epochId")
            return OpenedFields.Failed(E2eeManager.TEXT_UNTRUSTED_KEY)
        }

        return E2eeManager.openSigned(
            key, context, grpId, epochId, messageId, senderId, messageType, timestamp,
            ciphertext, signKey, signature, myUid, fieldCount
        )
    }

    private suspend fun unwrapGroupKey(grpId: String, epoch: Epoch, me: E2eeManager.Identity): ByteArray? {
        val cacheKey = "$grpId|${epoch.id}"
        groupKeys[cacheKey]?.let { return it }

        // The creator must be a member of the epoch they created, using the key they wrapped with.
        if (epoch.envelopes[epoch.createdBy]?.receiverKey != epoch.creatorKey) return null
        val envelope = epoch.envelopes[me.uid] ?: return null

        val opened = E2eeManager.openPairwise(
            context = CONTEXT_GROUP_KEY,
            scopeId = grpId,
            messageId = epoch.id,
            senderId = epoch.createdBy,
            receiverId = me.uid,
            messageType = TYPE_GROUP_KEY,
            timestamp = epoch.createdAt,
            senderKeyB64 = epoch.creatorKey,
            receiverKeyB64 = envelope.receiverKey,
            ciphertextB64 = envelope.ciphertext,
            myUid = me.uid
        )
        val key = (opened as? OpenedBytes.Ok)?.bytes?.takeIf { it.size == E2eeCrypto.KEY_SIZE }
            ?: return null
        groupKeys[cacheKey] = key
        return key
    }

    // ---------------------------------------------------------------- sending / key rotation

    /** The epoch and group key to send with, rotating first if the membership has changed. */
    private suspend fun sendingKey(grpId: String, senderId: String): Pair<String, ByteArray> {
        val me = E2eeManager.requireIdentity(senderId)
        return sendLocks.getOrPut(grpId) { Mutex() }.withLock {
            val members = fetchMembers(grpId)
                ?: throw E2eeException("Couldn't load the group members. Check your connection")
            if (me.uid !in members) throw E2eeException("You're not a member of this group")

            // Members who haven't signed in to an E2EE version yet have no key and can't be included.
            val recipients = LinkedHashMap<String, String>()
            for (uid in members) {
                val key = if (uid == me.uid) me.dhPublicB64 else E2eeManager.peerKey(uid, KeyKind.DH, fresh = false)
                if (key != null) recipients[uid] = key
            }

            val currentId = fetchString(groupsRef.child(grpId).child("e2ee").child("current"))
            if (currentId != null) {
                val current = loadEpoch(grpId, currentId)
                if (current != null && current.envelopes.mapValues { it.value.receiverKey } == recipients) {
                    unwrapGroupKey(grpId, current, me)?.let { return@withLock currentId to it }
                }
            }
            createEpoch(grpId, me, recipients)
        }
    }

    private suspend fun createEpoch(
        grpId: String,
        me: E2eeManager.Identity,
        recipients: Map<String, String>
    ): Pair<String, ByteArray> {
        val e2eeRef = groupsRef.child(grpId).child("e2ee")
        val epochId = e2eeRef.child("epochs").push().key
            ?: throw E2eeException("Couldn't create a group key")
        val groupKey = E2eeCrypto.generateSymmetricKey()
        val createdAt = System.currentTimeMillis()

        val envelopes = LinkedHashMap<String, Envelope>()
        for ((uid, keyB64) in recipients) {
            val sealed = E2eeManager.sealPairwise(
                context = CONTEXT_GROUP_KEY,
                scopeId = grpId,
                messageId = epochId,
                senderId = me.uid,
                receiverId = uid,
                messageType = TYPE_GROUP_KEY,
                timestamp = createdAt,
                content = groupKey,
                receiverKeyB64 = keyB64
            )
            envelopes[uid] = Envelope(sealed.ciphertext, keyB64)
        }

        val epochData = mapOf(
            "v" to E2eeCrypto.VERSION,
            "createdBy" to me.uid,
            "createdAt" to createdAt,
            "creatorKey" to me.dhPublicB64,
            "keys" to envelopes.mapValues { (_, e) ->
                mapOf("ct" to e.ciphertext, "receiverKey" to e.receiverKey)
            }
        )
        // One atomic write: the new epoch and the pointer to it. Firebase keeps this client's writes
        // in order, so the epoch always exists before any message that uses it.
        e2eeRef.updateChildren(mapOf("epochs/$epochId" to epochData, "current" to epochId))
            .addOnFailureListener { Log.e(TAG, "Failed to store group key for $grpId", it) }

        val epoch = Epoch(epochId, me.uid, createdAt, me.dhPublicB64, envelopes)
        epochs["$grpId|$epochId"] = epoch
        groupKeys["$grpId|$epochId"] = groupKey
        Log.i(TAG, "New group key for $grpId shared with ${recipients.size} member(s)")
        return epochId to groupKey
    }

    // ---------------------------------------------------------------- Firebase helpers

    private suspend fun loadEpoch(grpId: String, epochId: String): Epoch? {
        if (epochId.isEmpty() || epochId.contains('/')) return null
        epochs["$grpId|$epochId"]?.let { return it }
        val raw = fetch(groupsRef.child(grpId).child("e2ee").child("epochs").child(epochId)).value as? Map<*, *>
            ?: return null
        val epoch = parseEpoch(epochId, raw) ?: return null
        epochs["$grpId|$epochId"] = epoch
        return epoch
    }

    private fun parseEpoch(epochId: String, raw: Map<*, *>): Epoch? {
        val createdBy = raw["createdBy"] as? String ?: return null
        val createdAt = (raw["createdAt"] as? Number)?.toLong() ?: return null
        val creatorKey = raw["creatorKey"] as? String ?: return null
        val keys = raw["keys"] as? Map<*, *> ?: return null
        val envelopes = LinkedHashMap<String, Envelope>()
        for ((uid, value) in keys) {
            val entry = value as? Map<*, *> ?: continue
            val ct = entry["ct"] as? String ?: continue
            val receiverKey = entry["receiverKey"] as? String ?: continue
            if (uid is String) envelopes[uid] = Envelope(ct, receiverKey)
        }
        return Epoch(epochId, createdBy, createdAt, creatorKey, envelopes)
    }

    private suspend fun fetchMembers(grpId: String): Set<String>? {
        val fetched = fetch(groupsRef.child(grpId).child("participants"))
        if (!fetched.ok) return null
        return (fetched.value as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet() ?: emptySet()
    }

    private suspend fun fetchString(ref: DatabaseReference): String? = fetch(ref).value as? String

    private class Fetched(val ok: Boolean, val value: Any?)

    private suspend fun fetch(ref: DatabaseReference): Fetched = suspendCoroutine { cont ->
        ref.get()
            .addOnSuccessListener { snapshot -> cont.resume(Fetched(true, snapshot.value)) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Read failed: ${e.message}")
                cont.resume(Fetched(false, null))
            }
    }
}
