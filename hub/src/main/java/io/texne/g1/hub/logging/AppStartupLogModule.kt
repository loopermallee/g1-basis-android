package io.texne.g1.hub.logging

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.texne.g1.hub.ui.debug.DebugLogProvider

@Module
@InstallIn(SingletonComponent::class)
interface AppStartupLogModule {
    @Binds
    @IntoSet
    fun bindAppStartupLogProvider(logger: AppStartupLogger): DebugLogProvider
}
