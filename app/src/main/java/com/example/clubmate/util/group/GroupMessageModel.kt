package com.example.clubmate.util.group

data class GroupMessage(
    val messageId: String = "",
    val senderId: String = "",
    val messageText: String = "",
    val imageRef: String = "",
    val timestamp: Long = 0,
    // end-to-end encryption (see e2ee/GroupE2ee): v = 0 means a legacy plaintext message
    val v: Int = 0,
    val epochId: String = "",
    val ct: String = "",
    val signKey: String = "",
    val sig: String = ""
)
