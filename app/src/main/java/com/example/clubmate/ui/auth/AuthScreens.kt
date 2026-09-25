package com.example.clubmate.ui.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.clubmate.db.Status
import com.example.clubmate.ui.components.AppTextField
import com.example.clubmate.ui.components.BrandMark
import com.example.clubmate.ui.components.InlineError
import com.example.clubmate.ui.components.LinkButton
import com.example.clubmate.ui.components.PrimaryButton
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.AuthViewModel

// ---------------------------------------------------------------- splash

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 80.dp)
            Spacer(Modifier.height(20.dp))
            Text("ClubMate", style = MaterialTheme.typography.headlineMedium)
        }
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).size(24.dp)
        )
    }
}

// ---------------------------------------------------------------- shared layout

/** Scrollable form page used by sign-in and sign-up: brand mark, title, subtitle, content, footer. */
@Composable
private fun AuthLayout(
    title: String,
    subtitle: String,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(56.dp))
        BrandMark(size = 56.dp)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        content()
        Spacer(Modifier.weight(1f).height(24.dp))
        footer()
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Your messages are end-to-end encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FooterLink(prompt: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkButton(action, onClick)
    }
}

// ---------------------------------------------------------------- sign in

@Composable
fun LoginScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onLogin: () -> Unit,
    onCreateAccount: () -> Unit
) {
    AuthLayout(
        title = "Welcome back",
        subtitle = "Sign in to continue to your clubs and chats.",
        footer = { FooterLink("New to ClubMate?", "Create account", onCreateAccount) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTextField(
                value = email,
                onValueChange = onEmailChange,
                label = "Email",
                leadingIcon = Icons.Rounded.AlternateEmail,
                keyboardType = KeyboardType.Email
            )
            AppTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                leadingIcon = Icons.Rounded.Lock,
                isPassword = true,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = onLogin
            )
            if (error != null) InlineError(error)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "Log in",
                onClick = onLogin,
                loading = loading,
                enabled = email.isNotBlank() && password.isNotEmpty()
            )
        }
    }
}

@Composable
fun LoginRoute(authViewModel: AuthViewModel, onLoggedIn: () -> Unit, onCreateAccount: () -> Unit) {
    val authState by authViewModel.authState.observeAsState()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState == Status.Authenticated) onLoggedIn()
    }
    LoginScreen(
        email = email,
        onEmailChange = { email = it },
        password = password,
        onPasswordChange = { password = it },
        loading = authState == Status.Loading,
        error = (authState as? Status.Error)?.message,
        onLogin = { authViewModel.logIn(email.trim(), password) },
        onCreateAccount = onCreateAccount
    )
}

// ---------------------------------------------------------------- sign up

const val PASSWORD_RULES = "At least 8 characters, with upper and lower case letters, a number and a symbol (@\$!%*?&)."

@Composable
fun RegisterScreen(
    name: String,
    onNameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onRegister: () -> Unit,
    onLogin: () -> Unit
) {
    AuthLayout(
        title = "Create your account",
        subtitle = "Join your clubs, chat with members and stay in the loop.",
        footer = { FooterLink("Already have an account?", "Log in", onLogin) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTextField(name, onNameChange, "Full name", leadingIcon = Icons.Rounded.Person)
            AppTextField(
                email, onEmailChange, "Email",
                leadingIcon = Icons.Rounded.AlternateEmail, keyboardType = KeyboardType.Email
            )
            AppTextField(
                phone, onPhoneChange, "Phone number",
                leadingIcon = Icons.Rounded.Phone, keyboardType = KeyboardType.Phone
            )
            AppTextField(
                password, onPasswordChange, "Password",
                leadingIcon = Icons.Rounded.Lock,
                isPassword = true,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = onRegister,
                supportingText = PASSWORD_RULES
            )
            if (error != null) InlineError(error)
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                text = "Create account",
                onClick = onRegister,
                loading = loading,
                enabled = name.isNotBlank() && email.isNotBlank() && phone.isNotBlank() && password.isNotEmpty()
            )
            Text(
                "We'll send you an email to verify your address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun RegisterRoute(authViewModel: AuthViewModel, onRegistered: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val authState by authViewModel.authState.observeAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var submitting by rememberSaveable { mutableStateOf(false) }

    RegisterScreen(
        name = name, onNameChange = { name = it },
        email = email, onEmailChange = { email = it },
        phone = phone, onPhoneChange = { phone = it },
        password = password, onPasswordChange = { password = it },
        loading = submitting,
        error = if (submitting) null else (authState as? Status.Error)?.message,
        onRegister = {
            submitting = true
            authViewModel.register(
                email = email.trim(),
                password = password,
                userName = name.trim(),
                phone = phone.trim()
            ) { success ->
                submitting = false
                if (success) {
                    Toast.makeText(
                        context, "Account created. Verify your email, then log in.", Toast.LENGTH_LONG
                    ).show()
                    onRegistered()
                }
            }
        },
        onLogin = onLogin
    )
}

// ---------------------------------------------------------------- previews

@Preview
@Composable
fun LoginScreenPreview() = ClubMateTheme {
    LoginScreen("redwan@university.edu", {}, "password1", {}, loading = false, error = null, onLogin = {}, onCreateAccount = {})
}

@Preview
@Composable
fun RegisterScreenPreview() = ClubMateTheme {
    RegisterScreen(
        "Redwan Hussain", {}, "redwan@university.edu", {}, "+880 1700 000000", {}, "", {},
        loading = false, error = "Password does not meet requirements", onRegister = {}, onLogin = {}
    )
}

@Preview
@Composable
fun SplashScreenPreview() = ClubMateTheme { SplashScreen() }
