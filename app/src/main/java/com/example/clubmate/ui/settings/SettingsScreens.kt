package com.example.clubmate.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import com.example.clubmate.ui.theme.AvatarColors
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.AuthViewModel

/** Where "Report a problem" sends its email. */
const val SUPPORT_EMAIL = "redwan491560@gmail.com"

/** The app's version name (e.g. "1.0"), or "" if it can't be read. */
fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

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
    val version = remember { appVersionName(context) }

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

/**
 * A person on the team page. [contribution] is one line on what they built; [color] is their tile's
 * background, so everyone gets equal billing with their own colour; [zoom] frames the photo inside
 * its circle.
 */
data class TeamMember(
    val name: String,
    val role: String,
    val contribution: String,
    val email: String,
    val linkedIn: String,
    val color: Color,
    val photo: Painter? = null,
    val zoom: Float = 1f
)

/** "https://www.linkedin.com/in/someone/" -> "in/someone". */
fun linkedInHandle(url: String): String =
    url.substringAfter("linkedin.com/", url).trim('/').ifBlank { url }

private val LinkedInBlue = Color(0xFF0A66C2)
private val TileShape = RoundedCornerShape(24.dp)

/**
 * The team as a stack of equal, full-width cards, one person per row, each in their own colour (no
 * one card is bigger than another), closed by a card about the project itself. Each card lists the
 * person's role, what they built, and their email and LinkedIn, both tappable.
 */
@Composable
fun TeamScreen(
    members: List<TeamMember>,
    appVersion: String,
    onEmail: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = "The team", onBack = onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Meet the team", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            
            members.forEach { MemberTile(it, onEmail, onOpenLink, Modifier.fillMaxWidth()) }
            ProjectTile(members.size, appVersion, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MemberPhoto(member: TeamMember, size: Dp, ring: Color) {
    if (member.photo != null) {
        Image(
            member.photo, contentDescription = member.name, contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .border(3.dp, ring, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .graphicsLayer { scaleX = member.zoom; scaleY = member.zoom }
        )
    } else {
        Avatar(member.name, size = size)
    }
}

/** One person's full-width card: photo, name, role, what they built and how to reach them. */
@Composable
private fun MemberTile(member: TeamMember, onEmail: (String) -> Unit, onOpenLink: (String) -> Unit, modifier: Modifier) {
    val onColor = Color.White
    Column(
        modifier
            .clip(TileShape)
            .background(member.color)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberPhoto(member, 72.dp, onColor.copy(alpha = 0.35f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.titleLarge, color = onColor)
                Spacer(Modifier.height(6.dp))
                AccentPill(member.role, onColor)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(member.contribution, style = MaterialTheme.typography.bodyMedium, color = onColor.copy(alpha = 0.9f))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ContactLine(Icons.Rounded.AlternateEmail, member.email, onColor) { onEmail(member.email) }
                ContactLine(null, linkedInHandle(member.linkedIn), onColor) { onOpenLink(member.linkedIn) }
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onEmail(member.email) }, modifier = Modifier.size(40.dp), colors = tileButtonColors(onColor)) {
                    Icon(Icons.Rounded.MailOutline, contentDescription = "Email ${member.name}", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(onClick = { onOpenLink(member.linkedIn) }, modifier = Modifier.size(40.dp), colors = tileButtonColors(onColor)) {
                    LinkedInBadge(Modifier.size(20.dp), Color.White, LinkedInBlue, contentDescription = "${member.name} on LinkedIn")
                }
            }
        }
    }
}

/** Small translucent label for a role, readable on any of the tiles' accent colours. */
@Composable
private fun AccentPill(text: String, onColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = onColor,
        modifier = Modifier
            .clip(CircleShape)
            .background(onColor.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun tileButtonColors(onColor: Color) = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = onColor.copy(alpha = 0.18f),
    contentColor = onColor
)

/** Closing tile about the project itself. */
@Composable
private fun ProjectTile(memberCount: Int, appVersion: String, modifier: Modifier) {
    Column(
        modifier
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp)
    ) {
        BrandMark(size = 44.dp)
        Spacer(Modifier.height(12.dp))
        Text("ClubMate", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            if (appVersion.isBlank()) "3rd year project" else "Version $appVersion · 3rd year project",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
        Spacer(Modifier.weight(1f).height(12.dp))
        Text("$memberCount", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("contributors", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(8.dp))
        Text(
            "Jetpack Compose · Firebase\nX3DH + Double Ratchet",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
    }
}

/** One tappable contact detail: an icon (or the LinkedIn mark when [icon] is null) and the text. */
@Composable
private fun ContactLine(icon: ImageVector?, text: String, color: Color, maxLines: Int = 1, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp).size(14.dp))
        } else {
            LinkedInBadge(Modifier.size(14.dp), color, LinkedInBlue)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.85f), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** The LinkedIn "in" mark, drawn so no brand asset has to ship with the app. */
@Composable
private fun LinkedInBadge(modifier: Modifier, glyph: Color, background: Color, contentDescription: String? = null) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(22))
            .background(background)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val px = with(LocalDensity.current) { maxWidth.toSp() }
        Text(
            "in",
            color = glyph,
            fontWeight = FontWeight.Bold,
            fontSize = px * 0.62f,
            lineHeight = px * 0.62f,
            modifier = Modifier.offset(y = (-0.5).dp)
        )
    }
}

// ---------------------------------------------------------------- previews

val SampleTeam = listOf(
    TeamMember(
        "Redwan Hussain", "Project manager",
        "Built the app and its end-to-end encryption, and led the project from plan to release.",
        "redwan491560@gmail.com", "https://www.linkedin.com/in/redwan-hussain-edu/", AvatarColors[0]
    ),
    TeamMember(
        "Mizanur Rahman", "Database design",
        "Designed the Firebase data model for chats, groups and channels.",
        "mizan21331@gmail.com", "https://www.linkedin.com/in/mizanrahmanx/", AvatarColors[1]
    ),
    TeamMember(
        "Tonmoy Chanda", "UI design",
        "Shaped the screens, layouts and visual style of the app.",
        "tonmoychanda07@gmail.com", "https://www.linkedin.com/in/tonmoy-chanda/", AvatarColors[3]
    ),
    TeamMember(
        "Abu Adnan Shad", "QA testing",
        "Tested every feature and tracked down bugs before each release.",
        "adnanshad1035@gmail.com", "https://www.linkedin.com/in/abuadnanshad/", AvatarColors[2]
    ),
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
    TeamScreen(SampleTeam, "1.0", {}, {}, {})
}
