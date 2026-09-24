package com.example.clubmate.auth

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavHostController
import com.example.clubmate.R
import com.example.clubmate.db.Routes
import com.example.clubmate.db.Status
import com.example.clubmate.ui.theme.roboto
import com.example.clubmate.viewmodel.AuthViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LoginScreen(authViewmodel: AuthViewModel, navController: NavHostController) {

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val context = LocalContext.current

    val authState = authViewmodel.authState.observeAsState()
    LaunchedEffect(authState.value) {
        when (authState.value) {
            is Status.Error -> Toast.makeText(
                context, (authState.value as Status.Error).message, Toast.LENGTH_SHORT
            ).show()

            is Status.Authenticated -> navController.navigate(Routes.Main)

            else -> Unit
        }
    }

    AuthScaffold(
        palette = LoginPalette,
        logo = painterResource(id = R.drawable.logo_primary),
        appName = painterResource(id = R.drawable.app_name),
        subtitle = "Welcome back",
        footerPrompt = "Don't have an account?",
        footerAction = "Register",
        onFooterClick = { navController.navigate(Routes.Register) },
        fontFamily = roboto,
        modifier = Modifier.systemBarsPadding()
    ) {
        AuthTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "Email",
            leadingIcon = AuthIcons.Email,
            keyboardType = KeyboardType.Email,
            fontFamily = roboto
        )
        AuthTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = "Password",
            leadingIcon = AuthIcons.Password,
            isPassword = true,
            visibleIcon = painterResource(id = R.drawable.visibility_24px),
            hiddenIcon = painterResource(id = R.drawable.visibility_off_24px),
            imeAction = ImeAction.Done,
            onImeAction = { authViewmodel.logIn(email, password) },
            fontFamily = roboto
        )
        AuthPrimaryButton(
            text = "Login",
            palette = LoginPalette,
            loading = authState.value == Status.Loading,
            onClick = { authViewmodel.logIn(email, password) },
            fontFamily = roboto
        )
    }
}
