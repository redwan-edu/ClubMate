package com.example.clubmate.util


enum class Category {
    Admin, General, President, VicePresident, Treasurer,
}

enum class MessageType {
    Text, Audio, Video, Image
}

/** Delivery state of a chat message. */
enum class MessageStatus {
    SEEN, DELIVERED, FAILED, SENDING
}

/** Kinds of posts on a group's notice board. */
enum class EventCategory {
    Meeting, Notice, Event
}
