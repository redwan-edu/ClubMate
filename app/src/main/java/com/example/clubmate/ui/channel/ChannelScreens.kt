package com.example.clubmate.ui.channel

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.Routes
import com.example.clubmate.e2ee.ChannelE2ee
import com.example.clubmate.ui.components.AppTextField
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.ChatMessageUi
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.InlineError
import com.example.clubmate.ui.components.MessageComposer
import com.example.clubmate.ui.components.MessageList
import com.example.clubmate.ui.components.PrimaryButton
import com.example.clubmate.ui.components.SecondaryButton
import com.example.clubmate.ui.components.TabHeader
import com.example.clubmate.ui.group.FormPage
import com.example.clubmate.ui.group.GeneratedIdCard
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.PrivateChannelViewModel

// ---------------------------------------------------------------- channels tab (join)

@Composable
fun ChannelsTab(
    channelId: String,
    onChannelIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onCreate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        TabHeader("Channels")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Rounded.Key, "The password is the key", "Messages are encrypted with a key, share it wisely.")
            Feature(Icons.Rounded.Timer, "Messages vanish", "Messages deletes once they've been read and the reader leaves.")

            Text("Join a channel", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            AppTextField(channelId, onChannelIdChange, "Channel ID", leadingIcon = Icons.Rounded.Tag)
            AppTextField(
                password, onPasswordChange, "Password",
                leadingIcon = Icons.Rounded.Password, isPassword = true,
                imeAction = ImeAction.Done, onImeAction = onJoin
            )
            if (error != null) InlineError(error)
            PrimaryButton("Join channel", onJoin, loading = loading, enabled = channelId.isNotBlank() && password.isNotEmpty())

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "or",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
            SecondaryButton("Create a new channel", onCreate, icon = Icons.Rounded.Add)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Channels tab with its own state; the user ID comes from the signed-in account. */
@Composable
fun ChannelsTabRoute(
    myUid: String,
    viewModel: PrivateChannelViewModel,
    onOpen: (Routes.PrivateChat) -> Unit,
    onCreate: () -> Unit
) {
    var channelId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ChannelsTab(
        channelId = channelId, onChannelIdChange = { channelId = it.trim(); error = null },
        password = password, onPasswordChange = { password = it; error = null },
        loading = loading, error = error,
        onJoin = {
            if (channelId.isBlank() || password.isEmpty() || loading) return@ChannelsTab
            loading = true
            viewModel.joinChatroom(chatId = channelId, uid = myUid, passWord = password) { ok ->
                loading = false
                if (ok) {
                    onOpen(Routes.PrivateChat(channelId = channelId, uid = myUid, password = password))
                    password = ""
                } else {
                    error = "Wrong channel ID or password."
                }
            }
        },
        onCreate = onCreate
    )
}

// ---------------------------------------------------------------- create channel

@Composable
fun CreateChannelScreen(
    channelId: String?,
    onRegenerateId: () -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onBack: () -> Unit
) {
    val tooShort = password.isNotEmpty() && password.length < ChannelE2ee.MIN_PASSWORD_LENGTH
    FormPage(
        title = "New channel",
        onBack = onBack,
        action = {
            PrimaryButton(
                "Create channel", onCreate, loading = loading,
                enabled = channelId != null && password.length >= ChannelE2ee.MIN_PASSWORD_LENGTH
            )
        }
    ) {
        Text(
            "Anyone with the channel ID and password can join. Choose a password that's hard to guess: it's the encryption key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GeneratedIdCard("Channel ID", channelId, "Share the ID and password in person or over another secure app.", onRegenerateId)
        AppTextField(
            password, onPasswordChange, "Password",
            leadingIcon = Icons.Rounded.Password, isPassword = true,
            isError = tooShort,
            supportingText = "At least ${ChannelE2ee.MIN_PASSWORD_LENGTH} characters. A few random words work well.",
            imeAction = ImeAction.Done, onImeAction = onCreate
        )
        if (error != null) InlineError(error)
    }
}

@Composable
fun CreateChannelRoute(
    myUid: String,
    viewModel: PrivateChannelViewModel,
    onBack: () -> Unit,
    onCreated: (Routes.PrivateChat) -> Unit
) {
    var channelId by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (channelId == null) viewModel.requestId { channelId = it } }

    CreateChannelScreen(
        channelId = channelId,
        onRegenerateId = { channelId = null; viewModel.requestId { channelId = it } },
        password = password, onPasswordChange = { password = it; error = null },
        loading = loading, error = error,
        onCreate = {
            val id = channelId ?: return@CreateChannelScreen
            if (password.length < ChannelE2ee.MIN_PASSWORD_LENGTH || loading) return@CreateChannelScreen
            loading = true
            viewModel.createChatroom(chatId = id, passWord = password, uid = myUid) { created ->
                loading = false
                if (created != null) onCreated(Routes.PrivateChat(channelId = id, uid = myUid, password = password))
                else error = "Couldn't create the channel. Try again."
            }
        },
        onBack = onBack
    )
}

// ---------------------------------------------------------------- channel chat

@Composable
fun ChannelChatScreen(
    channelId: String,
    messages: List<ChatMessageUi>,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachment: Any?,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onCopyId: () -> Unit,
    onDeleteChannel: () -> Unit,
    onCopy: (ChatMessageUi) -> Unit,
    onDelete: (ChatMessageUi) -> Unit
) {
    val chat = ClubMateTheme.chat
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(chat.incognitoBackground)
            .imePadding()
    ) {
        AppTopBar(
            title = "Private channel",
            subtitle = "#$channelId · messages vanish",
            subtitleIcon = Icons.Rounded.Timer,
            onBack = onBack,
            leading = { Avatar("Private channel", size = 40.dp, icon = Icons.Rounded.Lock) },
            containerColor = chat.incognitoBackground,
            contentColor = chat.onIncognito
        ) {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy channel ID") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; onCopyId() }
                )
                DropdownMenuItem(
                    text = { Text("Delete channel", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDeleteChannel() }
                )
            }
        }
        MessageList(
            messages = messages,
            modifier = Modifier.weight(1f),
            notice = "Encrypted with a key made from the channel password. Messages are deleted after they've been read.",
            showSenders = true,
            incognito = true,
            onCopy = onCopy,
            onDelete = onDelete
        )
        MessageComposer(
            value = draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            placeholder = "Vanishing message",
            onAttach = onAttach,
            attachment = attachment,
            onRemoveAttachment = onRemoveAttachment,
            incognito = true
        )
    }
}

private val aliasColors = listOf("Amber", "Blue", "Coral", "Green", "Indigo", "Jade", "Ruby", "Silver")
private val aliasAnimals = listOf("Falcon", "Fox", "Heron", "Lynx", "Otter", "Owl", "Panda", "Raven", "Seal", "Tiger", "Whale", "Wolf")

/**
 * Channels have no member list, so senders get a stable pseudonym such as "Blue Otter" instead of
 * a name. Everyone in the channel sees the same pseudonym for the same person.
 */
fun channelAlias(senderId: String): String {
    var hash = 17
    for (c in senderId) hash = hash * 31 + c.code
    // murmur3 finaliser, so similar IDs still get unrelated pseudonyms
    hash = hash xor (hash ushr 16)
    hash *= -0x7a143595
    hash = hash xor (hash ushr 13)
    hash *= -0x3d4d51cb
    hash = hash xor (hash ushr 16)
    val n = hash and 0x7fffffff
    return "${aliasColors[n % aliasColors.size]} ${aliasAnimals[(n / aliasColors.size) % aliasAnimals.size]}"
}

@Composable
fun ChannelChatRoute(
    args: Routes.PrivateChat,
    viewModel: PrivateChannelViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatMessageUi?>(null) }
    val messages by viewModel.privateMessageList.collectAsState()
    val error by viewModel.error.collectAsState()

    // derive the channel key from the password, then start showing decrypted messages
    LaunchedEffect(args.channelId) {
        viewModel.openChannel(channelId = args.channelId, password = args.password, uid = args.uid)
    }
    // leaving deletes the messages that have been read
    DisposableEffect(args.channelId) { onDispose { viewModel.leaveChatroom(args.channelId) } }
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachment = uri
    }

    val shown = messages
        .filter { it.messageText.isNotEmpty() || it.imageUrl.isNotEmpty() }
        .map {
            ChatMessageUi(
                id = it.messageId,
                text = it.messageText,
                timestamp = it.timestampSent,
                isMine = it.senderId == args.uid,
                imageRef = it.imageUrl,
                senderId = it.senderId,
                senderName = channelAlias(it.senderId)
            )
        }

    ChannelChatScreen(
        channelId = args.channelId,
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
                viewModel.sendVanishingMessage(channelId = args.channelId, uid = args.uid, imageUri = image)
                attachment = null
            } else if (text.isNotEmpty()) {
                viewModel.sendVanishingMessage(channelId = args.channelId, uid = args.uid, messageText = text)
                draft = ""
            }
        },
        onBack = onBack,
        onCopyId = {
            clipboard.setText(AnnotatedString(args.channelId))
            Toast.makeText(context, "Channel ID copied", Toast.LENGTH_SHORT).show()
        },
        onDeleteChannel = { confirm = "channel" },
        onCopy = {
            clipboard.setText(AnnotatedString(it.text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        },
        onDelete = { pendingDelete = it }
    )

    if (confirm == "channel") {
        ConfirmDialog(
            title = "Delete this channel?",
            message = "The channel and all its messages will be deleted for everyone. This can't be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                confirm = null
                viewModel.deleteChannel(args.channelId) { onDeleted() }
            },
            onDismiss = { confirm = null }
        )
    }
    pendingDelete?.let { message ->
        ConfirmDialog(
            title = "Delete message?",
            message = "It will be removed for everyone in this channel.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                messages.firstOrNull { it.messageId == message.id }?.let {
                    viewModel.deleteIndividualMessage(args.channelId, it) { ok ->
                        if (!ok) Toast.makeText(context, "Couldn't delete the message", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ---------------------------------------------------------------- previews

@Preview
@Composable
fun ChannelsTabPreview() = ClubMateTheme {
    ChannelsTab("", {}, "", {}, loading = false, error = null, onJoin = {}, onCreate = {})
}

@Preview
@Composable
fun CreateChannelPreview() = ClubMateTheme {
    CreateChannelScreen("7c1e90ab3f", {}, "blue-lantern-ri", {}, false, null, {}, {})
}

@Preview
@Composable
fun ChannelChatPreview() = ClubMateTheme {
    val now = System.currentTimeMillis()
    ChannelChatScreen(
        "7c1e90ab3f",
        listOf(
            ChatMessageUi("1", "Is everyone here?", now - 6 * 60_000, false, senderId = "u1", senderName = channelAlias("u1")),
            ChatMessageUi("2", "Yes. Let's finalise the exam question bank split", now - 5 * 60_000, true, senderId = "me"),
            ChatMessageUi("3", "I'll take chapters 4 to 6", now - 4 * 60_000, false, senderId = "u2", senderName = channelAlias("u2")),
            ChatMessageUi("4", "Chapters 1 to 3 for me then", now - 2 * 60_000, false, senderId = "u1", senderName = channelAlias("u1")),
        ),
        "", {}, null, {}, {}, {}, {}, {}, {}, {}, {}
    )
}
