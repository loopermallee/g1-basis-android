package io.texne.g1.hub.settings

enum class HudWidgetType(val id: String, val displayName: String, val emoji: String) {
    CLOCK(id = "clock", displayName = "Clock", emoji = "\uD83D\uDD70\uFE0F"),
    WEATHER(id = "weather", displayName = "Weather", emoji = "\u2600\uFE0F"),
    NEWS(id = "news", displayName = "News", emoji = "\uD83D\uDCF0"),
    NOTIFICATIONS(id = "notifications", displayName = "Notifications", emoji = "\uD83D\uDD14");

    companion object {
        fun fromId(id: String): HudWidgetType? = entries.firstOrNull { it.id == id }
    }
}

data class HudWidget(
    val type: HudWidgetType,
    val enabled: Boolean,
)

object HudWidgetDefaults {
    val widgets: List<HudWidget> = listOf(
        HudWidget(HudWidgetType.CLOCK, enabled = true),
        HudWidget(HudWidgetType.WEATHER, enabled = true),
        HudWidget(HudWidgetType.NEWS, enabled = true),
        HudWidget(HudWidgetType.NOTIFICATIONS, enabled = true),
    )
}
