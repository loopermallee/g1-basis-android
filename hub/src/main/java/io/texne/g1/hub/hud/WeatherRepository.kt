package io.texne.g1.hub.hud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class WeatherRepository @Inject constructor() {

    private val _weather = MutableStateFlow(WeatherSnapshot.Unavailable)
    val weather: StateFlow<WeatherSnapshot> = _weather.asStateFlow()

    fun update(snapshot: WeatherSnapshot) {
        _weather.value = snapshot
    }
}

data class WeatherSnapshot(
    val condition: String? = null,
    val temperatureCelsius: Double? = null,
    val temperatureFahrenheit: Double? = null,
    val locationLabel: String? = null,
    val isStale: Boolean = true
) {
    companion object {
        val Unavailable = WeatherSnapshot()
    }
}
