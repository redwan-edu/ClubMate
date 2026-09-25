package com.example.clubmate.ui.group

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.EmptyState
import com.example.clubmate.ui.components.Pill
import com.example.clubmate.ui.components.TimeFormat
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.Category
import com.example.clubmate.util.EventCategory
import com.example.clubmate.viewmodel.EventData
import com.example.clubmate.viewmodel.GroupViewmodel

private val tabs = listOf(EventCategory.Event, EventCategory.Meeting, EventCategory.Notice)
private val EventCategory.plural get() = when (this) {
    EventCategory.Event -> "Events"
    EventCategory.Meeting -> "Meetings"
    EventCategory.Notice -> "Notices"
}

private val urlPattern = Regex("""(https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)

/** Web links in a post, trimmed of trailing punctuation and given a scheme if they lack one. */
fun linksIn(text: String): List<String> = urlPattern.findAll(text)
    .map { it.value.trimEnd('.', ',', ')', ';', '!', '?') }
    .map { if (it.startsWith("www.", ignoreCase = true)) "https://$it" else it }
    .distinct()
    .toList()

@Composable
fun NoticeBoardScreen(
    groupName: String,
    tab: EventCategory,
    onTabChange: (EventCategory) -> Unit,
    posts: List<EventData>,
    canPost: Boolean,
    onBack: () -> Unit,
    onNewPost: () -> Unit,
    onDelete: (EventData) -> Unit,
    onOpenLink: (String) -> Unit
) {
    val shown = posts.filter { it.type == tab }.sortedByDescending { it.timeStamp }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Notice board", subtitle = groupName, onBack = onBack)
            TabRow(
                selectedTabIndex = tabs.indexOf(tab),
                containerColor = MaterialTheme.colorScheme.surface,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[tabs.indexOf(tab)]),
                        height = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabs.forEach { option ->
                    val count = posts.count { it.type == option }
                    Tab(
                        selected = option == tab,
                        onClick = { onTabChange(option) },
                        text = { Text(if (count > 0) "${option.plural} · $count" else option.plural) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (shown.isEmpty()) {
                EmptyState(
                    tab.icon,
                    "No ${tab.plural.lowercase()} yet",
                    if (canPost) "Posts you share here reach every member, encrypted." else "Admins post updates here. Check back later."
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.messageId }) { post ->
                        PostCard(post, canDelete = canPost, onDelete = { onDelete(post) }, onOpenLink = onOpenLink)
                    }
                }
            }
        }
        if (canPost) {
            ExtendedFloatingActionButton(
                onClick = onNewPost,
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                text = { Text("New post") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
            )
        }
    }
}

@Composable
private fun PostCard(post: EventData, canDelete: Boolean, onDelete: () -> Unit, onOpenLink: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(post.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(post.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (post.timeStamp > 0) TimeFormat.dateTime(post.timeStamp) else post.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete post", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(end = 12.dp)) {
            if (post.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(post.description, style = MaterialTheme.typography.bodyMedium)
            }
            linksIn(post.description).forEach { link ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onOpenLink(link) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        link.substringAfter("://"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open link",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Pill(if (post.visibility == Category.General) "For everyone" else "For ${post.visibility.label.lowercase()}s")
        }
    }
}

@Composable
fun NoticeBoardRoute(
    grpId: String,
    myUid: String,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onNewPost: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var tab by rememberSaveable { mutableStateOf(EventCategory.Event) }
    var groupName by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EventData?>(null) }
    val posts by groupViewModel.eventList.collectAsState()

    LaunchedEffect(grpId) {
        groupViewModel.receiveEvent(grpId, tab)
        groupViewModel.checkAdmin(grpId, myUid) { isAdmin = it }
        groupViewModel.loadGroupInfo(grpId) { info -> info?.let { groupName = it.grpName } }
    }

    NoticeBoardScreen(
        groupName = groupName,
        tab = tab,
        onTabChange = { tab = it },
        posts = posts,
        canPost = isAdmin,
        onBack = onBack,
        onNewPost = onNewPost,
        onDelete = { pendingDelete = it },
        onOpenLink = { link ->
            try {
                uriHandler.openUri(link)
            } catch (e: Exception) {
                Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        }
    )

    pendingDelete?.let { post ->
        ConfirmDialog(
            title = "Delete this post?",
            message = "\"${post.title}\" will be removed from the notice board for everyone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                groupViewModel.deleteEvent(grpId, post.messageId) { ok ->
                    if (!ok) Toast.makeText(context, "Couldn't delete the post", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ---------------------------------------------------------------- previews

val SamplePosts = run {
    val now = System.currentTimeMillis()
    val hour = 60 * 60_000L
    listOf(
        EventData(EventCategory.Event, "Line follower competition", "Friday 10 am at the university auditorium. Teams of three, register by Wednesday: www.robotics-club.org/register", "e1", Category.General, now - 2 * hour),
        EventData(EventCategory.Event, "Soldering workshop", "Beginner-friendly. Kits are provided, bring safety glasses.", "e2", Category.General, now - 50 * hour),
        EventData(EventCategory.Meeting, "Committee budget review", "Sunday 5 pm, room 402.", "m1", Category.Treasurer, now - 5 * hour),
        EventData(EventCategory.Notice, "Lab closed on Monday", "Maintenance work in the CSE building.", "n1", Category.General, now - 30 * hour),
    )
}

@Preview
@Composable
fun NoticeBoardPreview() = ClubMateTheme {
    NoticeBoardScreen("Robotics Club", EventCategory.Event, {}, SamplePosts, canPost = true, {}, {}, {}, {})
}

@Preview
@Composable
fun NoticeBoardEmptyPreview() = ClubMateTheme {
    NoticeBoardScreen("Robotics Club", EventCategory.Notice, {}, emptyList(), canPost = false, {}, {}, {}, {})
}
