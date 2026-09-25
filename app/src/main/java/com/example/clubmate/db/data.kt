package com.example.clubmate.db

import kotlinx.serialization.Serializable

@Serializable
class Routes {


    @Serializable
    object Splash

    @Serializable
    object Login


    @Serializable
    object Register


    // screens
    @Serializable
    object Main


    @Serializable
    data class UserModel(
        val uid: String = "",
        val email: String = "",
        val username: String = "",
        val phone: String = "",
        val chatID: String = "",
        val publicKey: String = "", // X25519 public key (Base64) used for end-to-end encryption
        val photoUrl: String = "",
        val signingKey: String = "" // Ed25519 public key (Base64) used to sign group/channel messages
    )

    @Serializable
    data class UserDetails(
        val email: String = "",
        val uid: String = "",
        val username: String = "",
        val chatID: String = "",
        val phone: String = ""
    )

    @Serializable
    data class PrivateChat(
        val channelId: String,
        val uid: String,
        val password: String
    )

    @Serializable
    object CreateChannel


    // groups

    @Serializable
    data class GroupModel(
        val user: String = "", val grpId: String = ""
    )

    @Serializable
    object CreateGroup


    @Serializable
    data class GrpDetails(
        val grpId: String = "",
        val description: String = "",
        val photoUrl: String = "",
        val grpName: String = "",
        val createdAt: Long = 0L,
        val createdBy: String = ""
    )


    @Serializable
    data class AddUserToGroup(
        val grpId: String
    )

    @Serializable
    data class ViewAllUser(
        val grpId: String
    )

    @Serializable
    data class Timeline(
        val grpId: String,
        val uid: String
    )

    @Serializable
    data class NewPost(val grpId: String)

    @Serializable
    data class Console(
        val grpId: String,
        val grpName: String,
        val image: String = "",
        val description: String = "",
        val uid: String
    )


    @Serializable
    data class GroupUserDetails(
        val grpId: String, val grpName: String = "",
        val userId: String, val currentUserId: String
    )


    // settings
    @Serializable
    object Accounts

    @Serializable
    object Security

    @Serializable
    object Developers

}


sealed class UserState {
    data object Loading : UserState()
    data class Success(val user: Routes.UserModel?) : UserState()
    data class Error(val msg: String) : UserState()
}

sealed class GroupState {
    data object Loading : GroupState()
    data class Success(val group: Routes.GrpDetails?) : GroupState()
    data class Error(val msg: String) : GroupState()
}


sealed class Status {
    data object Authenticated : Status()
    data object NotAuthenticated : Status()
    data object Loading : Status()
    data class Error(val message: String) : Status()
}

