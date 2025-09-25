package io.texne.g1.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.google.android.material.button.MaterialButton

class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_crash)

        val cause = intent.getStringExtra(EXTRA_CAUSE).orEmpty()
        val stackTrace = intent.getStringExtra(EXTRA_STACKTRACE).orEmpty()
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME).orEmpty()

        bindCrashDetails(threadName = threadName, cause = cause, stackTrace = stackTrace)
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

    private fun bindCrashDetails(threadName: String, cause: String, stackTrace: String) {
        val threadView = findViewById<TextView>(R.id.crashThread)
        val causeView = findViewById<TextView>(R.id.crashCause)
        val stacktraceLabel = findViewById<TextView>(R.id.crashStacktraceLabel)
        val stacktraceView = findViewById<TextView>(R.id.crashStacktrace)
        val noDetailsView = findViewById<TextView>(R.id.crashNoDetails)

        val hasThread = threadName.isNotBlank()
        val hasCause = cause.isNotBlank()
        val hasStacktrace = stackTrace.isNotBlank()

        if (hasThread) {
            threadView.text = getString(R.string.crash_thread, threadName)
            threadView.visibility = View.VISIBLE
        } else {
            threadView.visibility = View.GONE
        }

        if (hasCause) {
            causeView.text = getString(R.string.crash_cause, cause)
            causeView.visibility = View.VISIBLE
        } else {
            causeView.visibility = View.GONE
        }

        if (hasStacktrace) {
            stacktraceLabel.visibility = View.VISIBLE
            stacktraceView.text = stackTrace
            stacktraceView.visibility = View.VISIBLE
        } else {
            stacktraceLabel.visibility = View.GONE
            stacktraceView.visibility = View.GONE
        }

        noDetailsView.visibility = if (hasThread || hasCause || hasStacktrace) {
            View.GONE
        } else {
            View.VISIBLE
        }

        findViewById<MaterialButton>(R.id.crashCopyButton).setOnClickListener {
            copyCrashDetails(threadName, cause, stackTrace)
        }

        findViewById<MaterialButton>(R.id.crashCloseButton).setOnClickListener {
            finishAffinity()
        }
    }

    companion object {
        const val EXTRA_CAUSE = "extra_cause"
        const val EXTRA_STACKTRACE = "extra_stacktrace"
        const val EXTRA_THREAD_NAME = "extra_thread_name"
    }
}
