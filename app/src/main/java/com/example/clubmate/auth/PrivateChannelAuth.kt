package com.example.clubmate.auth

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.screens.launchToast
import com.example.clubmate.ui.theme.Composables.Companion.TextDesign
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.viewmodel.PrivateChannelViewModel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun PrivateChannelAuth(
    navController: NavHostController,
    privateChannelViewModel: PrivateChannelViewModel
) {

    var isLoading by remember { mutableStateOf(false) }
    var channelId by remember {
        mutableStateOf("")
    }
    var userId by remember {
        mutableStateOf("")
    }
    var password by remember {
        mutableStateOf("")
    }

    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A3150))
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.padding(top = 15.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.incognito),
                            contentDescription = null,
                            Modifier.size(25.dp)
                        )
                        Image(
                            painter = painterResource(id = R.drawable.lock_bg),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(25.dp)
                        )
                        Image(
                            painter = painterResource(id = R.drawable.private_channel),
                            contentDescription = null,
                            Modifier.size(25.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextDesign(
                        color = Color.White,
                        text = "Join private channel",
                        size = 22
                    )
                    TextDesign(
                        color = Color.White,
                        text = "for secure encrypted anonymous messaging",
                        size = 14
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)

                ) {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo_primary),
                            contentDescription = null,
                            modifier = Modifier.size(100.dp)
                        )
                        Image(
                            painter = painterResource(id = R.drawable.app_name),
                            contentDescription = null,
                            modifier = Modifier.height(40.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    TextField(
                        value = channelId,
                        onValueChange = {
                            channelId = it
                        },
                        placeholder = {
                            Text(text = "Channel Id", fontFamily = roboto, color = Color.Black)
                        },
                        maxLines = 1,
                        modifier = Modifier.width(320.dp),
                        textStyle = TextStyle(fontFamily = roboto, color = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color.White,
                            unfocusedPlaceholderColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color(0xD5CEDAF5),
                            unfocusedContainerColor = Color(0xD5CEDAF5)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Next
                        )
                    )
                    TextField(
                        value = userId,
                        onValueChange = {
                            userId = it
                        },
                        placeholder = {
                            Text(text = "User id", fontFamily = roboto, color = Color.Black)
                        },
                        maxLines = 1,
                        modifier = Modifier.width(320.dp),
                        textStyle = TextStyle(fontFamily = roboto, color = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color.White,
                            unfocusedPlaceholderColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color(0xD5CEDAF5),
                            unfocusedContainerColor = Color(0xD5CEDAF5)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Next
                        )
                    )
                    TextField(
                        value = password,
                        onValueChange = {
                            password = it
                        },
                        placeholder = {
                            Text(text = "Password", fontFamily = roboto, color = Color.Black)
                        },
                        maxLines = 1,
                        modifier = Modifier.width(320.dp),
                        textStyle = TextStyle(fontFamily = roboto, color = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color.White,
                            unfocusedPlaceholderColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color(0xD5CEDAF5),
                            unfocusedContainerColor = Color(0xD5CEDAF5)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (password.isNotEmpty() && channelId.isNotEmpty() && userId.isNotEmpty()) {
                                    isLoading = true
                                    privateChannelViewModel.joinChatroom(
                                        chatId = channelId, passWord = password, uid = userId
                                    ) { isAllowed ->
                                        if (isAllowed) {
                                            isLoading = false
                                            navController.navigate(
                                                Routes.PrivateChat(
                                                    channelId = channelId,
                                                    password = password,
                                                    uid = userId
                                                )
                                            )
                                        } else {
                                            isLoading = false
                                            launchToast(
                                                context = context,
                                                "Wrong channel ID or password"
                                            )
                                        }
                                    }
                                } else {
                                    launchToast(
                                        context = context,
                                        "all field must be filled"
                                    )
                                }
                            }
                        )
                    )
                    Row(
                        modifier = Modifier
                            .width(310.dp)
                            .height(10.dp)
                            .padding(top = 5.dp)
                    ) {
                        if (isLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Button(
                        shape = RoundedCornerShape(8.dp),
                        onClick = {
                            if (password.isNotEmpty() && channelId.isNotEmpty() && userId.isNotEmpty()) {
                                isLoading = true
                                privateChannelViewModel.joinChatroom(
                                    chatId = channelId, passWord = password, uid = userId
                                ) { isAllowed ->
                                    if (isAllowed) {
                                        navController.navigate(
                                            Routes.PrivateChat(
                                                channelId = channelId,
                                                uid = userId, password = password,

                                                )
                                        )
                                        isLoading = false
                                    } else {
                                        isLoading = false
                                        launchToast(
                                            context = context,
                                            "Wrong channel ID or password"
                                        )
                                    }
                                }
                            } else {
                                launchToast(
                                    context = context,
                                    "password and id cannot be empty"
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(Color(0xFF767D9B))
                    ) {
                        TextDesign(text = "Join", color = Color.White)

                    }


                }
            }
            Spacer(
                modifier = Modifier
                    .width(600.dp)
                    .height(450.dp)
                    .offset(y = 320.dp)
                    .scale(2f, 1f)
                    .clip(RoundedCornerShape(200.dp))
                    .background(Color(0xFF020B33))
                    .align(Alignment.BottomCenter)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Create a private channel?",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontFamily = roboto
                )
                Text(text = "Create",
                    fontFamily = roboto,
                    fontSize = 22.sp,
                    color = Color.White,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        navController.navigate(Routes.CreateChannel)
                    }
                )
                Spacer(modifier = Modifier.height(25.dp))
            }


        }

    }

}

