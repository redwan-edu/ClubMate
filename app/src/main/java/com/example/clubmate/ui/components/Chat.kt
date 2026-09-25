package com.example.clubmate.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.clubmate.ui.theme.AppFontFamily
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.SecureAsyncImage
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- model

/** A message as the chat UI needs it, whatever it came from (1:1 chat, group or channel). */
data class ChatMessageUi(
    val id: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val imageRef: String = "",
    val senderId: String = "",
    val senderName: String? = null,
    val senderRole: String? = null,
    val canDelete: Boolean = isMine
) {
    /** Placeholders from the encryption layer ("🔒 ...", "⚠️ Not encrypted: ...") are styled differently. */
    val isNotice: Boolean get() = text.startsWith("🔒") || text.startsWith("⚠️")
}

sealed interface MessageListItem {
    val key: String

    data class Day(val label: String) : MessageListItem {
        override val key: String get() = "day-$label"
    }

    /** [first]/[last]: position within a run of messages from the same sender. */
    data class Bubble(val message: ChatMessageUi, val first: Boolean, val last: Boolean) : MessageListItem {
        override val key: String get() = message.id
    }
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/** Sorts messages, inserts day separators and marks runs of messages from the same sender. */
fun buildMessageItems(messages: List<ChatMessageUi>, now: Long = System.currentTimeMillis()): List<MessageListItem> {
    val sorted = messages.sortedBy { it.timestamp }
    fun sameRun(a: ChatMessageUi, b: ChatMessageUi) =
        a.isMine == b.isMine && a.senderId == b.senderId &&
            TimeFormat.sameDay(a.timestamp, b.timestamp) && b.timestamp - a.timestamp < GROUP_WINDOW_MS

    val items = ArrayList<MessageListItem>(sorted.size + 4)
    sorted.forEachIndexed { i, message ->
        val previous = sorted.getOrNull(i - 1)
        val next = sorted.getOrNull(i + 1)
        if (previous == null || !TimeFormat.sameDay(previous.timestamp, message.timestamp)) {
            items += MessageListItem.Day(TimeFormat.dayLabel(message.timestamp, now))
        }
        items += MessageListItem.Bubble(
            message,
            first = previous == null || !sameRun(previous, message),
            last = next == null || !sameRun(message, next)
        )
    }
    return items
}

// ---------------------------------------------------------------- list

/**
 * The scrolling conversation, anchored to the bottom like every chat app. [notice] is shown above
 * the first message (e.g. the end-to-end encryption notice).
 */
@Composable
fun MessageList(
    messages: List<ChatMessageUi>,
    modifier: Modifier = Modifier,
    notice: String? = null,
    showSenders: Boolean = false,
    incognito: Boolean = false,
    onCopy: ((ChatMessageUi) -> Unit)? = null,
    onDelete: ((ChatMessageUi) -> Unit)? = null
) {
    val items = remember(messages) { buildMessageItems(messages).asReversed() }
    val listState = rememberLazyListState()

    // Keep following the conversation when a new message arrives and we're at the bottom.
    val newestKey = items.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is MessageListItem.Day -> DaySeparator(item.label, incognito)
                is MessageListItem.Bubble -> MessageBubble(
                    item = item,
                    showSender = showSenders,
                    incognito = incognito,
                    onCopy = onCopy,
                    onDelete = onDelete,
                    modifier = Modifier.padding(top = if (item.first) 8.dp else 2.dp)
                )
            }
        }
        if (notice != null) {
            item(key = "notice") { EncryptionNotice(notice, incognito) }
        }
    }
}

@Composable
fun DaySeparator(label: String, incognito: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (incognito) ClubMateTheme.chat.onIncognito.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (incognito) ClubMateTheme.chat.incognitoBubble else ClubMateTheme.chat.pill
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun EncryptionNotice(text: String, incognito: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (incognito) ClubMateTheme.chat.incognitoBubble else ClubMateTheme.chat.pill
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = if (incognito) ClubMateTheme.chat.onIncognito.copy(alpha = 0.75f)
            else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Start)
        }
    }
}

// ---------------------------------------------------------------- bubble

private val BubbleMaxWidth = 296.dp
private val MessageTimeStyle = TextStyle(fontFamily = AppFontFamily, fontSize = 11.sp, lineHeight = 14.sp)
private val Big = 18.dp
private val Small = 6.dp
private val Tail = 4.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    item: MessageListItem.Bubble,
    modifier: Modifier = Modifier,
    showSender: Boolean = false,
    incognito: Boolean = false,
    onCopy: ((ChatMessageUi) -> Unit)? = null,
    onDelete: ((ChatMessageUi) -> Unit)? = null
) {
    val message = item.message
    val mine = message.isMine
    val chat = ClubMateTheme.chat
    val background = when {
        mine -> chat.outgoingBubble
        incognito -> chat.incognitoBubble
        else -> chat.incomingBubble
    }
    val contentColor = when {
        mine -> chat.onOutgoingBubble
        incognito -> chat.onIncognito
        else -> chat.onIncomingBubble
    }
    val shape = if (mine) {
        RoundedCornerShape(Big, if (item.first) Big else Small, if (item.last) Tail else Small, Big)
    } else {
        RoundedCornerShape(if (item.first) Big else Small, Big, Big, if (item.last) Tail else Small)
    }
    val showAvatarColumn = showSender && !mine
    var menuOpen by remember { mutableStateOf(false) }
    val canCopy = onCopy != null && message.text.isNotEmpty() && !message.isNotice
    val canDelete = onDelete != null && message.canDelete

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (showAvatarColumn) {
            if (item.last) Avatar(message.senderName ?: "?", size = 30.dp) else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .clip(shape)
                    .background(background)
                    .combinedClickable(
                        enabled = canCopy || canDelete,
                        onClick = {},
                        onLongClick = { menuOpen = true }
                    )
                    .padding(
                        if (message.imageRef.isNotEmpty()) PaddingValues(2.dp)
                        else PaddingValues(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 6.dp)
                    )
            ) {
                if (showSender && !mine && item.first && message.senderName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(
                            start = if (message.imageRef.isNotEmpty()) 9.dp else 0.dp,
                            top = if (message.imageRef.isNotEmpty()) 4.dp else 0.dp,
                            bottom = 3.dp
                        )
                    ) {
                        Text(
                            message.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = avatarColor(message.senderName)
                        )
                        if (!message.senderRole.isNullOrEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                message.senderRole,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
                if (message.imageRef.isNotEmpty()) {
                    ImageContent(message, contentColor)
                } else {
                    TextWithTime(
                        text = message.text,
                        time = TimeFormat.time(message.timestamp),
                        textStyle = if (message.isNotice) {
                            MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                        } else MaterialTheme.typography.bodyLarge,
                        textColor = if (message.isNotice) contentColor.copy(alpha = 0.75f) else contentColor,
                        timeColor = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (canCopy) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onCopy?.invoke(message) }
                    )
                }
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete?.invoke(message) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageContent(message: ChatMessageUi, contentColor: Color) {
    Box {
        SecureAsyncImage(
            model = message.imageRef,
            contentDescription = "Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(240.dp)
                .heightIn(min = 160.dp, max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Text(
            text = TimeFormat.time(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/**
 * Message text with its time in the bottom-right corner. The time shares the last line when there
 * is room, otherwise it drops below, like in most messaging apps.
 */
@Composable
private fun TextWithTime(
    text: String,
    time: String,
    textStyle: TextStyle,
    textColor: Color,
    timeColor: Color
) {
    val layoutHolder = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        content = {
            Text(text, style = textStyle, color = textColor, onTextLayout = { layoutHolder[0] = it })
            Text(time, style = MessageTimeStyle, color = timeColor)
        }
    ) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val textPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val timePlaceable = measurables[1].measure(Constraints())
        val textLayout = layoutHolder[0]
        val lastLineRight = if (textLayout != null && textLayout.lineCount > 0) {
            textLayout.getLineRight(textLayout.lineCount - 1).roundToInt()
        } else textPlaceable.width
        val sameLine = lastLineRight + gap + timePlaceable.width <= constraints.maxWidth

        val width = if (sameLine) max(textPlaceable.width, lastLineRight + gap + timePlaceable.width)
        else max(textPlaceable.width, timePlaceable.width)
        val height = if (sameLine) max(textPlaceable.height, timePlaceable.height)
        else textPlaceable.height + timePlaceable.height

        layout(width, height) {
            textPlaceable.place(0, 0)
            timePlaceable.place(width - timePlaceable.width, height - timePlaceable.height)
        }
    }
}

// ---------------------------------------------------------------- composer

/** Input bar: optional photo button, growing text field, round send button. */
@Composable
fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    onAttach: (() -> Unit)? = null,
    attachment: Any? = null,
    onRemoveAttachment: () -> Unit = {},
    sending: Boolean = false,
    incognito: Boolean = false
) {
    val chat = ClubMateTheme.chat
    val container = if (incognito) chat.incognitoBackground else MaterialTheme.colorScheme.surface
    val fieldColor = if (incognito) chat.incognitoBubble else MaterialTheme.colorScheme.surfaceContainer
    val textColor = if (incognito) chat.onIncognito else MaterialTheme.colorScheme.onSurface
    val hintColor = if (incognito) chat.onIncognito.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
    val canSend = (value.isNotBlank() || attachment != null) && !sending

    Column(modifier.fillMaxWidth().background(container).navigationBarsPadding()) {
        if (attachment != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    AsyncImage(
                        model = attachment,
                        contentDescription = "Selected photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(fieldColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable(onClick = onRemoveAttachment),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Photo", style = MaterialTheme.typography.titleSmall, color = textColor)
                    Text(
                        "Encrypted on your phone before upload",
                        style = MaterialTheme.typography.bodySmall,
                        color = hintColor
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (onAttach != null) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Add photo", tint = hintColor)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(fieldColor)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = hintColor)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (canSend || sending) MaterialTheme.colorScheme.primary else fieldColor)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else hintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
