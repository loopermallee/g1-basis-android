package io.texne.g1.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.texne.g1.hub.ui.theme.EyeOfKilroggTheme

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cause = intent.getStringExtra(EXTRA_CAUSE).orEmpty()
        val stackTrace = intent.getStringExtra(EXTRA_STACKTRACE).orEmpty()
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME).orEmpty()

        setContent {
            EyeOfKilroggTheme {
                CrashScreen(
                    threadName = threadName,
                    cause = cause,
                    stackTrace = stackTrace,
                    onCopy = { copyCrashDetails(threadName, cause, stackTrace) },
                    onClose = { finishAffinity() }
                )
            }
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

@Composable
private fun CrashScreen(
    threadName: String,
    cause: String,
    stackTrace: String,
    onCopy: () -> Unit,
    onClose: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.crash_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.crash_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val scrollState = rememberScrollState()
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (threadName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.crash_thread, threadName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (cause.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.crash_cause, cause),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (stackTrace.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.crash_stacktrace_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stackTrace,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (threadName.isBlank() && cause.isBlank() && stackTrace.isBlank()) {
                        Text(
                            text = stringResource(R.string.crash_no_details),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onCopy) {
                    Text(stringResource(R.string.crash_copy_button))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.crash_close_button))
                }
            }
        }
    }
}
