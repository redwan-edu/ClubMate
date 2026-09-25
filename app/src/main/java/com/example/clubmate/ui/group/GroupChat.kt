package com.example.clubmate.ui.group

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.Routes
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.ChatMessageUi
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.MessageComposer
import com.example.clubmate.ui.components.MessageList
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.Category
import com.example.clubmate.viewmodel.GroupViewmodel

/** How a member's role is shown in the app. */
val Category.label: String
    get() = when (this) {
        Category.Admin -> "Admin"
        Category.General -> "Member"
        Category.President -> "President"
        Category.VicePresident -> "Vice president"
        Category.Treasurer -> "Treasurer"
    }

@Composable
fun GroupChatScreen(
    name: String,
    photoUrl: String?,
    memberCount: Int,
    messages: List<ChatMessageUi>,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachment: Any?,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenNotices: () -> Unit,
    onCopy: (ChatMessageUi) -> Unit,
    onDelete: (ChatMessageUi) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ClubMateTheme.chat.background)
            .imePadding()
    ) {
        AppTopBar(
            title = name,
            subtitle = if (memberCount > 0) "$memberCount members · encrypted" else "End-to-end encrypted",
            subtitleIcon = Icons.Rounded.Lock,
            onBack = onBack,
            onTitleClick = onOpenInfo,
            leading = { Avatar(name, imageUrl = photoUrl, size = 40.dp, icon = Icons.Rounded.Groups) },
            showDivider = true
        ) {
            IconButton(onClick = onOpenNotices) {
                Icon(Icons.Rounded.Campaign, contentDescription = "Notice board")
            }
        }
        MessageList(
            messages = messages,
            modifier = Modifier.weight(1f),
            notice = "Messages in this group are end-to-end encrypted. Only its members can read them.",
            showSenders = true,
            onCopy = onCopy,
            onDelete = onDelete
        )
        MessageComposer(
            value = draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            onAttach = onAttach,
            attachment = attachment,
            onRemoveAttachment = onRemoveAttachment
        )
    }
}

@Composable
fun GroupChatRoute(
    grpId: String,
    myUid: String,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onOpenInfo: (Routes.GrpDetails) -> Unit,
    onOpenNotices: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var details by remember { mutableStateOf(Routes.GrpDetails(grpId = grpId)) }
    var draft by rememberSaveable { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatMessageUi?>(null) }

    val activities by groupViewModel.grpActivity.collectAsState()
    val members by groupViewModel.participantsList.collectAsState()
    val sendError by groupViewModel.sendError.collectAsState()

    LaunchedEffect(grpId) {
        groupViewModel.loadGroupInfo(grpId) { info -> info?.let { details = it } }
        groupViewModel.loadActivities(grpId)
        groupViewModel.getAllParticipants(grpId)
    }
    DisposableEffect(grpId) { onDispose { groupViewModel.clearMessage() } }
    LaunchedEffect(sendError) {
        sendError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            groupViewModel.clearSendError()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachment = uri
    }

    val byUid = remember(members) { members.associateBy { it.uid } }
    val shown = activities
        .map { it.message }
        .filter { it.messageText.isNotEmpty() || it.imageRef.isNotEmpty() }
        .map { message ->
            val sender = byUid[message.senderId]
            ChatMessageUi(
                id = message.messageId,
                text = message.messageText,
                timestamp = message.timestamp,
                isMine = message.senderId == myUid,
                imageRef = message.imageRef,
                senderId = message.senderId,
                senderName = when {
                    sender != null -> sender.username.ifBlank { sender.email }
                    members.isEmpty() -> null // still loading
                    else -> "Former member"
                },
                senderRole = sender?.userType?.takeIf { it != Category.General }?.label
            )
        }

    GroupChatScreen(
        name = details.grpName.ifBlank { "Group" },
        photoUrl = details.photoUrl,
        memberCount = members.size,
        messages = shown,
        draft = draft,
        onDraftChange = { draft = it },
        attachment = attachment,
        onAttach = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveAttachment = { attachment = null },
        onSend = {
            val image = attachment
            val text = draft.trim()
            if (image != null) {
                groupViewModel.addActivity(grpId = grpId, senderId = myUid, imageUri = image)
                attachment = null
            } else if (text.isNotEmpty()) {
                groupViewModel.addActivity(grpId = grpId, senderId = myUid, messageText = text)
                draft = ""
            }
        },
        onBack = onBack,
        onOpenInfo = { onOpenInfo(details) },
        onOpenNotices = onOpenNotices,
        onCopy = {
            clipboard.setText(AnnotatedString(it.text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        },
        onDelete = { pendingDelete = it }
    )

    pendingDelete?.let { message ->
        ConfirmDialog(
            title = "Delete message?",
            message = "It will be removed for everyone in this group.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                activities.firstOrNull { it.message.messageId == message.id }?.let {
                    groupViewModel.removeActivity(activity = it, grpId = grpId)
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ---------------------------------------------------------------- previews

private val now = System.currentTimeMillis()
private fun ago(min: Long) = now - min * 60_000

val SampleGroupConversation = listOf(
    ChatMessageUi("1", "Welcome to the Robotics Club group! Introduce yourselves", ago(60 * 30), false, senderId = "r", senderName = "Redwan Hussain", senderRole = "President"),
    ChatMessageUi("2", "Hi everyone, I'm Tonmoy from CSE 3rd year", ago(60 * 30 - 5), false, senderId = "t", senderName = "Tonmoy Chanda"),
    ChatMessageUi("3", "Mizan here, I handle the budget", ago(60 * 30 - 7), false, senderId = "m", senderName = "Mizanur Rahman", senderRole = "Treasurer"),
    ChatMessageUi("4", "Hey all! Excited for the line follower project", ago(50), true, senderId = "me"),
    ChatMessageUi("5", "", ago(20), false, imageRef = "e2ee-image:v1:k:https://example", senderId = "t", senderName = "Tonmoy Chanda"),
    ChatMessageUi("6", "The chassis is ready. Motors arrive on Thursday", ago(19), false, senderId = "t", senderName = "Tonmoy Chanda"),
    ChatMessageUi("7", "Nice work! Meeting is on the notice board", ago(4), false, senderId = "r", senderName = "Redwan Hussain", senderRole = "President"),
)

@Preview
@Composable
fun GroupChatPreview() = ClubMateTheme {
    GroupChatScreen(
        "Robotics Club", null, 24, SampleGroupConversation, "", {}, null, {}, {}, {}, {}, {}, {}, {}, {}
    )
}
