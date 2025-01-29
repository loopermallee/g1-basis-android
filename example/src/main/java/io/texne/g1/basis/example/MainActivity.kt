package io.texne.g1.basis.example

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
import io.texne.g1.basis.example.model.Repository
import io.texne.g1.basis.example.ui.ApplicationFrame
import io.texne.g1.basis.example.ui.theme.BasisTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService()

        enableEdgeToEdge()
        setContent {
            BasisTheme {
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
