package com.example.clubmate.util.chat

import com.example.clubmate.screens.MessageStatus
import com.example.clubmate.util.MessageType


data class Message(
    val messageId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val messageText: String = "",
    val imageRef: String = "",
    val timestamp: Long = 0,
    val seen: Boolean = false,
    val status: MessageStatus = MessageStatus.SENDING,
    val messageType: MessageType = MessageType.Text,
    // end-to-end encryption (see e2ee/E2eeManager): v = 0 plain text (legacy), 1 static DH (legacy), 2 Double Ratchet
    val v: Int = 0,
    val ct: String = "",
    val senderKey: String = "",
    val receiverKey: String = "",
    // v = 2 (Double Ratchet): ratchet header and, for a session's first messages, the X3DH prekey block
    val dh: String = "",
    val pn: Int = 0,
    val n: Int = 0,
    val preIk: String = "",
    val preEk: String = "",
    val preSpk: Int = 0
)
