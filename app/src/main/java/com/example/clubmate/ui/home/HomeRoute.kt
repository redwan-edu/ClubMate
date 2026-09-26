package com.example.clubmate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.GroupState
import com.example.clubmate.db.Routes
import com.example.clubmate.db.UserState
import com.example.clubmate.ui.components.AppTextField
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.InlineError
import com.example.clubmate.ui.components.ListDivider
import com.example.clubmate.ui.components.PrimaryButton
import com.example.clubmate.ui.components.SettingsRow
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.getInternetConnectionStatus
import com.example.clubmate.viewmodel.AuthViewModel
import com.example.clubmate.viewmodel.ChatViewModel
import com.example.clubmate.viewmodel.GroupMembership
import com.example.clubmate.viewmodel.GroupViewmodel

// ---------------------------------------------------------------- sheet contents

/** A found person or group, shown as a card with one action. */
@Composable
private fun ResultCard(
    title: String,
    subtitle: String,
    avatarName: String,
    photoUrl: String?,
    isGroup: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(avatarName, imageUrl = photoUrl, size = 48.dp, icon = if (isGroup) Icons.Rounded.Groups else null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
fun NewChatSheetContent(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    found: Routes.UserModel?,
    error: String?,
    onSearch: () -> Unit,
    onStartChat: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("New chat", style = MaterialTheme.typography.titleLarge)
        Text(
            "Find someone by their email or username.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            query, onQueryChange, "Email or username",
            leadingIcon = Icons.Rounded.Search, keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Search, onImeAction = onSearch
        )
        if (error != null) InlineError(error)
        if (found != null) {
            ResultCard(found.username.ifBlank { found.email }, found.email, found.username.ifBlank { found.email }, found.photoUrl, isGroup = false)
            PrimaryButton("Message ${found.username.ifBlank { "them" }.substringBefore(' ')}", onStartChat)
        } else {
            PrimaryButton("Search", onSearch, loading = searching, enabled = query.isNotBlank())
        }
    }
}

/**
 * A found group's status for the signed-in person, shown in the group sheet. [Checking] is the
 * server look-up that runs right after a group is found, so the sheet never shows "Ask to join"
 * for a group the person is already in or has already asked to join.
 */
enum class JoinState { Idle, Checking, NotConnected, Sending, Pending, Member }

@Composable
fun GroupActionsSheetContent(
    groupId: String,
    onGroupIdChange: (String) -> Unit,
    searching: Boolean,
    found: Routes.GrpDetails?,
    error: String?,
    joinState: JoinState,
    onCreateGroup: () -> Unit,
    onSearch: () -> Unit,
    onRequestJoin: () -> Unit,
    onOpenGroup: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text("Groups", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        SettingsRow(Icons.Rounded.AddCircleOutline, "Create a group", onCreateGroup, subtitle = "Start a group for your club and invite members")
        ListDivider()
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Join with a group ID", style = MaterialTheme.typography.titleMedium)
            AppTextField(
                groupId, onGroupIdChange, "Group ID",
                leadingIcon = Icons.Rounded.Tag, imeAction = ImeAction.Search, onImeAction = onSearch
            )
            if (error != null) InlineError(error)
            if (found == null) {
                PrimaryButton("Find group", onSearch, loading = searching, enabled = groupId.isNotBlank())
            } else {
                ResultCard(found.grpName, found.description, found.grpName, found.photoUrl, isGroup = true)
                when (joinState) {
                    JoinState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    JoinState.Pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Request sent. You'll see the group here once an admin approves it.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    JoinState.Member -> PrimaryButton("Open group", onOpenGroup)
                    else -> PrimaryButton("Ask to join", onRequestJoin, loading = joinState == JoinState.Sending)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- route

/** The home screen with its data: chat and group lists, search, and the two action sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    myUid: String,
    authViewModel: AuthViewModel,
    chatViewModel: ChatViewModel,
    groupViewModel: GroupViewmodel,
    onOpenChat: (Routes.UserModel) -> Unit,
    onOpenGroup: (grpId: String) -> Unit,
    onCreateGroup: () -> Unit,
    channelsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HomeTab.Chats) }
    var query by rememberSaveable { mutableStateOf("") }
    var online by remember { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<HomeTab?>(null) }

    val me by authViewModel.userData.collectAsState()
    val chats by chatViewModel.chats.collectAsState()
    val groups by groupViewModel.groupsList.collectAsState()

    // The group list is a snapshot, so refresh it every time home is shown.
    LaunchedEffect(myUid) { groupViewModel.listenForGroups(myUid) }
    LaunchedEffect(context) { getInternetConnectionStatus(context).collect { online = it } }

    val chatSummaries = chats.mapNotNull { chat ->
        val peer = chat.participants.firstOrNull { it.uid.isNotEmpty() && it.uid != myUid } ?: return@mapNotNull null
        val last = chat.lastMessage
        val isPhoto = last != null && last.imageRef.isNotEmpty()
        val text = when {
            last == null || (last.messageText.isEmpty() && !isPhoto) -> "No messages yet"
            isPhoto -> "Photo"
            else -> last.messageText
        }
        ChatSummary(
            chatId = chat.chatId,
            peerUid = peer.uid,
            name = peer.username.ifBlank { peer.email },
            email = peer.email,
            photoUrl = peer.photoUrl,
            preview = if (last != null && last.senderId == myUid && text != "No messages yet") "You: $text" else text,
            isPhoto = isPhoto,
            timestamp = last?.timestamp ?: 0L
        )
    }.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true)
    }.sortedByDescending { it.timestamp }

    val groupSummaries = groups.map { group ->
        val message = group.lastActivity.message
        val isPhoto = message.imageRef.isNotEmpty()
        GroupSummary(
            grpId = group.grpId,
            name = group.grpName,
            photoUrl = group.photoUrl,
            preview = if (isPhoto) "Photo" else message.messageText,
            isPhoto = isPhoto,
            timestamp = message.timestamp
        )
    }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .sortedByDescending { it.timestamp }

    HomeScreen(
        tab = tab,
        onTabChange = { tab = it; query = "" },
        online = online,
        chats = chatSummaries,
        groups = groupSummaries,
        query = query,
        onQueryChange = { query = it },
        onOpenChat = {
            onOpenChat(Routes.UserModel(uid = it.peerUid, email = it.email, username = it.name, chatID = it.chatId, photoUrl = it.photoUrl))
        },
        onOpenGroup = { onOpenGroup(it.grpId) },
        onNewChat = { sheet = HomeTab.Chats },
        onGroupActions = { sheet = HomeTab.Groups },
        channelsContent = channelsContent,
        settingsContent = settingsContent
    )

    if (sheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                sheet = null
                chatViewModel.emptyUser()
                groupViewModel.emptyGroup()
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(Modifier.imePadding().navigationBarsPadding()) {
                when (sheet) {
                    HomeTab.Chats -> NewChatSheet(myUid, chatViewModel) { user ->
                        sheet = null
                        onOpenChat(user)
                    }

                    else -> GroupSheet(
                        myUid = myUid,
                        myEmail = me?.email.orEmpty(),
                        myName = me?.username.orEmpty(),
                        groupViewModel = groupViewModel,
                        onCreateGroup = { sheet = null; onCreateGroup() },
                        onOpenGroup = { sheet = null; onOpenGroup(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NewChatSheet(myUid: String, chatViewModel: ChatViewModel, onStarted: (Routes.UserModel) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val state = chatViewModel.userState
    val found = (state as? UserState.Success)?.user?.takeIf { it.uid.isNotEmpty() }
    val error = when {
        found?.uid == myUid -> "That's you. Search for someone else."
        state is UserState.Error && state.msg.isNotBlank() && state.msg != "Query cannot be empty" -> "No one on ClubMate matches \"${query.trim()}\"."
        else -> null
    }

    NewChatSheetContent(
        query = query,
        onQueryChange = { query = it; chatViewModel.emptyUser() },
        searching = state is UserState.Loading,
        found = found?.takeIf { it.uid != myUid },
        error = error,
        onSearch = { if (query.isNotBlank()) chatViewModel.findUser(query.trim()) },
        onStartChat = {
            val user = found ?: return@NewChatSheetContent
            if (starting) return@NewChatSheetContent
            starting = true
            chatViewModel.initiateChat(senderId = myUid, receiverId = user.uid) { chatId ->
                chatViewModel.emptyUser()
                onStarted(user.copy(chatID = chatId))
            }
        }
    )
}

@Composable
private fun GroupSheet(
    myUid: String,
    myEmail: String,
    myName: String,
    groupViewModel: GroupViewmodel,
    onCreateGroup: () -> Unit,
    onOpenGroup: (String) -> Unit
) {
    var groupId by rememberSaveable { mutableStateOf("") }
    var joinState by remember { mutableStateOf(JoinState.Idle) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val state = groupViewModel.groupState
    val found = (state as? GroupState.Success)?.group?.takeIf { it.grpId.isNotEmpty() }
    val error = sendError ?: when {
        state is GroupState.Error && state.msg.isNotBlank() && state.msg != "Query cannot be empty" -> "No group has this ID. Check it with an admin."
        else -> null
    }

    // A group was found: ask the server whether this person is already in it or already asked,
    // rather than trusting the (possibly stale) list of groups already loaded on this screen.
    LaunchedEffect(found?.grpId) {
        val group = found
        if (group != null) {
            joinState = JoinState.Checking
            groupViewModel.checkGroupMembership(group.grpId, myUid) { membership ->
                joinState = when (membership) {
                    GroupMembership.Member -> JoinState.Member
                    GroupMembership.Requested -> JoinState.Pending
                    GroupMembership.None -> JoinState.NotConnected
                }
            }
        }
    }

    GroupActionsSheetContent(
        groupId = groupId,
        onGroupIdChange = { groupId = it.trim(); joinState = JoinState.Idle; sendError = null; groupViewModel.emptyGroup() },
        searching = state is GroupState.Loading,
        found = found,
        error = error,
        joinState = joinState,
        onCreateGroup = onCreateGroup,
        onSearch = { if (groupId.isNotBlank()) groupViewModel.findGroup(groupId) },
        onRequestJoin = {
            val group = found ?: return@GroupActionsSheetContent
            joinState = JoinState.Sending
            groupViewModel.sendJoinRequest(uid = myUid, email = myEmail, username = myName, grpId = group.grpId) { ok ->
                joinState = if (ok) JoinState.Pending else JoinState.NotConnected
                sendError = if (ok) null else "Couldn't send the request. Try again."
            }
        },
        onOpenGroup = { found?.let { onOpenGroup(it.grpId) } }
    )
}

// ---------------------------------------------------------------- previews

/** Draws sheet content the way ModalBottomSheet shows it, over a dimmed home screen. */
@Composable
private fun SheetPreviewFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HomeChatsPreview()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .padding(bottom = 16.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
            content()
        }
    }
}

@Preview
@Composable
fun NewChatSheetPreview() = ClubMateTheme {
    SheetPreviewFrame {
        NewChatSheetContent(
            "nadia@university.edu", {}, false,
            Routes.UserModel(uid = "n", email = "nadia@university.edu", username = "Nadia Islam"),
            null, {}, {}
        )
    }
}

@Preview
@Composable
fun GroupSheetPreview() = ClubMateTheme {
    SheetPreviewFrame {
        GroupActionsSheetContent(
            "93beefc4b3bf4d42a1d1", {}, false,
            Routes.GrpDetails(grpId = "93beefc4b3bf4d42a1d1", grpName = "Robotics Club", description = "Building robots, one line follower at a time."),
            null, JoinState.NotConnected, {}, {}, {}, {}
        )
    }
}
