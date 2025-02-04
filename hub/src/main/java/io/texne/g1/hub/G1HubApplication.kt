package io.texne.g1.hub

import android.app.Application
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object GlobalModule

@HiltAndroidApp
class G1HubApplication: Application()