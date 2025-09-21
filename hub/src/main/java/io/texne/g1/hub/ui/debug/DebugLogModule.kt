package io.texne.g1.hub.ui.debug

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugLogModule {
    @Multibinds
    abstract fun bindDebugLogProviders(): Set<DebugLogProvider>

    @Binds
    @IntoSet
    abstract fun bindConnectionLogProvider(
        provider: ConnectionLogProviderImpl
    ): DebugLogProvider
}
