package com.example.clubmate.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.ui.theme.Composables.Companion.TextDesignClickable
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.util.group.GroupActivityDesign
import com.example.clubmate.viewmodel.GroupViewmodel
import com.example.clubmate.viewmodel.UserJoinDetails

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "MutableCollectionMutableState")
@Composable
fun GroupScreen(
    userId: String, grpId: String, navController: NavHostController, grpViewmodel: GroupViewmodel
) {

    var grpDetails by remember { mutableStateOf(Routes.GrpDetails()) }

    var text by remember { mutableStateOf("") }
    val context = LocalContext.current

    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    LaunchedEffect(Unit) {
        grpViewmodel.loadGroupInfo(grpId) { details ->
            details?.let { grpDetails = details }
        }
    }

    DisposableEffect(Unit) { onDispose { grpViewmodel.clearMessage() } }

    LaunchedEffect(Unit) { grpViewmodel.loadActivities(grpId) }

    val grpActivity = grpViewmodel.grpActivity.collectAsState()

    // messages are never sent unencrypted: tell the user when encryption wasn't possible
    val sendError by grpViewmodel.sendError.collectAsState()
    LaunchedEffect(sendError) {
        sendError?.let {
            launchToast(context, it)
            grpViewmodel.clearSendError()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(grpActivity.value) { listState.interactionSource }

    LaunchedEffect(grpActivity.value) {
        if (grpActivity.value.isNotEmpty()) {
            listState.animateScrollToItem(grpActivity.value.lastIndex)
        }
    }


    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val openGalleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            uri?.let {
                selectedImageUri = it
            }
        }

    BackHandler {
        navController.navigate(Routes.Main)
    }

    Scaffold(modifier = Modifier
        .systemBarsPadding()
        .windowInsetsPadding(WindowInsets.ime)
        .padding(horizontal = 7.dp, vertical = 5.dp),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF3E5E5))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(8f)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = grpDetails.photoUrl,
                        contentDescription = "group photo",
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.logo_primary),
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(30.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = grpDetails.grpName,
                            fontSize = 18.sp,
                            fontFamily = roboto,
                        )
                        TextDesignClickable(text = "View Profile", size = 14) {
                            navController.navigate(
                                Routes.GrpDetails(
                                    grpId = grpDetails.grpId,
                                    grpName = grpDetails.grpName,
                                    description = grpDetails.description,
                                    createdBy = grpDetails.createdBy,
                                    createdAt = grpDetails.createdAt
                                )
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(end = 20.dp)
                ) {
                    Image(painter = painterResource(id = R.drawable.noticeboard),
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable {
                                navController.navigate(
                                    Routes.Timeline(
                                        grpId = grpId, uid = userId
                                    )
                                )
                            }
                    )
                    Image(painter = painterResource(id = R.drawable.private_channel),
                        contentDescription = null,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {
                                navController.navigate(Routes.PrivateAuth)
                            }
                    )
                }
            }

        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start
            ) {
                selectedImageUri?.let { uri ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 0.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF3A4233)),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.weight(8f),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(uri),
                                contentDescription = "Selected Image",
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .width(250.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.edit_button),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(top = 5.dp, end = 5.dp, bottom = 5.dp)
                                    .size(23.dp),
                                colorFilter = ColorFilter.tint(Color(0xFFF0F0D3))
                            )
                            Image(
                                painter = painterResource(id = R.drawable.delete_msg),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(top = 5.dp, end = 5.dp)
                                    .size(40.dp)
                                    .clickable {
                                        selectedImageUri = null
                                    },
                                colorFilter = ColorFilter.tint(Color(0xFFF0F0D3))
                            )
                        }
                    }

                }

                OutlinedTextField(value = text,
                    onValueChange = { text = it },
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 3,
                    leadingIcon = {
                        Image(painter = painterResource(id = R.drawable.attachment_24px),
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clickable {
                                    openGalleryLauncher.launch("image/*")
                                }
                        )
                    },
                    trailingIcon = {
                        Image(painter = painterResource(id = R.drawable.send_24px),
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clickable {
                                    val sentMessage = text.trim()
                                    selectedImageUri?.let { imageUri ->
                                        grpViewmodel.addActivity(
                                            grpId = grpId,
                                            senderId = userId,
                                            messageText = "",
                                            imageUri = imageUri
                                        )
                                    } ?: run {
                                        if (sentMessage.isNotEmpty()) {
                                            grpViewmodel.addActivity(
                                                grpId = grpId,
                                                senderId = userId,
                                                messageText = sentMessage,
                                                imageUri = null
                                            )
                                            text = ""
                                        } else {
                                            launchToast(context, "Message cannot be empty")
                                        }
                                    }
                                    selectedImageUri = null
                                }
                        )
                    },
                    textStyle = TextStyle(fontFamily = roboto, fontSize = 18.sp),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Enter message",
                            fontFamily = roboto,
                            fontSize = 18.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    })
            }
        }) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 60.dp, top = 70.dp)
        ) {

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                items(grpActivity.value.sortedBy {
                    it.message.timestamp
                }) { item ->
                    if (item.message.messageText.isNotEmpty() || item.message.imageRef.isNotEmpty()) {
                        var sender by remember { mutableStateOf(UserJoinDetails()) }
                        grpViewmodel.getParticipantsDetails(grpId, item.message.senderId) {
                            it?.let {
                                sender = it
                            }
                        }
                        GroupActivityDesign(
                            activity = item,
                            isSent = item.message.senderId == userId,
                            sender = sender,
                            sentTime = grpViewmodel.convertTimestampToTime(item.message.timestamp),
                            sentDate = grpViewmodel.convertTimestampToDate(item.message.timestamp),
                            onCopied = {
                                it?.let { clipboardManager.setPrimaryClip(it) }
                            }
                        ) { act ->
                            if (item.message.senderId == userId)
                                grpViewmodel.removeActivity(activity = act, grpId = grpId)
                            else launchToast(context, "only admin and sender can delete message")
                        }
                    }
                }
            }
        }
    }
}