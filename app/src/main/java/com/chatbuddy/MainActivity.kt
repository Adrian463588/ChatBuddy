package com.chatbuddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.chatbuddy.presentation.ChatBuddyApp
import com.chatbuddy.presentation.home.HomeViewModel
import com.chatbuddy.ui.theme.ChatBuddyTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatBuddyTheme {
                ChatBuddyApp(
                    windowSizeClass = calculateWindowSizeClass(this),
                    viewModel = hiltViewModel<HomeViewModel>()
                )
            }
        }
    }
}
