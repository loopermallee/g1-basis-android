package io.texne.g1.hub.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

object HubPalette {
    val Background: Color = Color(0xFF041610)
    val Surface: Color = Color(0xFF0B261B)
    val CardAccent: Color = Color(0xFF1F5435)
    val Accent: Color = Color(0xFF35E384)
    val OnBackground: Color = Color(0xFFE3F5EA)
    val OnSurface: Color = Color(0xFFD6FFE8)
    val TopBar: Color = Color(0xFF08301F)
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onAssistantClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onTodoClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onEReaderClick: () -> Unit,
    onNotificationsClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HubPalette.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        HubHeader()

        val tiles = listOf(
            HubTile(
                title = "Assistant",
                description = "Chat with GPT",
                icon = Icons.Outlined.Chat,
                onClick = onAssistantClick
            ),
            HubTile(
                title = "Settings",
                description = "Configure your glasses",
                icon = Icons.Outlined.Settings,
                onClick = onSettingsClick
            ),
            HubTile(
                title = "Subtitles",
                description = "Live captioning",
                icon = Icons.Outlined.Subtitles,
                onClick = onSubtitlesClick
            ),
            HubTile(
                title = "Todo",
                description = "Stay on track",
                icon = Icons.Outlined.TaskAlt,
                onClick = onTodoClick
            ),
            HubTile(
                title = "Navigation",
                description = "Find your way",
                icon = Icons.Outlined.Explore,
                onClick = onNavigationClick
            ),
            HubTile(
                title = "E-Reader",
                description = "Read with ease",
                icon = Icons.Outlined.MenuBook,
                onClick = onEReaderClick
            ),
            HubTile(
                title = "Notifications",
                description = "Never miss an alert",
                icon = Icons.Outlined.Notifications,
                onClick = onNotificationsClick
            ),
        )

        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(tiles, key = { it.title }) { tile ->
                HubTileCard(tile = tile)
            }
        }
    }
}

@Composable
private fun HubHeader() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(io.texne.g1.basis.service.R.mipmap.ic_service_foreground),
                contentDescription = "G1 Hub logo",
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "G1 Hub",
                    color = HubPalette.OnBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "A hub for Basis applications",
                    color = HubPalette.OnBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Choose an experience to launch",
            color = HubPalette.OnBackground.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HubTileCard(tile: HubTile) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = tile.onClick),
        colors = CardDefaults.cardColors(
            containerColor = HubPalette.Surface,
            contentColor = HubPalette.OnSurface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            width = 2.dp,
            color = HubPalette.CardAccent
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = HubPalette.Accent,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = tile.title,
                    color = HubPalette.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tile.description,
                    color = HubPalette.OnSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class HubTile(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        onAssistantClick = {},
        onSettingsClick = {},
        onSubtitlesClick = {},
        onTodoClick = {},
        onNavigationClick = {},
        onEReaderClick = {},
        onNotificationsClick = {}
    )
}
