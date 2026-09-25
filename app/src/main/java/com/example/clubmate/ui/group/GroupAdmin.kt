package com.example.clubmate.ui.group

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.Routes
import com.example.clubmate.ui.components.AppTextField
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.InlineError
import com.example.clubmate.ui.components.ListRow
import com.example.clubmate.ui.components.PrimaryButton
import com.example.clubmate.ui.components.SectionHeader
import com.example.clubmate.ui.components.SettingsRow
import com.example.clubmate.ui.components.TimeFormat
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.util.Category
import com.example.clubmate.util.EventCategory
import com.example.clubmate.viewmodel.GroupViewmodel
import com.example.clubmate.viewmodel.RequestMap

/** Page with a top bar, a scrolling form and a pinned primary action at the bottom. */
@Composable
internal fun FormPage(
    title: String,
    onBack: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        AppTopBar(title = title, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
        Box(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { action() }
    }
}

/** Generated ID with a refresh button; used for new groups and channels. */
@Composable
internal fun GeneratedIdCard(label: String, id: String?, hint: String, onRegenerate: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(
                    id ?: "Generating…",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                )
            }
            IconButton(onClick = onRegenerate) { Icon(Icons.Rounded.Refresh, contentDescription = "New ID") }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp)
        )
    }
}

// ---------------------------------------------------------------- create group

@Composable
fun CreateGroupScreen(
    groupId: String?,
    onRegenerateId: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    loading: Boolean,
    onCreate: () -> Unit,
    onBack: () -> Unit
) {
    FormPage(
        title = "New group",
        onBack = onBack,
        action = {
            PrimaryButton("Create group", onCreate, loading = loading, enabled = name.isNotBlank() && groupId != null)
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name.ifBlank { "Group" }, size = 88.dp, icon = if (name.isBlank()) Icons.Rounded.Groups else null)
            Spacer(Modifier.height(10.dp))
            Text(
                "You'll be the group's admin. Add a photo later from the admin console.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        AppTextField(name, onNameChange, "Group name", leadingIcon = Icons.Rounded.Title)
        AppTextField(
            description, onDescriptionChange, "Description (optional)",
            leadingIcon = Icons.Rounded.Info, singleLine = false, minLines = 3, imeAction = ImeAction.Default
        )
        GeneratedIdCard("Group ID", groupId, "People join by entering this ID. You can copy it later from Group info.", onRegenerateId)
    }
}

@Composable
fun CreateGroupRoute(
    myUid: String,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onCreated: (grpId: String) -> Unit
) {
    val context = LocalContext.current
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (groupId == null) groupViewModel.requestId { groupId = it } }

    CreateGroupScreen(
        groupId = groupId,
        onRegenerateId = { groupId = null; groupViewModel.requestId { groupId = it } },
        name = name, onNameChange = { name = it },
        description = description, onDescriptionChange = { description = it },
        loading = loading,
        onCreate = {
            val id = groupId ?: return@CreateGroupScreen
            loading = true
            groupViewModel.createGroup(id, myUid, name.trim(), description.trim()) { created ->
                loading = false
                if (created != null) onCreated(created.grpId)
                else Toast.makeText(context, "Couldn't create the group. Try again.", Toast.LENGTH_SHORT).show()
            }
        },
        onBack = onBack
    )
}

// ---------------------------------------------------------------- add member

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddMemberScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    role: Category,
    onRoleChange: (Category) -> Unit,
    loading: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onBack: () -> Unit
) {
    FormPage(
        title = "Add member",
        onBack = onBack,
        action = { PrimaryButton("Add to group", onAdd, loading = loading, enabled = email.isNotBlank()) }
    ) {
        Text(
            "Add someone who already has a ClubMate account. They'll get the group's encryption key automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            email, onEmailChange, "Email address",
            leadingIcon = Icons.Rounded.AlternateEmail, keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done, onImeAction = onAdd
        )
        if (error != null) InlineError(error)
        Text("Role", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        RoleChips(role, onRoleChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleChips(selected: Category, onSelect: (Category) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Category.General, Category.Admin, Category.President, Category.VicePresident, Category.Treasurer).forEach { role ->
            FilterChip(
                selected = role == selected,
                onClick = { onSelect(role) },
                label = { Text(role.label) },
                leadingIcon = if (role == selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
fun AddMemberRoute(grpId: String, groupViewModel: GroupViewmodel, onBack: () -> Unit) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Category.General) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AddMemberScreen(
        email = email, onEmailChange = { email = it; error = null },
        role = role, onRoleChange = { role = it },
        loading = loading, error = error,
        onAdd = {
            if (email.isBlank() || loading) return@AddMemberScreen
            loading = true
            groupViewModel.addParticipants(email = email.trim(), category = role, grpId = grpId) { ok ->
                loading = false
                if (ok) {
                    Toast.makeText(context, "Member added", Toast.LENGTH_SHORT).show()
                    email = ""
                } else {
                    error = "No ClubMate account uses this email."
                }
            }
        },
        onBack = onBack
    )
}

// ---------------------------------------------------------------- admin console

@Composable
fun ConsoleScreen(
    groupName: String,
    photoUrl: String?,
    uploadingPhoto: Boolean,
    memberCount: Int,
    adminCount: Int,
    committeeCount: Int,
    requests: List<RequestMap>,
    onBack: () -> Unit,
    onChangePhoto: () -> Unit,
    onAddMember: () -> Unit,
    onManageMembers: () -> Unit,
    onNewPost: () -> Unit,
    onApprove: (RequestMap) -> Unit,
    onDecline: (RequestMap) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Admin console", subtitle = groupName, onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.clickable(enabled = !uploadingPhoto, onClick = onChangePhoto)) {
                    Avatar(groupName, imageUrl = photoUrl, size = 72.dp, icon = Icons.Rounded.Groups)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PhotoCamera, contentDescription = "Change group photo",
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(groupName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (uploadingPhoto) "Uploading photo…" else "Tap the photo to change it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatTile("Members", memberCount, Modifier.weight(1f))
                StatTile("Admins", adminCount, Modifier.weight(1f))
                StatTile("Committee", committeeCount, Modifier.weight(1f))
                StatTile("Requests", requests.size, Modifier.weight(1f), highlight = requests.isNotEmpty())
            }

            SectionHeader(if (requests.isEmpty()) "Join requests" else "Join requests · ${requests.size}")
            if (requests.isEmpty()) {
                Text(
                    "No pending requests. Share the group ID so people can ask to join.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            requests.sortedByDescending { it.sentTime }.forEach { request ->
                ListRow(
                    title = request.username.ifBlank { request.email },
                    subtitle = if (request.sentTime > 0) "${request.email} · ${TimeFormat.listTimestamp(request.sentTime)}" else request.email,
                    leading = { Avatar(request.username.ifBlank { request.email }, size = 44.dp) },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(onClick = { onDecline(request) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Decline")
                            }
                            FilledIconButton(onClick = { onApprove(request) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Check, contentDescription = "Approve")
                            }
                        }
                    }
                )
            }

            SectionHeader("Manage")
            SettingsRow(Icons.Rounded.PersonAdd, "Add member", onAddMember, subtitle = "Add someone by email")
            SettingsRow(Icons.Rounded.ManageAccounts, "Members and roles", onManageMembers, subtitle = "Change roles or remove members")
            SettingsRow(Icons.Rounded.PostAdd, "New post", onNewPost, subtitle = "Share an event, meeting or notice")
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConsoleRoute(
    args: Routes.Console,
    groupViewModel: GroupViewmodel,
    onBack: () -> Unit,
    onAddMember: () -> Unit,
    onManageMembers: () -> Unit,
    onNewPost: () -> Unit
) {
    val context = LocalContext.current
    var photoUrl by remember { mutableStateOf(args.image) }
    var uploading by remember { mutableStateOf(false) }
    val members by groupViewModel.participantsList.collectAsState()
    val requests by groupViewModel.requestList.collectAsState()

    LaunchedEffect(args.grpId) {
        groupViewModel.getAllParticipants(args.grpId)
        groupViewModel.listenToRequest(args.grpId)
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            uploading = true
            groupViewModel.updateGroupProfilePicture(args.grpId, uri) { ok ->
                uploading = false
                if (ok) groupViewModel.loadGroupInfo(args.grpId) { info -> info?.let { photoUrl = it.photoUrl } }
                Toast.makeText(context, if (ok) "Group photo updated" else "Couldn't update the photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ConsoleScreen(
        groupName = args.grpName,
        photoUrl = photoUrl,
        uploadingPhoto = uploading,
        memberCount = members.size,
        adminCount = members.count { it.userType == Category.Admin },
        committeeCount = members.count { it.userType != Category.Admin && it.userType != Category.General },
        requests = requests,
        onBack = onBack,
        onChangePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onAddMember = onAddMember,
        onManageMembers = onManageMembers,
        onNewPost = onNewPost,
        onApprove = {
            groupViewModel.acceptRequest(args.grpId, it)
            Toast.makeText(context, "${it.username.ifBlank { it.email }} joined the group", Toast.LENGTH_SHORT).show()
        },
        onDecline = { groupViewModel.declineRequest(args.grpId, it) }
    )
}

// ---------------------------------------------------------------- new post

val EventCategory.icon: ImageVector
    get() = when (this) {
        EventCategory.Event -> Icons.Rounded.Event
        EventCategory.Meeting -> Icons.Rounded.Videocam
        EventCategory.Notice -> Icons.Rounded.Campaign
    }

/** Who a post is meant for; General means the whole group. */
val Category.audience: String get() = if (this == Category.General) "Everyone" else label

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPostScreen(
    type: EventCategory,
    onTypeChange: (EventCategory) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    audience: Category,
    onAudienceChange: (Category) -> Unit,
    loading: Boolean,
    onPost: () -> Unit,
    onBack: () -> Unit
) {
    FormPage(
        title = "New post",
        onBack = onBack,
        action = { PrimaryButton("Post to notice board", onPost, loading = loading, enabled = title.isNotBlank() && body.isNotBlank()) }
    ) {
        Text("Type", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(EventCategory.Event, EventCategory.Meeting, EventCategory.Notice).forEach { option ->
                FilterChip(
                    selected = option == type,
                    onClick = { onTypeChange(option) },
                    label = { Text(option.name) },
                    leadingIcon = { Icon(option.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                )
            }
        }
        AppTextField(title, onTitleChange, "Title", leadingIcon = Icons.Rounded.Title)
        AppTextField(
            body, onBodyChange,
            when (type) {
                EventCategory.Meeting -> "Details and meeting link"
                EventCategory.Event -> "Date, place and details"
                EventCategory.Notice -> "Message"
            },
            singleLine = false, minLines = 5, imeAction = ImeAction.Default,
            supportingText = "Links are shown as buttons, so members can open them in one tap."
        )
        Text("Audience", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Category.General, Category.Admin, Category.President, Category.VicePresident, Category.Treasurer).forEach { role ->
                FilterChip(selected = role == audience, onClick = { onAudienceChange(role) }, label = { Text(role.audience) })
            }
        }
        Text(
            "Posts are end-to-end encrypted like messages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun NewPostRoute(grpId: String, groupViewModel: GroupViewmodel, onBack: () -> Unit) {
    val context = LocalContext.current
    var type by rememberSaveable { mutableStateOf(EventCategory.Notice) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf(Category.General) }
    var loading by remember { mutableStateOf(false) }
    val sendError by groupViewModel.sendError.collectAsState()

    LaunchedEffect(sendError) {
        sendError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            groupViewModel.clearSendError()
        }
    }

    NewPostScreen(
        type = type, onTypeChange = { type = it },
        title = title, onTitleChange = { title = it },
        body = body, onBodyChange = { body = it },
        audience = audience, onAudienceChange = { audience = it },
        loading = loading,
        onPost = {
            loading = true
            groupViewModel.uploadEvent(type, title.trim(), body.trim(), audience, grpId) { ok ->
                loading = false
                if (ok) {
                    Toast.makeText(context, "Posted", Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    Toast.makeText(context, "Couldn't post. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onBack = onBack
    )
}

// ---------------------------------------------------------------- previews

@Preview
@Composable
fun CreateGroupPreview() = ClubMateTheme {
    CreateGroupScreen("a41f09c2d7e84b1c9f3e", {}, "Debate Society", {}, "Weekly debates and inter-university tournaments.", {}, false, {}, {})
}

@Preview
@Composable
fun AddMemberPreview() = ClubMateTheme {
    AddMemberScreen("nadia@university.edu", {}, Category.General, {}, false, null, {}, {})
}

@Preview
@Composable
fun ConsolePreview() = ClubMateTheme {
    val now = System.currentTimeMillis()
    ConsoleScreen(
        "Robotics Club", null, false, 24, 2, 3,
        listOf(
            RequestMap("sabbir@university.edu", "u1", "Sabbir Hossain", now - 25 * 60_000),
            RequestMap("ayesha@university.edu", "u2", "Ayesha Siddiqua", now - 26 * 60 * 60_000),
        ),
        {}, {}, {}, {}, {}, {}, {}
    )
}

@Preview
@Composable
fun NewPostPreview() = ClubMateTheme {
    NewPostScreen(
        EventCategory.Meeting, {}, "Weekly sync", {},
        "Sunday 5 pm in the CSE lab. Join online: https://meet.google.com/abc-defg-hij", {},
        Category.General, {}, false, {}, {}
    )
}
