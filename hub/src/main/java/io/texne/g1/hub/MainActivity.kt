package io.texne.g1.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.ui.ApplicationFrame
import io.texne.g1.hub.ui.theme.G1HubTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity: ComponentActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService()

        enableEdgeToEdge()
        setContent {
            G1HubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                        ApplicationFrame()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.unbindService()
    }
}