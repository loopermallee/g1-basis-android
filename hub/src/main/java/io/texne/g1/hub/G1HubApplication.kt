package io.texne.g1.hub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
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
import io.texne.g1.hub.logging.AppStartupLogger
import javax.inject.Singleton
import kotlin.system.exitProcess
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
class G1HubApplication: Application() {

    @javax.inject.Inject
    lateinit var startupLogger: AppStartupLogger

    override fun onCreate() {
        super.onCreate()

        startupLogger.recordAppLaunched()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            startupLogger.recordUncaughtException(thread, throwable)
            val handled = handleUncaughtException(thread, throwable)
            if (!handled) {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable): Boolean {
        val stackTrace = android.util.Log.getStackTraceString(throwable)
        val cause = throwable.toString()

        val intent = Intent(this, CrashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
            putExtra(CrashActivity.EXTRA_CAUSE, cause)
            putExtra(CrashActivity.EXTRA_STACKTRACE, stackTrace)
        }

        val started = runCatching { startActivity(intent) }.isSuccess

        if (!started) {
            return false
        }

        Process.killProcess(Process.myPid())
        exitProcess(10)

        return true
    }
}
