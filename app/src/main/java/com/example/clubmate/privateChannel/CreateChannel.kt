package com.example.clubmate.privateChannel


import android.annotation.SuppressLint
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.screens.launchToast
import com.example.clubmate.ui.theme.Composables.Companion.TextDesign
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.e2ee.ChannelE2ee
import com.example.clubmate.viewmodel.PrivateChannelViewModel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun CreatePrivateChannelScreen(
    viewmodel: PrivateChannelViewModel, navController: NavHostController
) {


    var password by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf("") }

    val context = LocalContext.current

    Scaffold(modifier = Modifier
        .background(Color(0xFF2A3150))
        .systemBarsPadding()
        .fillMaxSize()
        .padding(top = 20.dp), topBar = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 15.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Image(painter = painterResource(id = R.drawable.failed),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        // expand the animated visibility for the instruction
                    })
        }
    }) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2A3150))
                .padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.vanishing_mode_logo),
                    contentDescription = null,
                    modifier = Modifier.size(150.dp)
                )
                TextDesign(
                    text = "Private channel for secure messaging",
                    size = 14,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            Spacer(modifier = Modifier.height(60.dp))
            TextDesign(text = "Create channel", size = 20, color = Color.White)
            Spacer(modifier = Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(Color(0xD5C0D0F7)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable {
                    viewmodel.requestId { id ->
                        channelId = id
                    }
                }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = channelId.ifEmpty {
                            "Request channel id"
                        },
                        fontSize = 16.sp,
                        fontFamily = roboto,
                        color = Color.Black,
                        modifier = Modifier.padding(start = 15.dp)
                    )

                    Image(painter = painterResource(id = R.drawable.generate),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 20.dp)
                            .size(25.dp)
                            .rotate(270f)
                            .clickable {
                                viewmodel.requestId { id ->
                                    channelId = id
                                }
                            })
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xD5C0D0F7)),
                contentAlignment = Alignment.CenterStart
            ) {
                if (userId.isEmpty()) {
                    Text(
                        text = "Set a unique id",
                        fontFamily = roboto,
                        fontSize = 14.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(start = 15.dp)
                    )
                }
                BasicTextField(
                    value = userId, onValueChange = {
                        userId = it
                    }, modifier = Modifier
                        .padding(15.dp)
                        .fillMaxWidth(), textStyle = TextStyle(
                        fontFamily = roboto, fontSize = 16.sp, color = Color.Black
                    ), maxLines = 1, keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xD5C0D0F7)),
                contentAlignment = Alignment.CenterStart
            ) {
                if (password.isEmpty()) {
                    Text(
                        text = "Set a strong password",
                        fontFamily = roboto,
                        fontSize = 14.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(start = 15.dp)
                    )
                }
                BasicTextField(
                    value = password, onValueChange = {
                        password = it
                    }, modifier = Modifier
                        .padding(15.dp)
                        .fillMaxWidth(), textStyle = TextStyle(
                        fontFamily = roboto, fontSize = 16.sp, color = Color.Black
                    ), maxLines = 1, keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    )
                )
            }


            Spacer(modifier = Modifier.height(25.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A3150)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(colors = ButtonDefaults.buttonColors(Color(0xD5C0D0F7)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(130.dp),
                    onClick = {
                        if (channelId.isEmpty() || userId.isEmpty() || password.isEmpty()) {
                            Toast.makeText(
                                context, "All fields must be filled", Toast.LENGTH_SHORT
                            ).show()
                        } else if (password.length < ChannelE2ee.MIN_PASSWORD_LENGTH) {
                            // the password is the channel's encryption key, so it must not be guessable
                            Toast.makeText(
                                context,
                                "Password must be at least ${ChannelE2ee.MIN_PASSWORD_LENGTH} characters",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            viewmodel.createChatroom(
                                chatId = channelId, passWord = password, uid = userId
                            ) { details ->
                                if (details != null) {
                                    navController.navigate(
                                        Routes.PrivateChat(
                                            channelId = channelId, uid = userId, password = password
                                        )
                                    )
                                } else {
                                    launchToast(context, "Channel creation failed try again later")
                                }

                            }
                        }
                    }) {
                    TextDesign(text = "Create")
                }
            }
        }
    }
}

