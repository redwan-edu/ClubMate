package com.example.clubmate.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.clubmate.R

/** The ClubMate mark: the product logo, used consistently across splash, auth and settings. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Image(
        painter = painterResource(R.drawable.ic_brand_logo),
        contentDescription = "ClubMate",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}
