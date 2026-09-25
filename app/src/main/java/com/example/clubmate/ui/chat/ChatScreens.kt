package com.example.clubmate.ui.chat

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.clubmate.db.Routes
import com.example.clubmate.e2ee.E2eeManager
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.ChatMessageUi
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.InfoRow
import com.example.clubmate.ui.components.MessageComposer
import com.example.clubmate.ui.components.MessageList
import com.example.clubmate.ui.components.SectionHeader
import com.example.clubmate.ui.components.SettingsRow
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.ChatViewModel

// ---------------------------------------------------------------- chat

@Composable
fun ChatScreen(
    name: String,
    photoUrl: String?,
    messages: List<ChatMessageUi>,
    incognito: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachment: Any?,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onToggleIncognito: () -> Unit,
    onCopy: (ChatMessageUi) -> Unit,
    onDelete: (ChatMessageUi) -> Unit
) {
    val chat = ClubMateTheme.chat
    Column(
        Modifier
            .fillMaxSize()
            .background(if (incognito) chat.incognitoBackground else chat.background)
            .imePadding()
    ) {
        AppTopBar(
            title = name,
            subtitle = if (incognito) "Incognito · messages vanish" else "End-to-end encrypted",
            subtitleIcon = if (incognito) Icons.Rounded.VisibilityOff else Icons.Rounded.Lock,
            onBack = onBack,
            onTitleClick = onOpenProfile,
            leading = { Avatar(name, imageUrl = photoUrl, size = 40.dp) },
            containerColor = if (incognito) chat.incognitoBackground else MaterialTheme.colorScheme.surface,
            contentColor = if (incognito) chat.onIncognito else MaterialTheme.colorScheme.onSurface,
            showDivider = !incognito
        ) {
            IconButton(
                onClick = onToggleIncognito,
                colors = if (incognito) IconButtonDefaults.iconButtonColors(
                    containerColor = chat.incognitoBubble, contentColor = chat.onIncognito
                ) else IconButtonDefaults.iconButtonColors()
            ) {
                Icon(
                    if (incognito) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = if (incognito) "Leave incognito" else "Incognito mode"
                )
            }
        }
        MessageList(
            messages = messages,
            modifier = Modifier.weight(1f),
            notice = if (incognito) {
                "Incognito chat. Messages aren't kept and are deleted when you leave incognito."
            } else {
                "Messages are end-to-end encrypted with the Double Ratchet. Only you and $name can read them."
            },
            incognito = incognito,
            onCopy = onCopy,
            onDelete = if (incognito) null else onDelete
        )
        MessageComposer(
            value = draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            placeholder = if (incognito) "Incognito message" else "Message",
            onAttach = if (incognito) null else onAttach,
            attachment = attachment,
            onRemoveAttachment = onRemoveAttachment,
            incognito = incognito
        )
    }
}

@Composable
fun ChatRoute(
    args: Routes.UserModel,
    myUid: String,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val chatId = args.chatID

    var photoUrl by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var incognito by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatMessageUi?>(null) }

    val messages by chatViewModel.messages.collectAsState()
    val incognitoMessages by chatViewModel.incognitoMessages.collectAsState()
    val sendError by chatViewModel.sendError.collectAsState()

    LaunchedEffect(chatId) {
        chatViewModel.fetchUserByUid(args.uid) { photoUrl = it?.photoUrl }
        chatViewModel.receiveMessage(chatId)
    }
    DisposableEffect(chatId) {
        onDispose { chatViewModel.clearMessage(chatId = chatId) }
    }
    // Incognito messages are deleted when you leave incognito (or the chat while in it).
    LaunchedEffect(incognito) {
        if (incognito) chatViewModel.receiveIncognitoMessage(chatId)
    }
    DisposableEffect(incognito) {
        onDispose {
            // a rotation also disposes the screen, but the chat is still open
            val rotating = (context as? Activity)?.isChangingConfigurations == true
            if (incognito && !rotating) chatViewModel.deleteIncognitoMessage(chatId)
        }
    }
    LaunchedEffect(sendError) {
        sendError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            chatViewModel.clearSendError()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachment = uri
    }

    val shown = if (incognito) {
        incognitoMessages.map {
            ChatMessageUi(it.messageId, it.messageText, it.timestamp, isMine = it.senderId == myUid, senderId = it.senderId)
        }
    } else {
        messages.filter { it.messageText.isNotEmpty() || it.imageRef.isNotEmpty() }.map {
            ChatMessageUi(
                id = it.messageId, text = it.messageText, timestamp = it.timestamp,
                isMine = it.senderId == myUid, imageRef = it.imageRef, senderId = it.senderId
            )
        }
    }

    ChatScreen(
        name = args.username,
        photoUrl = photoUrl,
        messages = shown,
        incognito = incognito,
        draft = draft,
        onDraftChange = { draft = it },
        attachment = attachment,
        onAttach = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveAttachment = { attachment = null },
        onSend = {
            val image = attachment
            val text = draft.trim()
            when {
                image != null -> {
                    chatViewModel.sendMessage(chatId = chatId, senderId = myUid, receiverId = args.uid, imageUri = image)
                    attachment = null
                }

                text.isEmpty() -> Unit
                incognito -> chatViewModel.sendIncognitoMessage(chatId, args.uid, myUid, text)
                else -> chatViewModel.sendMessage(chatId = chatId, senderId = myUid, receiverId = args.uid, messageText = text)
            }
            if (image == null && text.isNotEmpty()) draft = ""
        },
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onToggleIncognito = { incognito = !incognito },
        onCopy = {
            clipboard.setText(AnnotatedString(it.text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        },
        onDelete = { pendingDelete = it }
    )

    pendingDelete?.let { message ->
        ConfirmDialog(
            title = "Delete message?",
            message = "It will be removed for everyone in this chat.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                chatViewModel.deleteIndividualMessage(chatId, message.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ---------------------------------------------------------------- contact profile

@Composable
fun ContactProfileScreen(
    name: String,
    email: String,
    phone: String,
    photoUrl: String?,
    safetyNumber: String?,
    onBack: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onDeleteMyMessages: () -> Unit,
    onDeleteChat: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Contact info", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(name, imageUrl = photoUrl, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EncryptionCard(
                "Messages and photos in this chat are end-to-end encrypted with the Double Ratchet. " +
                    "Not even ClubMate can read them."
            )
            SectionHeader("Safety number")
            SafetyNumber(safetyNumber, name)
            SectionHeader("Details")
            InfoRow("Name", name, icon = Icons.Rounded.Person, onCopy = { onCopy("Name", name) })
            InfoRow("Email", email, icon = Icons.Rounded.AlternateEmail, onCopy = { onCopy("Email", email) })
            if (phone.isNotBlank()) {
                InfoRow("Phone", phone, icon = Icons.Rounded.Phone, onCopy = { onCopy("Phone", phone) })
            }
            SectionHeader("Chat")
            SettingsRow(
                Icons.Rounded.DeleteSweep, "Delete my messages", onDeleteMyMessages,
                subtitle = "Removes everything you sent in this chat",
                tint = MaterialTheme.colorScheme.error, showChevron = false
            )
            SettingsRow(
                Icons.Rounded.DeleteOutline, "Delete chat", onDeleteChat,
                subtitle = "Removes the whole conversation for both of you",
                tint = MaterialTheme.colorScheme.error, showChevron = false
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The chat's safety number in three rows of two groups. If both people see the same digits, nobody
 * has swapped a key in between.
 */
@Composable
private fun SafetyNumber(number: String?, name: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (number == null) {
            Text(
                "$name hasn't set up encryption on their device yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            number.split(" ").chunked(2).forEach { row ->
                Text(
                    row.joinToString("   "),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Compare these numbers with $name in person. If they match, no one can listen in on your chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Reassuring note about encryption, used on profile and info screens. */
@Composable
fun EncryptionCard(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun ContactProfileRoute(
    args: Routes.UserDetails,
    myUid: String,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onChatDeleted: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var user by remember { mutableStateOf<Routes.UserModel?>(null) }
    var safetyNumber by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(args.uid) { chatViewModel.fetchUserByUid(args.uid) { user = it } }
    LaunchedEffect(args.uid) { safetyNumber = E2eeManager.safetyNumber(myUid, args.uid) }

    ContactProfileScreen(
        name = user?.username ?: args.username,
        email = user?.email ?: args.email,
        phone = user?.phone ?: args.phone,
        photoUrl = user?.photoUrl,
        safetyNumber = safetyNumber,
        onBack = onBack,
        onCopy = { label, value ->
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        },
        onDeleteMyMessages = { confirm = "messages" },
        onDeleteChat = { confirm = "chat" }
    )

    when (confirm) {
        "messages" -> ConfirmDialog(
            title = "Delete your messages?",
            message = "Everything you sent in this chat will be removed for both of you.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                confirm = null
                chatViewModel.deleteMyMessages(chatId = args.chatID, senderId = myUid) { ok ->
                    Toast.makeText(context, if (ok) "Your messages were deleted" else "Nothing to delete", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirm = null }
        )

        "chat" -> ConfirmDialog(
            title = "Delete this chat?",
            message = "The conversation will be removed for both of you. This can't be undone.",
            confirmText = "Delete chat",
            destructive = true,
            onConfirm = {
                confirm = null
                chatViewModel.deleteChatId(args.chatID, myUid) { ok ->
                    if (ok) onChatDeleted() else Toast.makeText(context, "Couldn't delete the chat", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

// ---------------------------------------------------------------- previews

private val now = System.currentTimeMillis()
private fun ago(min: Long) = now - min * 60_000

val SampleConversation = listOf(
    ChatMessageUi("1", "Hey! Are you coming to the robotics club meeting tomorrow?", ago(60 * 26), false, senderId = "b"),
    ChatMessageUi("2", "Yes, what time again?", ago(60 * 26 - 3), true, senderId = "a"),
    ChatMessageUi("3", "5 pm in the lab", ago(60 * 26 - 4), false, senderId = "b"),
    ChatMessageUi("4", "Bring the sensor kit if you can", ago(60 * 26 - 4), false, senderId = "b"),
    ChatMessageUi("5", "Sure, will do", ago(40), true, senderId = "a"),
    ChatMessageUi("6", "", ago(35), true, imageRef = "e2ee-image:v1:k:https://example", senderId = "a"),
    ChatMessageUi("7", "Here's the wiring diagram from last week. We should redo the motor driver before the demo, it overheats after ten minutes.", ago(12), false, senderId = "b"),
    ChatMessageUi("8", "Agreed. Let's do it tomorrow", ago(2), true, senderId = "a"),
)

@Preview
@Composable
fun ChatScreenPreview() = ClubMateTheme {
    ChatScreen(
        "Mizanur Rahman", null, SampleConversation, incognito = false, draft = "", onDraftChange = {},
        attachment = null, onAttach = {}, onRemoveAttachment = {}, onSend = {}, onBack = {}, onOpenProfile = {},
        onToggleIncognito = {}, onCopy = {}, onDelete = {}
    )
}

@Preview
@Composable
fun ChatIncognitoPreview() = ClubMateTheme {
    ChatScreen(
        "Mizanur Rahman", null,
        listOf(
            ChatMessageUi("i1", "Can you talk about the surprise for Adnan?", ago(3), false, senderId = "b"),
            ChatMessageUi("i2", "Yes! Cake at 6, everyone chips in 200 tk", ago(2), true, senderId = "a"),
        ),
        incognito = true, draft = "Don't tell him", onDraftChange = {},
        attachment = null, onAttach = {}, onRemoveAttachment = {}, onSend = {}, onBack = {}, onOpenProfile = {},
        onToggleIncognito = {}, onCopy = {}, onDelete = {}
    )
}

@Preview
@Composable
fun ContactProfilePreview() = ClubMateTheme {
    ContactProfileScreen(
        "Mizanur Rahman", "mizan21331@gmail.com", "+880 1712 345678", null,
        safetyNumber = "37402 11958 60213 88415 02736 91547",
        onBack = {}, onCopy = { _, _ -> }, onDeleteMyMessages = {}, onDeleteChat = {}
    )
}
