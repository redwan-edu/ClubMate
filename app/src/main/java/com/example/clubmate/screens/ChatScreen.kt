package com.example.clubmate.screens

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil3.compose.AsyncImage
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.ui.theme.Composables.Companion.TextDesign
import com.example.clubmate.ui.theme.Composables.Companion.TextDesignClickable
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.util.chat.IncognitoMessageItem
import com.example.clubmate.util.chat.MessageItem
import com.example.clubmate.viewmodel.AuthViewModel
import com.example.clubmate.viewmodel.ChatViewModel
import com.example.clubmate.viewmodel.MainViewmodel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "StateFlowValueCalledInComposition")
@Composable
fun ChatScreen(
    userModel: Routes.UserModel,
    chatViewmodel: ChatViewModel,
    authViewModel: AuthViewModel,
    navController: NavController,
    mainViewmodel: MainViewmodel
) {

    var incognito by remember { mutableStateOf(false) }

    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val receiverId = userModel.uid
    val currentUser by authViewModel.currentUser.collectAsState()

    var details: Routes.UserModel? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        chatViewmodel.fetchUserByUid(receiverId) {
            it?.let {
                details = it
            }
        }
    }

    LaunchedEffect(Unit) {
        currentUser?.uid?.let {
            chatViewmodel.receiveMessage(
                chatId = userModel.chatID
            )
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.interactionSource }
    val messages = chatViewmodel.messages.collectAsState()
    LaunchedEffect(messages.value, incognito) {
        if (messages.value.isNotEmpty()) {
            listState.animateScrollToItem(messages.value.size - 1) // Smooth scrolling
        }
    }


    val incognitoMessages = chatViewmodel.incognitoMessages.collectAsState()
    LaunchedEffect(incognito) {
        if (incognito) {
            chatViewmodel.receiveIncognitoMessage(
                chatId = userModel.chatID
            )
        } else {
            chatViewmodel.deleteIncognitoMessage(chatId = userModel.chatID)
        }
    }

    val incognitoListState = rememberLazyListState()
    LaunchedEffect(Unit) { incognitoListState.interactionSource }

    LaunchedEffect(incognitoMessages.value) {
        if (incognitoMessages.value.isNotEmpty()) {
            incognitoListState.animateScrollToItem(incognitoMessages.value.size - 1)
        }
    }

    DisposableEffect(Unit) {
        onDispose { chatViewmodel.clearMessage(chatId = userModel.chatID) }
    }

    // messages are never sent unencrypted: tell the user when encryption wasn't possible
    val sendError by chatViewmodel.sendError.collectAsState()
    LaunchedEffect(sendError) {
        sendError?.let {
            launchToast(context, it)
            chatViewmodel.clearSendError()
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
        chatViewmodel.emptyUser()
        navController.navigateUp()
    }

    Scaffold(modifier = Modifier
        .systemBarsPadding()
        .background(Color(0xFFFDF7F4))
        .windowInsetsPadding(WindowInsets.ime)
        .padding(vertical = 5.dp), topBar = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF3E5E5))
                    .padding(horizontal = 15.dp, vertical = 5.dp),
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
                        model = details?.photoUrl,
                        contentDescription = "group photo",
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.logo_primary),
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(30.dp))
                    )

                    Spacer(modifier = Modifier.width(15.dp))
                    Column {
                        userModel.run {
                            Text(
                                text = username,
                                fontSize = 18.sp,
                                fontFamily = roboto,
                            )

                            TextDesignClickable(text = "View Profile", size = 14) {
                                chatViewmodel.fetchUserByUid(receiverId) { user ->
                                    user?.let {
                                        navController.navigate(
                                            Routes.UserDetails(
                                                username = username,
                                                email = email,
                                                uid = uid,
                                                chatID = chatID,
                                                phone = it.phone
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Image(painter = painterResource(id = R.drawable.call_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {

                            })
                    Image(painter = painterResource(id = R.drawable.incognito),
                        contentDescription = null,
                        modifier = Modifier
                            .size(35.dp)
                            .clickable {
                                incognito = !incognito
                            })
                }
            }
        }

    }, bottomBar = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.Start
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
                            colorFilter = ColorFilter.tint(
                                Color(0xFFF0F0D3)
                            )
                        )
                    }
                }

            }

            OutlinedTextField(value = text,
                onValueChange = { text = it },
                shape = RoundedCornerShape(10.dp),
                maxLines = 3,
                leadingIcon = {
                    if (!incognito) {
                        Image(painter = painterResource(id = R.drawable.attachment_24px),
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clickable {
                                    if (incognito) launchToast(
                                        context, "cannot send image in incognito mode"
                                    )
                                    else openGalleryLauncher.launch("image/*")
                                })
                    }
                },
                trailingIcon = {
                    Image(painter = painterResource(id = R.drawable.send_24px),
                        contentDescription = null,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {

                                val uid = currentUser?.uid
                                if (uid != null) {
                                    selectedImageUri?.let { imageUri ->
                                        chatViewmodel.sendMessage(
                                            chatId = userModel.chatID,
                                            messageText = "",
                                            imageUri = imageUri,
                                            senderId = uid,
                                            receiverId = receiverId
                                        )
                                        selectedImageUri = null
                                    } ?: run {
                                        val sentMessage = text.trim()
                                        if (sentMessage.isNotEmpty()) {
                                            if (incognito) {
                                                chatViewmodel.sendIncognitoMessage(
                                                    chatId = userModel.chatID,
                                                    messageText = sentMessage,
                                                    senderId = uid,
                                                    receiverId = receiverId
                                                )
                                            } else {
                                                chatViewmodel.sendMessage(
                                                    chatId = userModel.chatID,
                                                    messageText = sentMessage,
                                                    senderId = uid,
                                                    receiverId = receiverId
                                                )
                                            }

                                            text = ""
                                        } else {
                                            launchToast(
                                                context, "Message cannot be empty"
                                            )
                                        }
                                    }
                                }
                            }
                    )
                },
                textStyle = TextStyle(
                    fontFamily = roboto, fontSize = 18.sp
                ),
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
                .background(Color(0xFFFDF7F4))
                .padding(bottom = 60.dp, top = 80.dp), verticalArrangement = Arrangement.Bottom
        ) {

            currentUser?.let { user ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages.value.size) { ind ->
                        Spacer(modifier = Modifier.height(5.dp))
                        val message = messages.value[ind]

                        if (message.messageText.isNotEmpty() || message.imageRef.isNotEmpty()) {
                            MessageItem(message = message,
                                isSent = messages.value[ind].senderId == user.uid,
                                time = chatViewmodel.convertTimestampToDate(messages.value[ind].timestamp),
                                onSelect = {
                                    clipboardManager.setText(AnnotatedString(message.messageText))
                                },
                                onDeleteMessage = { msg ->
                                    chatViewmodel.deleteIndividualMessage(
                                        chatId = userModel.chatID, messageId = msg.messageId
                                    )
                                })
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = incognito, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(
                    animationSpec = tween(500)
                ), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(
                    animationSpec = tween(500)
                )
            ) {
                val animatedHeight by animateDpAsState(
                    targetValue = if (incognito) 600.dp else 0.dp,
                    animationSpec = tween(durationMillis = 500),
                    label = "incognitoHeight"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp, horizontal = 1.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .height(animatedHeight)
                        .background(Color(0xFF0C1C3A)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(5.dp))
                    TextDesign(
                        text = "Incognito mode", size = 18, color = Color.White
                    )

                    currentUser?.let { user ->
                        LazyColumn(
                            state = incognitoListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .weight(1f),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            items(incognitoMessages.value.size) { ind ->
                                val message = incognitoMessages.value[ind]
                                IncognitoMessageItem(
                                    message = message,
                                    isSent = message.senderId == user.uid,
                                    time = chatViewmodel.convertTimestampToDate(message.timestamp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


fun launchToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}


enum class MessageStatus {
    SEEN, DELIVERED, FAILED, SENDING
}