package io.texne.g1.hub.hud

import io.texne.g1.hub.ai.HudFormatter
import io.texne.g1.hub.settings.HudSettingsRepository
import io.texne.g1.hub.settings.HudWidget
import io.texne.g1.hub.settings.HudWidgetType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

@Singleton
class HudStatusComposer @Inject constructor(
    private val hudSettingsRepository: HudSettingsRepository,
    private val weatherRepository: WeatherRepository,
    private val newsRepository: NewsRepository,
    private val notificationRepository: NotificationRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    data class Payload(
        val lines: List<String>,
        val truncated: Boolean
    ) {
        companion object {
            val Empty = Payload(lines = emptyList(), truncated = false)
        }
    }

    val status: StateFlow<Payload> = combine(
        hudSettingsRepository.widgets,
        clockFlow().onEach { notificationRepository.refresh() },
        weatherRepository.weather,
        newsRepository.news,
        notificationRepository.notifications
    ) { widgets, clock, weather, news, notifications ->
        compose(widgets, clock, weather, news, notifications)
    }.stateIn(scope, SharingStarted.Eagerly, Payload.Empty)

    private fun compose(
        widgets: List<HudWidget>,
        clock: String,
        weather: WeatherSnapshot,
        news: NewsDigest,
        notifications: NotificationState
    ): Payload {
        val segments = buildList {
            widgets.filter { it.enabled }.forEach { widget ->
                when (widget.type) {
                    HudWidgetType.CLOCK -> add(formatClock(clock))
                    HudWidgetType.WEATHER -> formatWeather(weather)?.let { add(it) }
                    HudWidgetType.NEWS -> formatNews(news)?.let { add(it) }
                    HudWidgetType.NOTIFICATIONS -> add(formatNotifications(notifications))
                }
            }
        }

        if (segments.isEmpty()) {
            return Payload.Empty
        }

        val combined = segments.joinToString(separator = "   ")
        val formatted = HudFormatter.format(combined)
        val firstPage = formatted.pages.firstOrNull().orEmpty()
        val truncated = formatted.truncated || formatted.pages.size > 1
        return Payload(lines = firstPage, truncated = truncated)
    }

    private fun formatClock(clock: String): String = "\uD83D\uDD70\uFE0F $clock"

    private fun formatWeather(weather: WeatherSnapshot): String? {
        val temperature = weather.temperatureFahrenheit ?: weather.temperatureCelsius
        val unit = if (weather.temperatureFahrenheit != null) "°F" else if (weather.temperatureCelsius != null) "°C" else null
        val condition = weather.condition?.takeIf { it.isNotBlank() }
        if (temperature == null && condition == null) {
            return null
        }
        val roundedTemp = temperature?.let { it.roundToInt().toString() }
        val tempLabel = if (roundedTemp != null && unit != null) "$roundedTemp$unit" else null
        val description = listOfNotNull(tempLabel, condition).joinToString(" ")
        return "\u2600\uFE0F ${if (description.isBlank()) "Weather" else description}"
    }

    private fun formatNews(news: NewsDigest): String? {
        val headline = news.primaryHeadline?.takeIf { it.isNotBlank() }
        return headline?.let { "\uD83D\uDCF0 $it" }
    }

    private fun formatNotifications(state: NotificationState): String {
        return if (!state.hasAccess) {
            "\uD83D\uDD14 Grant notification access"
        } else if (state.activeCount == 0) {
            "\uD83D\uDD14 No alerts"
        } else {
            "\uD83D\uDD14 ${state.activeCount} alerts"
        }
    }

    private fun clockFlow(): Flow<String> = tickerFlow(CLOCK_REFRESH_INTERVAL_MILLIS)
        .map { formatCurrentTime() }

    private fun formatCurrentTime(): String {
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun tickerFlow(periodMillis: Long): Flow<Unit> = flow {
        emit(Unit)
        while (true) {
            delay(periodMillis)
            emit(Unit)
        }
    }

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

    companion object {
        private const val CLOCK_REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
