package io.texne.g1.hub

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.texne.g1.hub.BuildConfig
import io.texne.g1.hub.assistant.AssistantActivationGesture
import io.texne.g1.hub.assistant.AssistantPreferences
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object GlobalModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideAssistantActivationGesture(
        assistantPreferences: AssistantPreferences
    ): StateFlow<AssistantActivationGesture> =
        assistantPreferences.observeActivationGesture()
}

@HiltAndroidApp
class G1HubApplication: Application()
