package com.usbmediaexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbmediaexplorer.ui.theme.UsbMediaExplorerTheme
import java.io.File

/**
 * Post-crash report screen.
 *
 * Runs in a separate `:crash` process on purpose: when the main process dies from an uncaught
 * exception (or is killed right after by the crash handler), this activity — and the stack
 * trace it shows — survives. That turns "the app keeps stopping" into a readable, shareable
 * report instead of a dead end.
 *
 * The trace arrives via [EXTRA_TRACE]; if the intent extra was dropped (it can be large), the
 * activity falls back to the copy the handler always writes to [REPORT_FILE] in filesDir,
 * which every process of the app can read.
 */
class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE)?.takeIf { it.isNotBlank() }
            ?: runCatching { File(filesDir, REPORT_FILE).readText() }.getOrDefault("")
            .ifBlank { "(no crash report was written)" }
        setContent {
            UsbMediaExplorerTheme {
                CrashReportScreen(
                    trace = trace,
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "USB Media Explorer — crash report")
                            putExtra(Intent.EXTRA_TEXT, trace)
                        }
                        runCatching { startActivity(Intent.createChooser(send, null)) }
                    },
                    onCopy = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("crash report", trace))
                        Toast.makeText(this, "تم نسخ التقرير", Toast.LENGTH_SHORT).show()
                    },
                    onClose = { finishAffinity() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_TRACE = "com.usbmediaexplorer.extra.CRASH_TRACE"
        const val REPORT_FILE = "crash_report.txt"
    }
}

@Composable
private fun CrashReportScreen(
    trace: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text("تعطّل التطبيق", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "التقرير أدناه يوضح سبب التعطل بدقة. انسخه أو شاركه حتى يمكن إصلاح المشكلة.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onShare) { Text("مشاركة") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCopy) { Text("نسخ") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose) { Text("إغلاق") }
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = trace,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
