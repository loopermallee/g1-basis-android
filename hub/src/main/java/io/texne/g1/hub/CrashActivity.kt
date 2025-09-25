package io.texne.g1.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import io.texne.g1.hub.databinding.ActivityCrashBinding

class CrashActivity : ComponentActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cause = intent.getStringExtra(EXTRA_CAUSE).orEmpty()
        val stackTrace = intent.getStringExtra(EXTRA_STACKTRACE).orEmpty()
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME).orEmpty()

        renderCrashDetails(threadName, cause, stackTrace)

        binding.buttonCopy.setOnClickListener {
            copyCrashDetails(threadName, cause, stackTrace)
        }
        binding.buttonClose.setOnClickListener {
            finishAffinity()
        }
    }

    private fun renderCrashDetails(threadName: String, cause: String, stackTrace: String) {
        if (threadName.isNotBlank()) {
            binding.textCrashThread.isVisible = true
            binding.textCrashThread.text = getString(R.string.crash_thread, threadName)
        }

        if (cause.isNotBlank()) {
            binding.textCrashCause.isVisible = true
            binding.textCrashCause.text = getString(R.string.crash_cause, cause)
        }

        if (stackTrace.isNotBlank()) {
            binding.textCrashStacktraceLabel.isVisible = true
            binding.textCrashStacktrace.isVisible = true
            binding.textCrashStacktrace.text = stackTrace
        }

        if (threadName.isBlank() && cause.isBlank() && stackTrace.isBlank()) {
            binding.textCrashNoDetails.isVisible = true
        }
    }

    private fun copyCrashDetails(threadName: String, cause: String, stackTrace: String) {
        val clipboardManager = getSystemService<ClipboardManager>()
        if (clipboardManager == null) {
            Toast.makeText(this, R.string.crash_copy_failed, Toast.LENGTH_LONG).show()
            return
        }

        val text = buildString {
            if (threadName.isNotBlank()) {
                appendLine(getString(R.string.crash_thread, threadName))
            }
            if (cause.isNotBlank()) {
                appendLine(getString(R.string.crash_cause, cause))
            }
            if (stackTrace.isNotBlank()) {
                appendLine()
                append(stackTrace)
            }
        }.ifBlank { getString(R.string.crash_unknown) }

        clipboardManager.setPrimaryClip(ClipData.newPlainText("Crash details", text))
        Toast.makeText(this, R.string.crash_copy_success, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CAUSE = "extra_cause"
        const val EXTRA_STACKTRACE = "extra_stacktrace"
        const val EXTRA_THREAD_NAME = "extra_thread_name"
    }
}
