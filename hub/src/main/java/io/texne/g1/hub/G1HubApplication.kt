package io.texne.g1.hub

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.texne.g1.hub.BuildConfig
import io.texne.g1.hub.assistant.AssistantActivationGesture
import io.texne.g1.hub.assistant.AssistantPreferences
import io.texne.g1.hub.ble.G1Connector
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

    @Provides
    @Singleton
    fun provideG1Connector(
        @ApplicationContext context: Context
    ): G1Connector = G1Connector(context)
}

@HiltAndroidApp
class G1HubApplication: Application()
