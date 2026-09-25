package com.example.clubmate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.EmptyState
import com.example.clubmate.ui.components.ListRow
import com.example.clubmate.ui.components.OfflineBanner
import com.example.clubmate.ui.components.PrimaryButton
import com.example.clubmate.ui.components.SearchField
import com.example.clubmate.ui.components.TabHeader
import com.example.clubmate.ui.components.TimeFormat
import com.example.clubmate.ui.theme.ClubMateTheme

enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Chats("Chats", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    Groups("Groups", Icons.Outlined.Groups, Icons.Rounded.Groups),
    Channels("Channels", Icons.Outlined.Lock, Icons.Rounded.Lock),
    Settings("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
}

/** One row of the chat list. */
data class ChatSummary(
    val chatId: String,
    val peerUid: String,
    val name: String,
    val email: String,
    val photoUrl: String,
    val preview: String,
    val isPhoto: Boolean,
    val timestamp: Long
)

/** One row of the group list. */
data class GroupSummary(
    val grpId: String,
    val name: String,
    val photoUrl: String,
    val preview: String,
    val isPhoto: Boolean,
    val timestamp: Long
)

/**
 * The main screen: four tabs behind a bottom navigation bar. Channels and Settings are passed in as
 * content so this screen stays free of their state.
 */
@Composable
fun HomeScreen(
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    online: Boolean,
    chats: List<ChatSummary>,
    groups: List<GroupSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenGroup: (GroupSummary) -> Unit,
    onNewChat: () -> Unit,
    onGroupActions: () -> Unit,
    channelsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { HomeNavigationBar(tab, onTabChange) },
        floatingActionButton = {
            when (tab) {
                HomeTab.Chats -> HomeFab(Icons.Rounded.AddComment, "New chat", onNewChat)
                HomeTab.Groups -> HomeFab(Icons.Rounded.GroupAdd, "Create or join a group", onGroupActions)
                else -> Unit
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                HomeTab.Chats -> ChatsTab(chats, query, onQueryChange, online, onOpenChat, onNewChat)
                HomeTab.Groups -> GroupsTab(groups, query, onQueryChange, online, onOpenGroup, onGroupActions)
                HomeTab.Channels -> channelsContent()
                HomeTab.Settings -> settingsContent()
            }
        }
    }
}

@Composable
private fun HomeNavigationBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            HomeTab.values().forEach { tab ->
                val isSelected = tab == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun HomeFab(icon: ImageVector, description: String, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp)
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun ListTabLayout(
    title: String,
    searchHint: String,
    query: String,
    onQueryChange: (String) -> Unit,
    online: Boolean,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TabHeader(title)
        SearchField(query, onQueryChange, searchHint, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (!online) {
            Spacer(Modifier.height(6.dp))
            OfflineBanner()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ChatsTab(
    chats: List<ChatSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    online: Boolean,
    onOpenChat: (ChatSummary) -> Unit,
    onNewChat: () -> Unit
) {
    ListTabLayout("Chats", "Search chats", query, onQueryChange, online) {
        when {
            chats.isEmpty() && query.isNotBlank() -> EmptyState(
                Icons.Rounded.SearchOff, "No matches", "No chat matches \"$query\"."
            )

            chats.isEmpty() -> EmptyState(
                icon = Icons.Rounded.ChatBubble,
                title = "No conversations yet",
                message = "Start a private chat with anyone on ClubMate. Every message is end-to-end encrypted.",
                action = { PrimaryButton("Start a chat", onNewChat, Modifier) }
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(chats, key = { it.chatId }) { chat ->
                    ListRow(
                        title = chat.name,
                        subtitle = chat.preview,
                        subtitleIcon = if (chat.isPhoto) Icons.Rounded.Image else null,
                        trailingText = TimeFormat.listTimestamp(chat.timestamp),
                        leading = { Avatar(chat.name, imageUrl = chat.photoUrl, size = 52.dp) },
                        onClick = { onOpenChat(chat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsTab(
    groups: List<GroupSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    online: Boolean,
    onOpenGroup: (GroupSummary) -> Unit,
    onGroupActions: () -> Unit
) {
    ListTabLayout("Groups", "Search groups", query, onQueryChange, online) {
        when {
            groups.isEmpty() && query.isNotBlank() -> EmptyState(
                Icons.Rounded.SearchOff, "No matches", "No group matches \"$query\"."
            )

            groups.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Groups,
                title = "No groups yet",
                message = "Create a group for your club, or join one with an invite ID from an admin.",
                action = { PrimaryButton("Create or join", onGroupActions, Modifier) }
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(groups, key = { it.grpId }) { group ->
                    ListRow(
                        title = group.name,
                        subtitle = group.preview.ifEmpty { "No messages yet" },
                        subtitleIcon = if (group.isPhoto) Icons.Rounded.Image else null,
                        trailingText = TimeFormat.listTimestamp(group.timestamp),
                        leading = {
                            Avatar(group.name, imageUrl = group.photoUrl, size = 52.dp, icon = Icons.Rounded.Groups)
                        },
                        onClick = { onOpenGroup(group) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- previews

private val now = System.currentTimeMillis()
private fun minutesAgo(m: Long) = now - m * 60_000

val SampleChats = listOf(
    ChatSummary("c1", "u1", "Mizanur Rahman", "mizan@uni.edu", "", "You: Agreed. Let's do it tomorrow", false, minutesAgo(3)),
    ChatSummary("c2", "u2", "Tonmoy Chanda", "tonmoy@uni.edu", "", "Photo", true, minutesAgo(64)),
    ChatSummary("c3", "u3", "Abu Adnan Shad", "adnan@uni.edu", "", "See you at the fest 🎉", false, minutesAgo(60 * 20)),
    ChatSummary("c4", "u4", "Nusrat Jahan", "nusrat@uni.edu", "", "Can you share the slides from today?", false, minutesAgo(60 * 50)),
    ChatSummary("c5", "u5", "Farhan Ahmed", "farhan@uni.edu", "", "You: Thanks!", false, minutesAgo(60 * 24 * 4)),
    ChatSummary("c6", "u6", "Sadia Islam", "sadia@uni.edu", "", "The budget sheet is ready for review", false, minutesAgo(60 * 24 * 40)),
)

val SampleGroups = listOf(
    GroupSummary("g1", "Robotics Club", "", "Meeting moved to 6 pm", false, minutesAgo(12)),
    GroupSummary("g2", "Debate Society", "", "Photo", true, minutesAgo(60 * 5)),
    GroupSummary("g3", "CSE Batch '26", "", "Assignment deadline extended to Friday", false, minutesAgo(60 * 26)),
    GroupSummary("g4", "Photography Club", "", "", false, 0),
)

@Preview
@Composable
fun HomeChatsPreview() = ClubMateTheme {
    HomeScreen(
        HomeTab.Chats, {}, online = true, chats = SampleChats, groups = SampleGroups, query = "", onQueryChange = {},
        onOpenChat = {}, onOpenGroup = {}, onNewChat = {}, onGroupActions = {}, channelsContent = {}, settingsContent = {}
    )
}

@Preview
@Composable
fun HomeGroupsPreview() = ClubMateTheme {
    HomeScreen(
        HomeTab.Groups, {}, online = false, chats = SampleChats, groups = SampleGroups, query = "", onQueryChange = {},
        onOpenChat = {}, onOpenGroup = {}, onNewChat = {}, onGroupActions = {}, channelsContent = {}, settingsContent = {}
    )
}

@Preview
@Composable
fun HomeEmptyPreview() = ClubMateTheme {
    HomeScreen(
        HomeTab.Chats, {}, online = true, chats = emptyList(), groups = emptyList(), query = "", onQueryChange = {},
        onOpenChat = {}, onOpenGroup = {}, onNewChat = {}, onGroupActions = {}, channelsContent = {}, settingsContent = {}
    )
}
