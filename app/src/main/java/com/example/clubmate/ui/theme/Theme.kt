package com.example.clubmate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Palette.Blue500,
    onPrimary = Palette.Grey0,
    primaryContainer = Palette.Blue50,
    onPrimaryContainer = Palette.Blue900,
    secondary = Palette.Grey700,
    onSecondary = Palette.Grey0,
    secondaryContainer = Palette.Grey50,
    onSecondaryContainer = Palette.Grey900,
    tertiary = Palette.Green500,
    background = Palette.Grey0,
    onBackground = Palette.Grey900,
    surface = Palette.Grey0,
    onSurface = Palette.Grey900,
    surfaceVariant = Palette.Grey50,
    onSurfaceVariant = Palette.Grey500,
    surfaceContainerLowest = Palette.Grey0,
    surfaceContainerLow = Palette.Grey25,
    surfaceContainer = Palette.Grey50,
    surfaceContainerHigh = Palette.Grey100,
    surfaceContainerHighest = Palette.Grey100,
    inverseSurface = Palette.Grey900,
    inverseOnSurface = Palette.Grey0,
    outline = Palette.Grey200,
    outlineVariant = Palette.Grey100,
    error = Palette.Red500,
    onError = Palette.Grey0,
    errorContainer = Palette.Red50,
    onErrorContainer = Palette.Red500,
    scrim = Color(0x99000000)
)

private val DarkColors = darkColorScheme(
    primary = Palette.Blue400,
    onPrimary = Palette.Grey0,
    primaryContainer = Color(0xFF232C5C),
    onPrimaryContainer = Palette.Blue100,
    secondary = Palette.Night700,
    onSecondary = Palette.Night0,
    secondaryContainer = Palette.Night200,
    onSecondaryContainer = Palette.Night900,
    tertiary = Palette.Green500,
    background = Palette.Night0,
    onBackground = Palette.Night900,
    surface = Palette.Night0,
    onSurface = Palette.Night900,
    surfaceVariant = Palette.Night100,
    onSurfaceVariant = Palette.Night500,
    surfaceContainerLowest = Palette.Night0,
    surfaceContainerLow = Palette.Night50,
    surfaceContainer = Palette.Night100,
    surfaceContainerHigh = Palette.Night200,
    surfaceContainerHighest = Palette.Night300,
    inverseSurface = Palette.Night900,
    inverseOnSurface = Palette.Night0,
    outline = Palette.Night300,
    outlineVariant = Palette.Night200,
    error = Color(0xFFFF6B6F),
    onError = Palette.Night0,
    errorContainer = Palette.Red900,
    onErrorContainer = Color(0xFFFFB3B5),
    scrim = Color(0xCC000000)
)

/** Colours Material doesn't have a slot for: chat bubbles and the chat background. */
@Immutable
data class ChatColors(
    val background: Color,
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val pill: Color,
    val incognitoBackground: Color,
    val incognitoBubble: Color,
    val onIncognito: Color
)

private val LightChatColors = ChatColors(
    background = Palette.Grey50,
    outgoingBubble = Palette.Blue500,
    onOutgoingBubble = Palette.Grey0,
    incomingBubble = Palette.Grey0,
    onIncomingBubble = Palette.Grey900,
    pill = Palette.Grey0,
    incognitoBackground = Color(0xFF151A26),
    incognitoBubble = Color(0xFF242B3B),
    onIncognito = Color(0xFFE9ECF3)
)

private val DarkChatColors = ChatColors(
    background = Palette.Night0,
    outgoingBubble = Palette.Blue500,
    onOutgoingBubble = Palette.Grey0,
    incomingBubble = Palette.Night200,
    onIncomingBubble = Palette.Night900,
    pill = Palette.Night100,
    incognitoBackground = Color(0xFF080A10),
    incognitoBubble = Color(0xFF1A1F2C),
    onIncognito = Color(0xFFE9ECF3)
)

private val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/** Access to the app's extra colour roles, e.g. `ClubMateTheme.chat.outgoingBubble`. */
object ClubMateTheme {
    val chat: ChatColors
        @Composable get() = LocalChatColors.current
}

@Composable
fun ClubMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalChatColors provides if (darkTheme) DarkChatColors else LightChatColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
