package com.example.clubmate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clubmate.ui.ClubMateApp
import com.example.clubmate.ui.theme.ClubMateTheme
import com.example.clubmate.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // follows the system light/dark setting
            ClubMateTheme {
                val authViewModel: AuthViewModel = viewModel()
                ClubMateApp(authViewModel)
            }
        }
    }
}
