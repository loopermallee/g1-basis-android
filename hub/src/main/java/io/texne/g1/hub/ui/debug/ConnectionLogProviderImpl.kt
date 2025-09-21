package io.texne.g1.hub.ui.debug

import io.texne.g1.hub.model.Repository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionLogProviderImpl @Inject constructor(
    private val repository: Repository
) : DebugLogProvider {
    override val name: String = "Connection"

    override suspend fun getLogs(): List<String> = repository.getConnectionLogs()
}
