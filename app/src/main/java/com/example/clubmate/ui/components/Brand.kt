package com.example.clubmate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The ClubMate mark: a chat glyph on a rounded, softly graded brand square. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(primary, Color(0xFF6C4DF0)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Forum, contentDescription = "ClubMate", tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}
