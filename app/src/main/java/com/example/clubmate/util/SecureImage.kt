package com.example.clubmate.util

import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.example.clubmate.e2ee.SecureImages

private sealed class ImageLoad {
    object Loading : ImageLoad()
    class Ready(val bitmap: ImageBitmap) : ImageLoad()
    object Failed : ImageLoad()
}

/**
 * Drop-in replacement for AsyncImage for message pictures: end-to-end encrypted image references
 * are downloaded and decrypted on the device; plain URLs (older messages) load as before.
 */
@Composable
fun SecureAsyncImage(
    model: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    error: Painter? = null
) {
    if (!SecureImages.isEncrypted(model)) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            error = error
        )
    } else {
        val state by produceState<ImageLoad>(ImageLoad.Loading, model) {
            value = SecureImages.load(model)?.let { ImageLoad.Ready(it.asImageBitmap()) } ?: ImageLoad.Failed
        }
        when (val current = state) {
            is ImageLoad.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier
            )

            ImageLoad.Failed -> if (error != null) {
                Image(painter = error, contentDescription = contentDescription, modifier = modifier)
            } else {
                Text(text = "🔒 Image unavailable", modifier = modifier)
            }

            ImageLoad.Loading -> Unit // the message bubble already shows a progress indicator
        }
    }
}
