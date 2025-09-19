package io.texne.g1.hub

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Singleton

import io.texne.g1.hub.BuildConfig
import io.texne.g1.hub.settings.HUD_WIDGET_DATA_STORE_NAME
import io.texne.g1.hub.settings.hudWidgetDataStore
import javax.inject.Named

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
    @Named(HUD_WIDGET_DATA_STORE_NAME)
    fun provideHudWidgetDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.hudWidgetDataStore
}

@HiltAndroidApp
class G1HubApplication: Application()
