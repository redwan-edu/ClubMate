package com.example.clubmate.util.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.clubmate.R
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.viewmodel.GroupActivity


@Composable
fun GroupDesign(
    grpName: String, photo: String, lastActivity: GroupActivity, onClick: () -> Unit
) {

    // read the parameters directly so the preview updates when a new (decrypted) message arrives
    val name = grpName
    val lastAct = lastActivity

    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE1EACD))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {

            AsyncImage(
                model = photo,
                contentDescription = "group photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(30.dp)),
                error = painterResource(id = R.drawable.logo_primary)
            )

            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = name,
                    fontFamily = roboto,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = if (lastAct.message.imageRef.isNotEmpty()) "image..." else lastAct.message.messageText,
                    color = Color.Gray,
                    fontFamily = roboto,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}