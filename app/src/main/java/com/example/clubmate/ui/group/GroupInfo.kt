package com.example.clubmate.ui.group

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.Routes
import com.example.clubmate.ui.chat.EncryptionCard
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.EmptyState
import com.example.clubmate.ui.components.InfoRow
import com.example.clubmate.ui.components.LinkButton
import com.example.clubmate.ui.components.ListRow
import com.example.clubmate.ui.components.Pill
import com.example.clubmate.ui.components.SearchField
import com.example.clubmate.ui.components.SectionHeader
import com.example.clubmate.ui.components.SettingsRow
import com.example.clubmate.ui.components.TimeFormat
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.Category
import com.example.clubmate.viewmodel.GroupViewmodel
import com.example.clubmate.viewmodel.UserJoinDetails

/** Admins first, then the committee, then everyone else; alphabetical within a role. */
fun List<UserJoinDetails>.sortedByRole(): List<UserJoinDetails> =
    sortedWith(compareBy<UserJoinDetails> { it.userType.rank }.thenBy { it.username.lowercase() })

private val Category.rank: Int
    get() = when (this) {
        Category.Admin -> 0
        Category.President -> 1
        Category.VicePresident -> 2
        Category.Treasurer -> 3
        Category.General -> 4
    }

@Composable
fun MemberRow(member: UserJoinDetails, onClick: (() -> Unit)?) {
    ListRow(
        title = member.username.ifBlank { member.email },
        subtitle = member.email,
        leading = { Avatar(member.username.ifBlank { member.email }, imageUrl = member.photoUrl, size = 44.dp) },
        trailing = if (member.userType != Category.General) {
            { Pill(member.userType.label, emphasized = member.userType == Category.Admin) }
        } else null,
        onClick = onClick
    )
}

// ---------------------------------------------------------------- group info

@Composable
fun GroupInfoScreen(
    details: Routes.GrpDetails,
    createdBy: String?,
    members: List<UserJoinDetails>,
    isAdmin: Boolean,
    onBack: () -> Unit,
    onCopyId: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenMembers: () -> Unit,
    onMemberClick: (UserJoinDetails) -> Unit,
    onLeave: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Group info", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(details.grpName, imageUrl = details.photoUrl, size = 96.dp, icon = Icons.Rounded.Groups)
                Spacer(Modifier.height(16.dp))
                Text(details.grpName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Group · ${members.size} members",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (details.description.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        details.description,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            InviteCard(details.grpId, onCopyId)
            Spacer(Modifier.height(12.dp))
            EncryptionCard("Messages, photos and notices in this group are end-to-end encrypted. Only members can read them.")

            SectionHeader("Group")
            SettingsRow(Icons.Rounded.Campaign, "Notice board", onOpenNotices, subtitle = "Events, meetings and notices")
            if (isAdmin) {
                SettingsRow(
                    Icons.Rounded.AdminPanelSettings, "Admin console", onOpenConsole,
                    subtitle = "Requests, members, photo and posts"
                )
            }

            SectionHeader("${members.size} members")
            members.sortedByRole().take(PREVIEW_MEMBERS).forEach { member ->
                MemberRow(member) { onMemberClick(member) }
            }
            if (members.size > PREVIEW_MEMBERS) {
                LinkButton("See all ${members.size} members", onOpenMembers, Modifier.padding(start = 8.dp))
            }

            SectionHeader("About")
            if (details.createdAt > 0) {
                InfoRow("Created", TimeFormat.date(details.createdAt), icon = Icons.Rounded.CalendarMonth)
            }
            if (!createdBy.isNullOrBlank()) InfoRow("Created by", createdBy, icon = Icons.Rounded.Person)

            Spacer(Modifier.height(8.dp))
            SettingsRow(
                Icons.AutoMirrored.Rounded.Logout, "Leave group", onLeave,
                tint = MaterialTheme.colorScheme.error, showChevron = false
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

private const val PREVIEW_MEMBERS = 5

/** The group ID is the invite: people paste it under Groups → Join a group. */
@Composable
private fun InviteCard(grpId: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onCopy)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Invite with group ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(grpId, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
        }
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy group ID", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun GroupInfoRoute(
    args: Routes.GrpDetails,
    myUid: String,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenConsole: (Routes.GrpDetails) -> Unit,
    onOpenMembers: () -> Unit,
    onMemberClick: (UserJoinDetails) -> Unit,
    onLeft: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf(args) }
    var isAdmin by remember { mutableStateOf(false) }
    var createdBy by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val members by groupViewModel.participantsList.collectAsState()

    LaunchedEffect(args.grpId) {
        groupViewModel.loadGroupInfo(args.grpId) { info -> info?.let { details = it } }
        groupViewModel.checkAdmin(args.grpId, myUid) { isAdmin = it }
        groupViewModel.getAllParticipants(args.grpId)
    }
    LaunchedEffect(details.createdBy) {
        if (details.createdBy.isNotBlank()) {
            groupViewModel.fetchUserDetailsByUid(details.createdBy) { user ->
                createdBy = user?.let { it.username.ifBlank { it.email } }
            }
        }
    }

    GroupInfoScreen(
        details = details,
        createdBy = createdBy,
        members = members,
        isAdmin = isAdmin,
        onBack = onBack,
        onCopyId = {
            clipboard.setText(AnnotatedString(details.grpId))
            Toast.makeText(context, "Group ID copied. Share it to invite people.", Toast.LENGTH_SHORT).show()
        },
        onOpenNotices = onOpenNotices,
        onOpenConsole = { onOpenConsole(details) },
        onOpenMembers = onOpenMembers,
        onMemberClick = onMemberClick,
        onLeave = { confirmLeave = true }
    )

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave ${details.grpName}?",
            message = "You'll stop receiving messages from this group. You can ask to join again later.",
            confirmText = "Leave",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                groupViewModel.leaveGroup(myUid, args.grpId) { ok ->
                    if (ok) onLeft() else Toast.makeText(context, "Couldn't leave the group", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirmLeave = false }
        )
    }
}

// ---------------------------------------------------------------- members

@Composable
fun MembersScreen(
    members: List<UserJoinDetails>,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onMemberClick: (UserJoinDetails) -> Unit
) {
    val filtered = members
        .filter {
            query.isBlank() || it.username.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true)
        }
        .sortedByRole()
    val committee = filtered.filter { it.userType != Category.General }
    val everyone = filtered.filter { it.userType == Category.General }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Members", subtitle = "${members.size} people", onBack = onBack)
        SearchField(query, onQueryChange, "Search members", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (filtered.isEmpty()) {
            EmptyState(Icons.Rounded.SearchOff, "No members found", "Try a different name or email.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (committee.isNotEmpty()) {
                item { SectionHeader("Admins and committee") }
                items(committee, key = { it.uid }) { MemberRow(it) { onMemberClick(it) } }
            }
            if (everyone.isNotEmpty()) {
                item { SectionHeader("Members") }
                items(everyone, key = { it.uid }) { MemberRow(it) { onMemberClick(it) } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun MembersRoute(
    grpId: String,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onMemberClick: (UserJoinDetails) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val members by groupViewModel.participantsList.collectAsState()
    LaunchedEffect(grpId) { groupViewModel.getAllParticipants(grpId) }
    MembersScreen(members, query, { query = it }, onBack, onMemberClick)
}

// ---------------------------------------------------------------- member detail

@Composable
fun MemberDetailScreen(
    member: UserJoinDetails?,
    canManage: Boolean,
    onBack: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onChangeRole: (Category) -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Member", onBack = onBack)
        if (member == null) return@Column
        val name = member.username.ifBlank { member.email }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(name, imageUrl = member.photoUrl, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(member.userType.label, emphasized = member.userType == Category.Admin)
                    if (member.joinData > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Joined ${TimeFormat.date(member.joinData)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            SectionHeader("Contact")
            InfoRow("Email", member.email, icon = Icons.Rounded.AlternateEmail, onCopy = { onCopy("Email", member.email) })
            if (member.phone.isNotBlank()) {
                InfoRow("Phone", member.phone, icon = Icons.Rounded.Phone, onCopy = { onCopy("Phone", member.phone) })
            }
            if (canManage) {
                SectionHeader("Role")
                Category.entries.sortedBy { it.rank }.forEach { role ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (role != member.userType) onChangeRole(role) }
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(role.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        RadioButton(selected = role == member.userType, onClick = { if (role != member.userType) onChangeRole(role) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingsRow(
                    Icons.Rounded.PersonRemove, "Remove from group", onRemove,
                    tint = MaterialTheme.colorScheme.error, showChevron = false
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun MemberDetailRoute(
    args: Routes.GroupUserDetails,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var member by remember { mutableStateOf<UserJoinDetails?>(null) }
    var viewerIsAdmin by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(args.userId, reload) {
        groupViewModel.getParticipantDetails(args.grpId, args.userId) { member = it }
    }
    LaunchedEffect(args.currentUserId) {
        groupViewModel.checkAdmin(args.grpId, args.currentUserId) { viewerIsAdmin = it }
    }

    MemberDetailScreen(
        member = member,
        canManage = viewerIsAdmin && args.userId != args.currentUserId,
        onBack = onBack,
        onCopy = { label, value ->
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        },
        onChangeRole = { role ->
            groupViewModel.updateUserRole(args.userId, role, args.grpId) { ok ->
                if (ok) reload++
                Toast.makeText(context, if (ok) "Role changed to ${role.label}" else "Couldn't change the role", Toast.LENGTH_SHORT).show()
            }
        },
        onRemove = { confirmRemove = true }
    )

    val current = member
    if (confirmRemove && current != null) {
        ConfirmDialog(
            title = "Remove ${current.username.ifBlank { current.email }}?",
            message = "They'll lose access to this group's messages and notices.",
            confirmText = "Remove",
            destructive = true,
            onConfirm = {
                confirmRemove = false
                groupViewModel.removeParticipants(current.email, args.grpId) { ok ->
                    Toast.makeText(context, if (ok) "Member removed" else "Couldn't remove this member", Toast.LENGTH_SHORT).show()
                    if (ok) onBack()
                }
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

// ---------------------------------------------------------------- previews

private val day = 24 * 60 * 60_000L
val SampleMembers = listOf(
    UserJoinDetails("redwan491560@gmail.com", "", "", "r", "Redwan Hussain", System.currentTimeMillis() - 90 * day, Category.Admin),
    UserJoinDetails("mizan21331@gmail.com", "+880 1712 345678", "", "m", "Mizanur Rahman", System.currentTimeMillis() - 80 * day, Category.Treasurer),
    UserJoinDetails("tonmoychanda07@gmail.com", "", "", "t", "Tonmoy Chanda", System.currentTimeMillis() - 60 * day, Category.President),
    UserJoinDetails("adnanshad1035@gmail.com", "", "", "a", "Abu Adnan Shad", System.currentTimeMillis() - 30 * day, Category.General),
    UserJoinDetails("nadia@university.edu", "", "", "n", "Nadia Islam", System.currentTimeMillis() - 20 * day, Category.General),
    UserJoinDetails("farhan@university.edu", "", "", "f", "Farhan Ahmed", System.currentTimeMillis() - 10 * day, Category.General),
    UserJoinDetails("sadia@university.edu", "", "", "s", "Sadia Karim", System.currentTimeMillis() - 5 * day, Category.General),
)

val SampleGroupDetails = Routes.GrpDetails(
    grpId = "93beefc4b3bf4d42a1d1",
    description = "Building robots, one line follower at a time. Weekly meetups in the CSE lab.",
    grpName = "Robotics Club",
    createdAt = System.currentTimeMillis() - 120 * day,
    createdBy = "r"
)

@Preview
@Composable
fun GroupInfoPreview() = ClubMateTheme {
    GroupInfoScreen(
        SampleGroupDetails, "Redwan Hussain", SampleMembers, isAdmin = true,
        onBack = {}, onCopyId = {}, onOpenNotices = {}, onOpenConsole = {}, onOpenMembers = {}, onMemberClick = {}, onLeave = {}
    )
}

@Preview
@Composable
fun MembersPreview() = ClubMateTheme {
    MembersScreen(SampleMembers, "", {}, {}, {})
}

@Preview
@Composable
fun MemberDetailPreview() = ClubMateTheme {
    MemberDetailScreen(SampleMembers[1], canManage = true, onBack = {}, onCopy = { _, _ -> }, onChangeRole = {}, onRemove = {})
}
