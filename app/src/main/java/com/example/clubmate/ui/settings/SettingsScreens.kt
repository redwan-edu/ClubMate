package com.example.clubmate.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.e2ee.E2eeManager
import com.example.clubmate.ui.components.AppTopBar
import com.example.clubmate.ui.components.Avatar
import com.example.clubmate.ui.components.BrandMark
import com.example.clubmate.ui.components.ConfirmDialog
import com.example.clubmate.ui.components.InfoRow
import com.example.clubmate.ui.components.ListDivider
import com.example.clubmate.ui.components.SectionHeader
import com.example.clubmate.ui.components.SettingsRow
import com.example.clubmate.ui.components.TabHeader
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.AuthViewModel

/** Where "Report a problem" sends its email. */
const val SUPPORT_EMAIL = "redwan491560@gmail.com"

// ---------------------------------------------------------------- settings tab

@Composable
fun SettingsTab(
    name: String,
    email: String,
    photoUrl: String?,
    version: String,
    onProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onTeam: () -> Unit,
    onReportProblem: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("Settings")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onProfile)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name.ifBlank { email }, imageUrl = photoUrl, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "Your profile" }, style = MaterialTheme.typography.titleLarge)
                Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ListDivider()
        SectionHeader("Account")
        SettingsRow(Icons.Rounded.Person, "Profile", onProfile, subtitle = "Photo, name, email and phone")
        SettingsRow(Icons.Rounded.Shield, "Privacy and security", onPrivacy, subtitle = "Encryption and your key fingerprint")
        SectionHeader("About")
        SettingsRow(Icons.Rounded.Groups, "The team", onTeam, subtitle = "The people who built ClubMate")
        SettingsRow(Icons.Rounded.BugReport, "Report a problem", onReportProblem, subtitle = "Email the developers")
        Spacer(Modifier.height(8.dp))
        SettingsRow(
            Icons.AutoMirrored.Rounded.Logout, "Sign out", onSignOut,
            tint = MaterialTheme.colorScheme.error, showChevron = false
        )
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandMark(size = 32.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                "ClubMate $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsTabRoute(
    authViewModel: AuthViewModel,
    onProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onTeam: () -> Unit
) {
    val context = LocalContext.current
    val user by authViewModel.userData.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    SettingsTab(
        name = user?.username.orEmpty(),
        email = user?.email.orEmpty(),
        photoUrl = user?.photoUrl,
        version = version,
        onProfile = onProfile,
        onPrivacy = onPrivacy,
        onTeam = onTeam,
        onReportProblem = {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "ClubMate $version: problem report")
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No email app found. Write to $SUPPORT_EMAIL", Toast.LENGTH_LONG).show()
            }
        },
        onSignOut = { confirmSignOut = true }
    )

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Your encrypted chat history stays on this device and comes back when you sign in again.",
            confirmText = "Sign out",
            destructive = true,
            onConfirm = {
                confirmSignOut = false
                authViewModel.signOut()
            },
            onDismiss = { confirmSignOut = false }
        )
    }
}

// ---------------------------------------------------------------- profile

@Composable
fun ProfileScreen(
    name: String,
    email: String,
    phone: String,
    photoUrl: String?,
    uploading: Boolean,
    onChangePhoto: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Profile", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.clickable(enabled = !uploading, onClick = onChangePhoto)) {
                    Avatar(name.ifBlank { email }, imageUrl = photoUrl, size = 112.dp)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-2).dp, y = (-2).dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PhotoCamera, contentDescription = "Change photo",
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (uploading) "Uploading photo…" else "Tap the photo to change it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SectionHeader("Details")
            InfoRow("Name", name, icon = Icons.Rounded.Person, onCopy = { onCopy("Name", name) })
            InfoRow("Email", email, icon = Icons.Rounded.AlternateEmail, onCopy = { onCopy("Email", email) })
            if (phone.isNotBlank()) InfoRow("Phone", phone, icon = Icons.Rounded.Phone, onCopy = { onCopy("Phone", phone) })
            Text(
                "People can find you by your email or name to start a chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun ProfileRoute(authViewModel: AuthViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val user by authViewModel.userData.collectAsState()
    var uploading by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val uid = user?.uid
        if (uri != null && !uid.isNullOrEmpty()) {
            uploading = true
            authViewModel.uploadImage(uid, uri) { ok ->
                uploading = false
                if (ok) authViewModel.checkAuthenticationStatus() // reloads the profile with the new photo
                Toast.makeText(context, if (ok) "Profile photo updated" else "Couldn't upload the photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ProfileScreen(
        name = user?.username.orEmpty(),
        email = user?.email.orEmpty(),
        phone = user?.phone.orEmpty(),
        photoUrl = user?.photoUrl,
        uploading = uploading,
        onChangePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onCopy = { label, value ->
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        },
        onBack = onBack
    )
}

// ---------------------------------------------------------------- privacy and security

@Composable
fun PrivacyScreen(fingerprint: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "Privacy and security", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("End-to-end encrypted", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Messages are locked on your phone and unlocked only on the recipient's. " +
                        "ClubMate's servers store scrambled data they can't read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            SectionHeader("How it works")
            Explainer(
                Icons.Rounded.Person, "Chats",
                "X3DH key agreement and the Double Ratchet. Every message has its own key, so an old key can't unlock new messages and a stolen key can't unlock old ones."
            )
            Explainer(
                Icons.Rounded.Groups, "Groups",
                "A shared group key, sent to each member over their encrypted chat. It changes whenever someone joins or leaves, and every message is signed by its sender."
            )
            Explainer(
                Icons.Rounded.Key, "Private channels",
                "A key made from the channel password with PBKDF2. Only a salted check value is stored, never the password."
            )
            Explainer(
                Icons.Rounded.PhotoCamera, "Photos",
                "Encrypted on your phone before upload with a fresh key that travels inside the encrypted message."
            )
            Explainer(
                Icons.Rounded.VisibilityOff, "Incognito and vanishing messages",
                "Incognito chats are deleted from the server when you leave incognito. Channel messages are deleted once they've been read."
            )

            SectionHeader("Your key fingerprint")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    fingerprint ?: "Not available yet",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your private keys never leave this phone. To check a contact, open their profile and compare the safety number with them in person.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Explainer(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrivacyRoute(myUid: String, onBack: () -> Unit) {
    var fingerprint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(myUid) {
        fingerprint = try {
            E2eeManager.myFingerprint(myUid)
        } catch (e: Exception) {
            null
        }
    }
    PrivacyScreen(fingerprint, onBack)
}

// ---------------------------------------------------------------- team

/** A developer on the team page; [zoom] frames the photo inside the circle. */
data class TeamMember(val name: String, val role: String, val email: String, val photo: Painter? = null, val zoom: Float = 1.1f)

@Composable
fun TeamScreen(members: List<TeamMember>, onEmail: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "The team", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                "ClubMate was built as a 3rd year project: a club messenger with end-to-end encryption.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                members.forEach { member -> TeamCard(member, onEmail) }
            }
        }
    }
}

@Composable
private fun TeamCard(member: TeamMember, onEmail: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (member.photo != null) {
            Image(
                member.photo, contentDescription = member.name, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .graphicsLayer { scaleX = member.zoom; scaleY = member.zoom }
            )
        } else {
            Avatar(member.name, size = 56.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name, style = MaterialTheme.typography.titleMedium)
            Text(member.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(member.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onEmail(member.email) }) {
            Icon(Icons.Rounded.MailOutline, contentDescription = "Email ${member.name}")
        }
    }
}

// ---------------------------------------------------------------- previews

val SampleTeam = listOf(
    TeamMember("Redwan Hussain", "Developer", "redwan491560@gmail.com"),
    TeamMember("Mizanur Rahman", "Developer", "mizan21331@gmail.com"),
    TeamMember("Tonmoy Chanda", "Developer", "tonmoychanda07@gmail.com"),
    TeamMember("Abu Adnan Shad", "Developer", "adnanshad1035@gmail.com"),
)

@Preview
@Composable
fun SettingsTabPreview() = ClubMateTheme {
    SettingsTab("Redwan Hussain", "redwan491560@gmail.com", null, "1.0", {}, {}, {}, {}, {})
}

@Preview
@Composable
fun ProfilePreview() = ClubMateTheme {
    ProfileScreen("Redwan Hussain", "redwan491560@gmail.com", "+880 1700 000000", null, false, {}, { _, _ -> }, {})
}

@Preview
@Composable
fun PrivacyPreview() = ClubMateTheme {
    PrivacyScreen("3F 9A 12 C4 7B 0E D5 61", {})
}

@Preview
@Composable
fun TeamPreview() = ClubMateTheme {
    TeamScreen(SampleTeam, {}, {})
}
