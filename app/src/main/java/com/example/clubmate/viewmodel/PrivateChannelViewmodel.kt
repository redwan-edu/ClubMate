package com.example.clubmate.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudinary.android.MediaManager
import com.cloudinary.utils.ObjectUtils
import com.example.clubmate.e2ee.ChannelE2ee
import com.example.clubmate.e2ee.E2eeManager
import com.example.clubmate.e2ee.SecureImages
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


class PrivateChannelViewModel : ViewModel() {

    private val _channelRef = FirebaseDatabase.getInstance().getReference("private_channels")
    private val _privateMessageList = MutableStateFlow<List<VanishingMessage>>(emptyList())
    val privateMessageList = _privateMessageList

    // set when the channel can't be unlocked or a message can't be sent; the screen shows a toast
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    // the key of the channel open in this screen; sending waits until it has been derived
    private val channelKey = CompletableDeferred<ByteArray?>()
    private var messagesRef: DatabaseReference? = null
    private var messagesListener: ChildEventListener? = null

    fun createChatroom(
        chatId: String, passWord: String, uid: String, onResult: (ChannelMap?) -> Unit
    ) {
        if (chatId.isEmpty() || passWord.length < ChannelE2ee.MIN_PASSWORD_LENGTH || uid.isEmpty()) {
            onResult(null)
            return
        }

        _channelRef.child(chatId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    onResult(null)
                    return
                }
                viewModelScope.launch {
                    // Only a salt and a password verifier are stored, never the password itself.
                    val createdAt = System.currentTimeMillis()
                    val channel = ChannelE2ee.create(chatId, passWord)
                    _channelRef.child(chatId).setValue(channel.fields + ("createdAt" to createdAt))
                        .addOnSuccessListener {
                            onResult(ChannelMap(createdAt = createdAt))
                        }.addOnFailureListener {
                            onResult(null)
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(null)
            }
        })
    }

    fun joinChatroom(chatId: String, uid: String, passWord: String, onClick: (Boolean) -> Unit) {
        unlockChannel(chatId, passWord) { key ->
            if (key != null) markMessagesAsSeen(chatId = chatId, uid = uid)
            onClick(key != null)
        }
    }

    /** Derives the channel key from the password, then starts showing (decrypted) messages. */
    fun openChannel(channelId: String, password: String, uid: String) {
        unlockChannel(channelId, password) { key ->
            channelKey.complete(key)
            if (key != null) {
                listenForMessages(channelId, key, uid)
            } else {
                _error.value = "Couldn't unlock this channel. Check the channel ID and password"
            }
        }
    }

    // Reads the channel, checks the password and returns its key (null if missing or wrong).
    // A legacy channel with a plain-text password is upgraded on the way.
    private fun unlockChannel(chatId: String, password: String, onResult: (ByteArray?) -> Unit) {
        _channelRef.child(chatId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val chatData = try {
                    snapshot.getValue(ChannelMap::class.java)
                } catch (e: Exception) {
                    Log.e("Chatroom", "Malformed channel $chatId", e)
                    null
                }
                if (!snapshot.exists() || chatData == null) {
                    onResult(null)
                    return
                }
                viewModelScope.launch {
                    when (val result = ChannelE2ee.unlock(chatId, chatData, password)) {
                        is ChannelE2ee.Unlock.Ok -> {
                            result.upgrade?.let { upgrade ->
                                _channelRef.child(chatId).updateChildren(upgrade)
                                    .addOnFailureListener { Log.e("Chatroom", "Channel upgrade failed", it) }
                            }
                            onResult(result.key)
                        }

                        ChannelE2ee.Unlock.WrongPassword -> onResult(null)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Chatroom", "Database error: ${error.message}")
                onResult(null)
            }
        })
    }

    fun leaveChatroom(chatId: String) {
        deleteSeenMessages(chatId)
        Log.d("Chatroom", "User left chatroom and seen messages were deleted")
    }


    // so far good
    fun sendVanishingMessage(
        channelId: String, uid: String, messageText: String = "", imageUri: Uri? = null
    ) {
        viewModelScope.launch {
            val messageId =
                _channelRef.child(channelId).child("messages").push().key ?: return@launch
            val timestampSent = System.currentTimeMillis()

            if (imageUri != null) {
                // The picture is encrypted on this device before upload; only the message holds its key.
                val imageRef = try {
                    SecureImages.upload(imageUri)
                } catch (e: E2eeManager.E2eeException) {
                    _error.value = e.message
                    return@launch
                }
                val message = VanishingMessage(
                    messageId = messageId,
                    messageText = "",
                    imageUrl = imageRef,
                    senderId = uid,
                    timestampSent = timestampSent,
                    isSeen = false
                )
                sendEncrypted(channelId, messageId, message)
            } else {
                val message = VanishingMessage(
                    messageId = messageId,
                    messageText = messageText,
                    imageUrl = "",
                    senderId = uid,
                    timestampSent = timestampSent,
                    isSeen = false
                )
                sendEncrypted(channelId, messageId, message)
            }
        }
    }

    // Only the encrypted copy ever reaches Firebase; nothing is sent if encryption fails.
    private suspend fun sendEncrypted(channelId: String, messageId: String, message: VanishingMessage) {
        val key = channelKey.await()
        if (key == null) {
            _error.value = "Message not sent: this channel isn't unlocked"
            return
        }
        val sealed = try {
            ChannelE2ee.sealMessage(channelId, key, message)
        } catch (e: E2eeManager.E2eeException) {
            _error.value = e.message
            return
        }
        saveVanishingMessage(channelId, messageId, sealed)
    }

    // Save message to the database
    private fun saveVanishingMessage(chatId: String, messageId: String, message: VanishingMessage) {
        _channelRef.child(chatId).child("messages").child(messageId).setValue(message)
            .addOnSuccessListener {
                Log.d("Message", "Vanishing message sent: $messageId")
            }.addOnFailureListener {
                Log.e("Message", "Failed to send vanishing message", it)
            }
    }



    // Best effort: the file is encrypted, so a leftover copy on Cloudinary reveals nothing.
    private fun deleteImageFromCloudinary(imageRef: String) {
        val url = SecureImages.urlOf(imageRef)
        val match = Regex("/(image|raw)/upload/(?:v\\d+/)?(.+)$").find(url)
        if (match == null) {
            Log.e("Cloudinary", "Not a Cloudinary URL: $url")
            return
        }
        val resourceType = match.groupValues[1]
        val path = match.groupValues[2]
        // image public IDs exclude the file extension, raw ones include it
        val publicId = if (resourceType == "image") path.substringBeforeLast('.') else path

        viewModelScope.launch(Dispatchers.IO) {
            try {
                MediaManager.get().cloudinary.uploader().destroy(
                    publicId, ObjectUtils.asMap("resource_type", resourceType)
                )
            } catch (e: Exception) {
                Log.e("Cloudinary", "Error deleting image: ${e.message}")
            }
        }
    }


    private fun listenForMessages(chatId: String, key: ByteArray, myUid: String) {
        messagesListener?.let { listener -> messagesRef?.removeEventListener(listener) }

        val ref = _channelRef.child(chatId).child("messages")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                showMessage(chatId, key, snapshot, myUid)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val raw = parseMessage(snapshot) ?: return
                showMessage(chatId, key, snapshot, myUid)
                if (raw.isSeen) {
                    deleteMessageAfterRead(chatId, raw.messageId)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val removedId = snapshot.key ?: return
                _privateMessageList.value = _privateMessageList.value.filterNot { it.messageId == removedId }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                Log.d("PrivateChannel", "Message moved")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("PrivateChannel", "Failed to listen for messages: ${error.message}")
            }
        }
        messagesRef = ref
        messagesListener = listener
        ref.addChildEventListener(listener)
    }

    // Decrypts a message and inserts/replaces it in the list (the screen sorts by time).
    private fun showMessage(chatId: String, key: ByteArray, snapshot: DataSnapshot, myUid: String) {
        val raw = parseMessage(snapshot) ?: return
        val message = raw.copy(messageId = raw.messageId.ifEmpty { snapshot.key.orEmpty() })
        viewModelScope.launch {
            val shown = ChannelE2ee.openMessage(chatId, key, message, myUid)
            val list = _privateMessageList.value.toMutableList()
            val index = list.indexOfFirst { it.messageId == shown.messageId }
            if (index >= 0) list[index] = shown else list.add(0, shown)
            _privateMessageList.value = list
        }
    }

    private fun parseMessage(snapshot: DataSnapshot): VanishingMessage? = try {
        snapshot.getValue(VanishingMessage::class.java)
    } catch (e: Exception) {
        Log.e("PrivateChannel", "Malformed message ${snapshot.key}", e)
        null
    }

    override fun onCleared() {
        messagesListener?.let { listener -> messagesRef?.removeEventListener(listener) }
        messagesListener = null
        messagesRef = null
        super.onCleared()
    }


    private fun deleteMessageAfterRead(chatId: String, messageId: String) {
        _channelRef.child(chatId).child("messages").child(messageId).removeValue()
            .addOnSuccessListener {
                Log.d("Message", "Message deleted after reading: $messageId")
            }.addOnFailureListener {
                Log.e("Message", "Failed to delete message after reading", it)
            }
    }

    fun requestId(onResult: (String) -> Unit) {
        val channelId = generateUniqueId()

        _channelRef.child(channelId).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                requestId(onResult)
            } else {
                onResult(channelId)
            }
        }.addOnFailureListener {
            Log.d("RequestIdError", "Failed to check group ID existence: ${it.message}")
        }
    }

    fun markMessagesAsSeen(chatId: String, uid: String) {
        _channelRef.child(chatId).child("messages")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { child ->
                        val message = child.getValue(VanishingMessage::class.java)
                        message?.let {
                            if (it.senderId != uid && !it.isSeen) {
                                // Update message to seen
                                _channelRef.child(chatId).child("messages").child(it.messageId)
                                    .child("isSeen").setValue(true)
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Chatroom", "Failed to mark messages as seen: ${error.message}")
                }
            })
    }

    private fun deleteSeenMessages(chatId: String) {
        _channelRef.child(chatId).child("messages")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { child ->
                        val message = child.getValue(VanishingMessage::class.java)
                        message?.let {
                            if (it.isSeen) {
                                _channelRef.child(chatId).child("messages").child(it.messageId)
                                    .removeValue()
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Chatroom", "Failed to delete seen messages: ${error.message}")
                }
            })
    }


    private fun generateUniqueId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(10)
    }

    fun convertTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("hh:mm a  dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun deleteChannel(channelId: String, callback: () -> Unit) {
        _channelRef.child(channelId).removeValue().addOnSuccessListener {
            callback()
        }
    }

    fun deleteIndividualMessage(
        channelId: String, message: VanishingMessage, onComplete: (Boolean) -> Unit
    ) {
        _channelRef.child(channelId).child("messages").child(message.messageId).removeValue()
            .addOnSuccessListener {
                if (message.imageUrl.isNotEmpty()) deleteImageFromCloudinary(message.imageUrl)
                onComplete(true)
            }
            .addOnFailureListener { onComplete(false) }
    }

}

data class ChannelMap(
    val createdAt: Long = 0L, // Store as Long
    // legacy channels only: the password in plain text (removed when the channel is upgraded)
    val setPassword: String = "",
    // end-to-end encryption (see e2ee/ChannelE2ee): PBKDF2 salt/iterations and a password verifier
    val v: Int = 0,
    val salt: String = "",
    val iterations: Int = 0,
    val verifier: String = ""
)

data class VanishingMessage(
    val messageId: String = "", // Unique ID for the message
    val messageText: String = "", // Text content of the message
    val imageUrl: String = "", // URL for media (if any)
    val timestampSent: Long = System.currentTimeMillis(), // When the message was sent
    var senderId: String = "", // Whether the message has been read
    var isSeen: Boolean = false, // Whether the message has been deleted
    // end-to-end encryption (see e2ee/ChannelE2ee): v = 0 means a legacy plaintext message
    val v: Int = 0,
    val kind: String = "", // "Text" or "Image": which field the decrypted content belongs to
    val ct: String = "",
    val signKey: String = "",
    val sig: String = ""
)