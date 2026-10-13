package com.muxuechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.muxuechat.presentation.MuxueChatApp
import com.muxuechat.presentation.chat.ChatViewModel
import com.muxuechat.presentation.settings.GoogleAuthViewModel
import com.muxuechat.presentation.settings.XaiAuthViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels { ChatViewModel.Factory }
    private val xaiAuthViewModel: XaiAuthViewModel by viewModels { XaiAuthViewModel.Factory }
    private val googleAuthViewModel: GoogleAuthViewModel by viewModels { GoogleAuthViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MuxueChatApp(viewModel, xaiAuthViewModel, googleAuthViewModel)
        }
    }
}
