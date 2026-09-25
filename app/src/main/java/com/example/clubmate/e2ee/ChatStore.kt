package com.example.clubmate.e2ee

import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.crypto.RatchetState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/** Sent by the initiator in every message until the other side replies (X3DH "initial message"). */
class PreKeyInfo(val identityKey: ByteArray, val ephemeralKey: ByteArray, val preKeyId: Int)

/**
 * One Double Ratchet session with a contact. [baseKey] is the initiator's X3DH ephemeral key, which
 * both sides use to recognise the session. [peerIdentity]/[myIdentity] are the X25519 identity keys
 * it was set up with, so a reinstall on either side leads to a fresh session.
 */
class RatchetSession(
    val state: RatchetState,
    val peerIdentity: ByteArray,
    val myIdentity: ByteArray,
    val baseKey: ByteArray,
    val pendingPreKey: PreKeyInfo?
) {
    fun with(state: RatchetState, pendingPreKey: PreKeyInfo? = this.pendingPreKey) =
        RatchetSession(state, peerIdentity, myIdentity, baseKey, pendingPreKey)
}

/**
 * Everything this device keeps for one 1:1 chat: the ratchet sessions (current first, a few older
 * ones for messages still in flight) and the decrypted messages. The ratchet deletes message keys
 * after use, so decrypted text must be kept here: it can't be decrypted from Firebase a second time.
 *
 * Stored encrypted (AES-256-GCM with a Keystore-protected device key) by [RatchetStore].
 */
class ChatStore(
    val sessions: MutableList<RatchetSession> = mutableListOf(),
    val messages: LinkedHashMap<String, String> = LinkedHashMap()
) {
    fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(FORMAT)
            out.writeInt(sessions.size)
            for (s in sessions) {
                RatchetState.writeBytes(out, s.state.serialize())
                RatchetState.writeBytes(out, s.peerIdentity)
                RatchetState.writeBytes(out, s.myIdentity)
                RatchetState.writeBytes(out, s.baseKey)
                val pending = s.pendingPreKey
                out.writeBoolean(pending != null)
                if (pending != null) {
                    RatchetState.writeBytes(out, pending.identityKey)
                    RatchetState.writeBytes(out, pending.ephemeralKey)
                    out.writeInt(pending.preKeyId)
                }
            }
            out.writeInt(messages.size)
            for ((id, text) in messages) {
                RatchetState.writeBytes(out, id.toByteArray(Charsets.UTF_8))
                RatchetState.writeBytes(out, text.toByteArray(Charsets.UTF_8))
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val FORMAT = 1
        const val MAX_SESSIONS = 5

        fun deserialize(data: ByteArray): ChatStore = try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readByte().toInt() != FORMAT) throw GeneralSecurityException("Unknown store format")
                val sessions = mutableListOf<RatchetSession>()
                repeat(input.readInt()) {
                    val state = RatchetState.deserialize(RatchetState.readBytes(input)!!)
                    val peer = RatchetState.readBytes(input)!!
                    val mine = RatchetState.readBytes(input)!!
                    val base = RatchetState.readBytes(input)!!
                    val pending = if (input.readBoolean()) {
                        PreKeyInfo(RatchetState.readBytes(input)!!, RatchetState.readBytes(input)!!, input.readInt())
                    } else null
                    sessions += RatchetSession(state, peer, mine, base, pending)
                }
                val messages = LinkedHashMap<String, String>()
                repeat(input.readInt()) {
                    val id = String(RatchetState.readBytes(input)!!, Charsets.UTF_8)
                    messages[id] = String(RatchetState.readBytes(input)!!, Charsets.UTF_8)
                }
                ChatStore(sessions, messages)
            }
        } catch (e: IOException) {
            throw GeneralSecurityException("Corrupt chat store", e)
        } catch (e: NullPointerException) {
            throw GeneralSecurityException("Corrupt chat store", e)
        }

        /** Encrypts a serialized store for disk, bound to its file name. */
        fun seal(storageKey: ByteArray, name: String, store: ChatStore): ByteArray =
            E2eeCrypto.encrypt(storageKey, store.serialize(), aad(name))

        fun open(storageKey: ByteArray, name: String, data: ByteArray): ChatStore =
            deserialize(E2eeCrypto.decrypt(storageKey, data, aad(name)))

        private fun aad(name: String) = "ClubMate-store-v1|$name".toByteArray(Charsets.UTF_8)
    }
}

/** Where encrypted chat stores are kept: files on Android, memory in tests. */
interface StoreBackend {
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
}
