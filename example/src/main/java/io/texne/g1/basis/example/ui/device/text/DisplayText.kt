package io.texne.g1.basis.example.ui.device.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.service.protocol.G1Glasses

@Composable
fun DisplayText(glasses: G1Glasses) {
    val viewModel = hiltViewModel<DisplayTextViewModel>()
    val state = viewModel.state.collectAsState().value

    LaunchedEffect(Unit) {
        viewModel.setGlassesId(glasses.id)
    }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { viewModel.showText() }
        ) {
            Text("Show Message")
        }
    }
}
