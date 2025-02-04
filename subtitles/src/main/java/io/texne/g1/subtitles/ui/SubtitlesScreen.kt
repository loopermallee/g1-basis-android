package io.texne.g1.subtitles.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SubtitlesScreen() {

    val viewModel = hiltViewModel<SubtitlesViewModel>()
    val state = viewModel.state.collectAsState().value

    if(state.glasses == null) {
        Text("NO CONNECTION")
    } else {
        Text("CONNECTION")
    }
}