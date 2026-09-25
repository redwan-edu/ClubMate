package com.example.clubmate.util.group

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.clubmate.util.SecureAsyncImage
import com.example.clubmate.R
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.viewmodel.GroupActivity
import com.example.clubmate.viewmodel.UserJoinDetails

@Composable
fun GroupActivityDesign(
    activity: GroupActivity,
    sender: UserJoinDetails,
    isSent: Boolean,
    sentTime: String,
    sentDate: String,
    onCopied: (ClipData?) -> Unit,
    onDeleteMessage: (GroupActivity) -> Unit
) {

    var isSelected by remember { mutableStateOf(false) }

    val bgColor by remember {
        mutableStateOf(
            if (isSent) Color(0xFFD5F1C4)
            else Color(0xBFF1D4D4)
        )
    }

    Box(
        modifier = Modifier.fillMaxWidth(), contentAlignment = if (isSent) Alignment.CenterEnd
        else Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
        ) {
            Column(
                horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
            ) {
                Row {
                    if (isSent) {
                        Text(
                            text = sender.username, fontSize = 12.sp, fontFamily = roboto,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(end = 5.dp),
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = sender.userType.toString(),
                            fontSize = 14.sp,
                            fontFamily = roboto,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(end = 5.dp),
                            color = Color.Red,
                            textDecoration = TextDecoration.Underline
                        )
                    } else {
                        Text(
                            text = sender.userType.toString(),
                            fontSize = 14.sp,
                            fontFamily = roboto,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(end = 0.dp),
                            color = Color.Red,
                            textDecoration = TextDecoration.Underline
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = sender.username, fontSize = 12.sp, fontFamily = roboto,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(end = 0.dp),
                            color = Color.Black
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .padding(4.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    val clipData = ClipData.newPlainText(
                                        "Copied Text",
                                        activity.message.messageText
                                    )
                                    clipData?.let {
                                        onCopied(clipData)
                                    } ?: run {
                                        onCopied(null)
                                    }
                                },
                                onLongPress = { if (isSent) isSelected = true },
                                onTap = { if (isSelected) isSelected = false })
                        },
                ) {
                    if (activity.message.imageRef.isNotEmpty()) {
                        // Display Image with preloading and caching
                        Box(
                            modifier = Modifier.width(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(40.dp)
                                    .align(Alignment.Center),
                                color = Color.Gray,
                                strokeWidth = 4.dp
                            )
                            SecureAsyncImage(
                                model = activity.message.imageRef,
                                contentDescription = "Sent Image",
                                contentScale = ContentScale.Inside,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    } else {
                        // Display Text
                        Text(
                            text = activity.message.messageText,
                            fontSize = 16.sp,
                            fontFamily = roboto,
                            color = Color.Black,
                            modifier = Modifier
                                .padding(horizontal = 15.dp)
                                .widthIn(max = 280.dp)

                        )
                    }
                }
                Row(
                    horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
                    modifier = Modifier.padding(end = if (isSent) 5.dp else 0.dp)
                ) {
                    Text(
                        text = sentTime, fontFamily = roboto, fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = sentDate, fontFamily = roboto, fontSize = 10.sp
                    )
                }
            }
            AnimatedVisibility(isSelected) {
                Icon(painter = painterResource(id = R.drawable.delete_msg),
                    contentDescription = "Delete Item",
                    modifier = Modifier
                        .padding(bottom = 20.dp, start = 5.dp)
                        .size(30.dp)
                        .clickable { onDeleteMessage(activity) }
                )
            }
        }
    }
}
