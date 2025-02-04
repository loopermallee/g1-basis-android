package io.texne.g1.subtitles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.subtitles.model.Repository
import io.texne.g1.subtitles.ui.SubtitlesScreen
import io.texne.g1.subtitles.ui.theme.SubtitlesTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity: ComponentActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService(lifecycleScope)
        repository.initializeSpeechRecognizer(this)

        enableEdgeToEdge()
        setContent {
            SubtitlesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                        SubtitlesScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.destroySpeechRecognizer()
        repository.unbindService()
    }
}