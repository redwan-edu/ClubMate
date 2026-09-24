package com.example.clubmate.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Pure Compose (no Android APIs) so the auth UI can be previewed and tested off-device.

data class AuthPalette(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color,
    val onAccent: Color,
    val link: Color,
)

val LoginPalette = AuthPalette(
    backgroundTop = Color(0xFFE3F4EA),
    backgroundBottom = Color(0xFFF3EFFA),
    accent = Color(0xFF8FD3AE),
    onAccent = Color(0xFF123524),
    link = Color(0xFF2F8A5C),
)

val RegisterPalette = AuthPalette(
    backgroundTop = Color(0xFFE2EDF8),
    backgroundBottom = Color(0xFFF6EEF5),
    accent = Color(0xFF9CC4EC),
    onAccent = Color(0xFF14304D),
    link = Color(0xFF3A6EA5),
)

private val TextPrimary = Color(0xFF1F2430)
private val TextMuted = Color(0xFF7A8090)
private val FieldBorder = Color(0xFFE6E8EF)

object AuthIcons {
    val Email: ImageVector = Icons.Filled.Email
    val Password: ImageVector = Icons.Filled.Lock
    val Username: ImageVector = Icons.Filled.Person
    val Phone: ImageVector = Icons.Filled.Phone
}

@Composable
fun AuthScaffold(
    palette: AuthPalette,
    logo: Painter,
    appName: Painter,
    subtitle: String,
    footerPrompt: String,
    footerAction: String,
    onFooterClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = FontFamily.Default,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.backgroundTop, palette.backgroundBottom)))
            .then(modifier)
    ) {
        val minHeight = maxHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            Image(painter = logo, contentDescription = null, modifier = Modifier.size(112.dp))
            Spacer(Modifier.height(8.dp))
            Image(painter = appName, contentDescription = "ClubMate", modifier = Modifier.height(32.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )
            Spacer(Modifier.height(28.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = palette.accent, spotColor = palette.accent)
                    .background(Color.White, RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                content = content
            )

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                Text(text = footerPrompt, color = TextMuted, fontSize = 14.sp, fontFamily = fontFamily)
                TextButton(onClick = onFooterClick) {
                    Text(
                        text = footerAction,
                        color = palette.link,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fontFamily
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    visibleIcon: Painter? = null,
    hiddenIcon: Painter? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    fontFamily: FontFamily = FontFamily.Default,
) {
    var visible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = fontFamily, fontSize = 15.sp, color = TextPrimary),
        placeholder = { Text(text = placeholder, fontFamily = fontFamily, fontSize = 15.sp) },
        leadingIcon = {
            Icon(imageVector = leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = if (isPassword && visibleIcon != null && hiddenIcon != null) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painter = if (visible) hiddenIcon else visibleIcon,
                        contentDescription = if (visible) "Hide password" else "Show password",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFF7F8FB),
            unfocusedContainerColor = Color(0xFFF7F8FB),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedPlaceholderColor = TextMuted,
            unfocusedPlaceholderColor = TextMuted,
            focusedLeadingIconColor = TextPrimary,
            unfocusedLeadingIconColor = TextMuted,
            focusedTrailingIconColor = TextPrimary,
            unfocusedTrailingIconColor = TextMuted,
            cursorColor = TextPrimary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, FieldBorder, shape)
    )
}

@Composable
fun AuthPrimaryButton(
    text: String,
    palette: AuthPalette,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = FontFamily.Default,
) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.onAccent,
            disabledContainerColor = palette.accent.copy(alpha = 0.6f),
            disabledContentColor = palette.onAccent
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        modifier = modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = palette.onAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily)
        }
    }
}
