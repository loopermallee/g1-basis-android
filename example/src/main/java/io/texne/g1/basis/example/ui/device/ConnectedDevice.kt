package io.texne.g1.basis.example.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.example.ui.device.text.DisplayText
import io.texne.g1.basis.service.protocol.G1Glasses

@Composable
fun ConnectedDevice(
    glasses: G1Glasses
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            indicator = {}
        ) {
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxSize()
                    .clickable(onClick = { selectedTab = 0 })
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                val selected = selectedTab == 0
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    text = "Text",
                    fontSize = if(selected) 24.sp else 24.sp,
                    color = if(selected) Color.White else Color.Black,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            DisplayText(glasses)
        }
    }
}
