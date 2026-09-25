package com.example.clubmate.viewmodel

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clubmate.db.Routes
import com.example.clubmate.db.UserState
import com.example.clubmate.e2ee.E2eeManager
import com.example.clubmate.e2ee.SecureImages
import com.example.clubmate.screens.MessageStatus
import com.example.clubmate.util.MessageType
import com.example.clubmate.util.chat.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class ChatViewModel : ViewModel() {

    // db reference
    private val _db = FirebaseDatabase.getInstance()
    private val chatRef = _db.getReference("chat")
    private val userRef = _db.getReference("user")
    private val receiverId = FirebaseAuth.getInstance().uid

    // message list
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages


    private val _unreadMessages = MutableStateFlow<List<Message>>(emptyList())
    val unreadMessages: StateFlow<List<Message>> = _unreadMessages

    // chats lists
    private val _chats = MutableStateFlow<List<Chats>>(emptyList())
    val chats: StateFlow<List<Chats>> = _chats

    // set when a message could not be encrypted/sent; the chat screen shows it as a toast
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    // active Firebase listeners, so they can be detached when a chat is closed
    private var chatsListener: ChildEventListener? = null
    private val lastMessageListeners = mutableMapOf<String, ValueEventListener>()
    private var activeChatId: String? = null
    private var messagesRef: DatabaseReference? = null
    private var messagesListener: ChildEventListener? = null
    private var incognitoRef: DatabaseReference? = null
    private var incognitoListener: ValueEventListener? = null
    private var incognitoJob: Job? = null

    // must stay below the properties above, which listenForChats() uses
    init {
        listenForChats()
    }

    // userdata
    var userState by mutableStateOf<UserState>(UserState.Success(null))
        private set

    // fetches he searched result
    var user by mutableStateOf<Routes.UserModel?>(Routes.UserModel())


    private suspend fun fetchUsers(senderId: String, receiverId: String): List<Routes.UserModel> {
        val users = mutableListOf<Routes.UserModel>()
        suspend fun fetchUser(uid: String): Routes.UserModel? = suspendCoroutine { continuation ->
            fetchUserByUid(uid) { user ->
                continuation.resume(user)
            }
        }

        val user1 = fetchUser(senderId)
        val user2 = fetchUser(receiverId)

        user1?.let { users.add(it) }
        user2?.let { users.add(it) }

        return users
    }


    // chat id and chat
    private fun generateChatID(senderId: String, receiverId: String): String {
        val sortedIds = listOf(senderId, receiverId).sorted()
        val chatID = "${sortedIds[0]}+${sortedIds[1]}"

        return chatID
    }

    fun deleteChatId(chatId: String, uid: String, onSuccess: (Boolean) -> Unit) {

        chatRef.child(chatId).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val participants = chatId.split("+")
                if (participants.contains(uid)) {
                    onSuccess(true)
                    chatRef.child(chatId).removeValue()
                    viewModelScope.launch { E2eeManager.forgetChat(chatId, uid) }

                } else {
                    onSuccess(false)
                }
            } else {
                onSuccess(false)
            }
        }.addOnFailureListener {
            onSuccess(false)
        }
    }


    private fun getChatId(receiverId: String, senderId: String): String {
        var chatId = generateChatID(receiverId = receiverId, senderId = senderId)

        chatRef.child(chatId).get().addOnSuccessListener { snapshot ->
            chatId = if (snapshot.exists()) {
                snapshot.key.toString()
            } else {
                snapshot.key.toString()
            }
        }.addOnFailureListener {
            Log.e("Firebase", "Error fetching chats: ${it.message}")
        }
        return chatId
    }

    fun initiateChat(
        senderId: String, receiverId: String, message: String = "", onClick: (String) -> Unit
    ) {
        val chatId = getChatId(senderId, receiverId)

        viewModelScope.launch {
            val users = fetchUsers(senderId, receiverId)

            if (users.size == 2) {
                onClick(chatId)
                chatRef.child(chatId).child("participants").setValue(users).addOnCompleteListener {
                    Log.d("failed to initiate chat", "initiateChat: ")
                }.addOnFailureListener {
                    Log.d("failed to initiate chat", "initiateChat: ")
                }
            } else {
                Log.d("failed to initiate chat", "initiateChat: ")
            }
        }
    }


    // message

    fun sendMessage(
        chatId: String,
        senderId: String,
        receiverId: String,
        messageText: String = "",
        imageUri: Uri? = null,
    ) {

        viewModelScope.launch {
            val messageId = chatRef.child(chatId).push().key ?: return@launch
            val timestamp = System.currentTimeMillis()

            if (imageUri != null) {
                // The picture is encrypted on this device before upload; only the message holds its key.
                val imageRef = try {
                    SecureImages.upload(imageUri)
                } catch (e: E2eeManager.E2eeException) {
                    _sendError.value = e.message
                    return@launch
                }
                val messageData = Message(
                    messageId = messageId,
                    senderId = senderId,
                    receiverId = receiverId,
                    messageText = "",
                    imageRef = imageRef,
                    timestamp = timestamp,
                    messageType = MessageType.Image,
                    status = MessageStatus.SENDING
                )
                sendEncrypted(chatId, messageData)
            } else {
                val messageData = Message(
                    messageId = messageId,
                    senderId = senderId,
                    receiverId = receiverId,
                    messageText = messageText,
                    imageRef = "",
                    timestamp = timestamp,
                    messageType = MessageType.Text,
                    status = MessageStatus.SENDING
                )
                sendEncrypted(chatId, messageData)
            }

        }
    }

    // Only the encrypted copy ever reaches Firebase; nothing is sent if encryption fails.
    private suspend fun sendEncrypted(chatId: String, messageData: Message) {
        val sealed = try {
            E2eeManager.sealMessage(chatId, messageData)
        } catch (e: E2eeManager.E2eeException) {
            Log.e("E2EE", "Message not sent: ${e.message}")
            _sendError.value = e.message
            return
        }
        saveMessageToDatabase(chatId, sealed.messageId, sealed)
        updateLastMessage(chatId, sealed)
    }

    fun clearSendError() {
        _sendError.value = null
    }

    private fun saveMessageToDatabase(
        chatId: String, messageId: String, messageData: Message
    ) {

        chatRef.child(chatId).child("messages").child(messageId).setValue(messageData)
            .addOnSuccessListener {
                Log.d("Success", "Activity added successfully")
            }.addOnFailureListener { e ->
                Log.e("Failure", "Failed to add activity: ${e.message}")
            }
    }


    private fun updateLastMessage(chatId: String, messageData: Message) {
        chatRef.child(chatId).child("msg").child("last").setValue(messageData)
            .addOnFailureListener {
                Log.e("Message", "Error updating last message")
            }
    }


    fun receiveMessage(chatId: String?) {

        if (chatId.isNullOrEmpty()) return
        val myUid = FirebaseAuth.getInstance().uid ?: return

        // Only one chat is open at a time: detach the previous chat's listener first.
        stopReceivingMessages()
        activeChatId = chatId
        chatId.split("+").firstOrNull { it != myUid }?.let { E2eeManager.watchPeer(it) }

        val ref = chatRef.child(chatId).child("messages")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                showMessage(chatId, snapshot, myUid)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                showMessage(chatId, snapshot, myUid)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val removedId = snapshot.key ?: return
                _messages.value = _messages.value.filterNot { it.messageId == removedId }
                // deleted for everyone: also drop the decrypted copy kept on this device
                viewModelScope.launch { E2eeManager.forgetMessage(chatId, myUid, removedId) }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                Log.d("TAG", "onChildMoved: ")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Message", "Error receiving message: ${error.message}")
            }
        }
        messagesRef = ref
        messagesListener = listener
        ref.addChildEventListener(listener)
    }

    // Decrypts a message from Firebase and inserts/replaces it in the list (kept in time order).
    private fun showMessage(chatId: String, snapshot: DataSnapshot, myUid: String) {
        val raw = try {
            snapshot.getValue(Message::class.java)
        } catch (e: Exception) {
            Log.e("Message", "Malformed message ${snapshot.key}", e)
            null
        } ?: return
        val message = raw.copy(messageId = raw.messageId.ifEmpty { snapshot.key.orEmpty() })

        viewModelScope.launch {
            val shown = E2eeManager.openMessage(chatId, message, myUid)
            if (activeChatId != chatId) return@launch // user left this chat meanwhile

            val list = _messages.value.toMutableList()
            val index = list.indexOfFirst { it.messageId == shown.messageId }
            if (index >= 0) {
                list[index] = shown
            } else {
                list.add(shown)
                list.sortBy { it.timestamp }
            }
            _messages.value = list
        }
    }

    private fun stopReceivingMessages() {
        messagesListener?.let { listener -> messagesRef?.removeEventListener(listener) }
        messagesListener = null
        messagesRef = null
        activeChatId = null
    }


    fun convertTimestampToDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("hh:mm a  dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    // chatId: the chat being closed. If another chat was opened in the meantime, keep that one alive.
    fun clearMessage(chatId: String? = null) {
        if (chatId != null && activeChatId != null && activeChatId != chatId) return
        stopReceivingMessages()
        stopReceivingIncognito()
        _messages.value = emptyList()
    }

    private fun listenForChats() {
        val myUid = receiverId ?: return

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val chatId = snapshot.key ?: return
                if (!isMyChat(chatId, myUid)) return

                if (_chats.value.none { it.chatId == chatId }) {
                    _chats.value = _chats.value + Chats(chatId = chatId)
                }
                getParticipants(chatId) { prt ->
                    updateChat(chatId) { it.copy(participants = prt) }
                }
                watchLastMessage(chatId, myUid)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // the last message has its own listener; only participants need refreshing here
                val chatId = snapshot.key ?: return
                if (!isMyChat(chatId, myUid)) return
                getParticipants(chatId) { prt ->
                    updateChat(chatId) { it.copy(participants = prt) }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val chatId = snapshot.key ?: return
                lastMessageListeners.remove(chatId)?.let { listener ->
                    chatRef.child(chatId).child("msg").child("last").removeEventListener(listener)
                }
                _chats.value = _chats.value.filterNot { it.chatId == chatId }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatListener", "Error listening for chats: ${error.message}")
            }
        }
        chatsListener = listener
        chatRef.addChildEventListener(listener)
    }

    private fun isMyChat(chatId: String, myUid: String): Boolean {
        val participants = chatId.split("+")
        return participants.size == 2 && participants.contains(myUid)
    }

    private fun updateChat(chatId: String, transform: (Chats) -> Chats) {
        _chats.value = _chats.value
            .map { if (it.chatId == chatId) transform(it) else it }
            .sortedByDescending { it.lastMessage?.timestamp }
    }

    fun getParticipants(
        chatId: String, onResult: (List<Routes.UserModel>) -> Unit
    ) {

        chatRef.child(chatId).child("participants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val participants = mutableListOf<Routes.UserModel>()
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val participant = child.getValue(Routes.UserModel::class.java)
                            if (participant != null) {
                                participants.add(participant)
                            }
                        }
                    }
                    onResult(participants)
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(emptyList())
                }
            })
    }

    // Keeps the chat list preview in sync with chat/{chatId}/msg/last, decrypted for display.
    private fun watchLastMessage(chatId: String, myUid: String) {
        if (lastMessageListeners.containsKey(chatId)) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lastMsg = try {
                    snapshot.getValue(Message::class.java)
                } catch (e: Exception) {
                    Log.e("getLastMessage", "Malformed last message in $chatId", e)
                    null
                }
                if (lastMsg == null) {
                    updateChat(chatId) { it.copy(lastMessage = Message()) }
                    return
                }
                viewModelScope.launch {
                    val shown = E2eeManager.openMessage(chatId, lastMsg, myUid)
                    updateChat(chatId) { it.copy(lastMessage = shown) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("getLastMessage", "Error fetching last message: ${error.message}")
            }
        }
        lastMessageListeners[chatId] = listener
        chatRef.child(chatId).child("msg").child("last").addValueEventListener(listener)
    }

    // find user section

    fun findUser(search: String) {
        if (search.isEmpty()) {
            userState = UserState.Error("Query cannot be empty")
            return
        }

        userState = UserState.Loading
        viewModelScope.launch {
            find(search) { result ->
                if (result != null) {
                    user = result
                    userState = UserState.Success(result)
                } else {
                    userState = UserState.Error("User not found")
                }
            }
        }
    }


    fun emptyUser() {
        user = null
        userState = UserState.Success(null)
    }

    fun setUserEmpty(txt: String) {
        user = null
        userState = UserState.Error(txt)
    }

    fun fetchUserByUid(uid: String, callback: (Routes.UserModel?) -> Unit) {

        userRef.child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val userInfo = snapshot.getValue(Routes.UserModel::class.java)
                    callback(userInfo)
                } else {
                    callback(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                callback(null)
            }
        })
    }


    private fun find(search: String, onResult: (Routes.UserModel?) -> Unit) {

        userRef.orderByChild("email").equalTo(search)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (childSnapshot in snapshot.children) {
                            val usr = childSnapshot.getValue(Routes.UserModel::class.java)
                            onResult(usr)
                            return
                        }
                    } else {
                        userRef.orderByChild("username").equalTo(search)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    if (snapshot.exists()) {
                                        for (childSnapshot in snapshot.children) {
                                            val usr =
                                                childSnapshot.getValue(Routes.UserModel::class.java)
                                            onResult(usr)
                                            return
                                        }
                                    } else {
                                        userState = UserState.Error("User doesn't exist")
                                        onResult(null)
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    userState = UserState.Error(error.message)
                                    onResult(null)
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    userState = UserState.Error(error.message)
                    onResult(null)
                }
            })
    }


    // delete messages


    fun deleteIndividualMessage(chatId: String, messageId: String) {

        chatRef.child(chatId).child("messages").child(messageId).removeValue()
            .addOnCompleteListener {
                // onResult()
                Log.d("TAG", "deleteIndividualMessage: succ")
            }.addOnFailureListener {
                Log.d("TAG", "deleteIndividualMessage: failed")
            }
    }


    fun clearChats() {
        _chats.value = emptyList()
    }

    fun deleteMyMessages(chatId: String, senderId: String, onComplete: (Boolean) -> Unit) {
        chatRef.child(chatId).child("messages").get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val updates = mutableMapOf<String, Any?>()

                snapshot.children.forEach { messageSnapshot ->
                    val message = messageSnapshot.getValue(Message::class.java)
                    if (message != null && message.senderId == senderId) {
                        updates[messageSnapshot.key!!] = null
                    }
                }

                if (updates.isNotEmpty()) {
                    chatRef.child(chatId).child("messages").updateChildren(updates)
                        .addOnSuccessListener {
                            deleteLastMessage(chatId, senderId)
                            onComplete(true)
                        }
                        .addOnFailureListener {
                            onComplete(false)
                        }
                } else {
                    deleteLastMessage(chatId, senderId)
                    onComplete(false)
                }
            } else {
                deleteLastMessage(chatId, senderId)
                onComplete(false)
            }
        }.addOnFailureListener { e ->
            onComplete(false)
        }
    }

    private fun deleteLastMessage(chatId: String, senderId: String) {
        chatRef.child(chatId).child("msg").child("last").get().addOnSuccessListener { snapshot ->
            val lastMessage = snapshot.getValue(Message::class.java)

            if (lastMessage != null && lastMessage.senderId == senderId) {
                snapshot.ref.removeValue().addOnSuccessListener {
                    Log.d("DeleteMessage", "Last message deleted successfully")
                }.addOnFailureListener { e ->
                    Log.e("DeleteMessage", "Failed to delete last message: ${e.message}")
                }
            } else {
                Log.d("DeleteMessage", "Last message is not sent by the user or does not exist")
            }
        }.addOnFailureListener { e ->
            Log.e("DeleteMessage", "Error fetching last message: ${e.message}")
        }
    }


    // incognito

    private val _incognitoMessages = MutableStateFlow<List<IncognitoMessage>>(emptyList())
    val incognitoMessages: StateFlow<List<IncognitoMessage>> = _incognitoMessages

    fun sendIncognitoMessage(
        chatId: String,
        receiverId: String,
        senderId: String,
        messageText: String
    ) {
        val messageId = chatRef.child(chatId).child("incognito").push().key ?: return
        val timestamp = System.currentTimeMillis()

        val messageData = IncognitoMessage(
            messageText = messageText,
            senderId = senderId,
            receiverId = receiverId,
            timestamp = timestamp,
            messageId = messageId
        )

        viewModelScope.launch {
            val sealed = try {
                E2eeManager.sealIncognito(chatId, messageData)
            } catch (e: E2eeManager.E2eeException) {
                Log.e("E2EE", "Incognito message not sent: ${e.message}")
                _sendError.value = e.message
                return@launch
            }
            chatRef.child(chatId).child("incognito").child(messageId).setValue(sealed)
                .addOnSuccessListener {
                    Log.d("Success", "Activity added successfully")
                }.addOnFailureListener { e ->
                    Log.e("Failure", "Failed to add activity: ${e.message}")
                }
        }
    }


    fun receiveIncognitoMessage(chatId: String) {
        val myUid = FirebaseAuth.getInstance().uid ?: return
        stopReceivingIncognito()

        val ref = chatRef.child(chatId).child("incognito")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<IncognitoMessage>()
                for (childSnapshot in snapshot.children) {
                    try {
                        childSnapshot.getValue(IncognitoMessage::class.java)?.let {
                            messages.add(it)
                        }
                    } catch (e: Exception) {
                        Log.e("Incognito", "Malformed message ${childSnapshot.key}", e)
                    }
                }
                // each snapshot is the full list, so only the newest decryption run matters
                incognitoJob?.cancel()
                incognitoJob = viewModelScope.launch {
                    _incognitoMessages.value = messages
                        .map { E2eeManager.openIncognito(chatId, it, myUid) }
                        .sortedBy { it.timestamp }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d("TAG", "onCancelled: error")
            }
        }
        incognitoRef = ref
        incognitoListener = listener
        ref.addValueEventListener(listener)
    }

    private fun stopReceivingIncognito() {
        incognitoListener?.let { listener -> incognitoRef?.removeEventListener(listener) }
        incognitoListener = null
        incognitoRef = null
        incognitoJob?.cancel()
        incognitoJob = null
    }

    override fun onCleared() {
        stopReceivingMessages()
        stopReceivingIncognito()
        chatsListener?.let { chatRef.removeEventListener(it) }
        lastMessageListeners.forEach { (chatId, listener) ->
            chatRef.child(chatId).child("msg").child("last").removeEventListener(listener)
        }
        lastMessageListeners.clear()
        super.onCleared()
    }

    fun deleteIncognitoMessage(chatId: String) {
        chatRef.child(chatId).child("incognito").removeValue()
            .addOnSuccessListener {
                Log.d("TAG", "onCancelled: error")
            }.addOnFailureListener {
                Log.d("TAG", "onCancelled: error")
            }
    }


}

data class IncognitoMessage(
    val messageText: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val timestamp: Long = 0L,
    val messageId: String = "",
    // end-to-end encryption, same format as Message
    val v: Int = 0,
    val ct: String = "",
    val senderKey: String = "",
    val receiverKey: String = "",
    val dh: String = "",
    val pn: Int = 0,
    val n: Int = 0,
    val preIk: String = "",
    val preEk: String = "",
    val preSpk: Int = 0
)


data class Chats(
    val chatId: String = "",
    var lastMessage: Message? = Message(),
    var participants: List<Routes.UserModel> = listOf(Routes.UserModel())
)