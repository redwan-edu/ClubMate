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
fun RegisterScreen(authViewmodel: AuthViewModel, navController: NavHostController) {

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val authState = authViewmodel.authState.observeAsState()
    LaunchedEffect(authState.value) {
        when (authState.value) {
            is Status.Error -> {
                Toast.makeText(
                    context,
                    (authState.value as Status.Error).message,
                    Toast.LENGTH_SHORT
                ).show()
                isLoading = false
            }

            else -> Unit
        }
    }

    val register = {
        isLoading = true
        authViewmodel.register(
            email = email,
            password = password,
            userName = userName,
            phone = phone,
            context = context
        ) { success ->
            isLoading = false
            if (success) navController.navigate(Routes.Login)
        }
    }

    AuthScaffold(
        palette = RegisterPalette,
        logo = painterResource(id = R.drawable.logo_primary),
        appName = painterResource(id = R.drawable.app_name),
        subtitle = "Create your account",
        footerPrompt = "Already have an account?",
        footerAction = "Login",
        onFooterClick = { navController.navigate(Routes.Login) },
        fontFamily = roboto,
        modifier = Modifier.systemBarsPadding()
    ) {
        AuthTextField(
            value = userName,
            onValueChange = { userName = it },
            placeholder = "Username",
            leadingIcon = AuthIcons.Username,
            fontFamily = roboto
        )
        AuthTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "Email",
            leadingIcon = AuthIcons.Email,
            keyboardType = KeyboardType.Email,
            fontFamily = roboto
        )
        AuthTextField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = "Contact No",
            leadingIcon = AuthIcons.Phone,
            keyboardType = KeyboardType.Phone,
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
            onImeAction = register,
            fontFamily = roboto
        )
        AuthPrimaryButton(
            text = "Register",
            palette = RegisterPalette,
            loading = isLoading,
            onClick = register,
            fontFamily = roboto
        )
    }
}
