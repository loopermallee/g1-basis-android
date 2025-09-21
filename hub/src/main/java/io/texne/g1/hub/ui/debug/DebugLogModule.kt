package io.texne.g1.hub.ui.debug

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugLogModule {
    @Multibinds
    abstract fun bindDebugLogProviders(): Set<DebugLogProvider>
}
