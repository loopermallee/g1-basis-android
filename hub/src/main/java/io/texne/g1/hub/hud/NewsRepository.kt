package io.texne.g1.hub.hud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NewsRepository @Inject constructor() {

    private val _news = MutableStateFlow(NewsDigest())
    val news: StateFlow<NewsDigest> = _news.asStateFlow()

    fun update(digest: NewsDigest) {
        _news.value = digest
    }
}

data class NewsDigest(
    val headlines: List<String> = emptyList(),
    val source: String? = null,
    val isStale: Boolean = true
) {
    val primaryHeadline: String?
        get() = headlines.firstOrNull()
}
