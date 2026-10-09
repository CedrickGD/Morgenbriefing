package com.cedrickgd.morgenbriefing.ui.components

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "Details" under an AI error (chat card, key dialog): collapsed by default, it opens the short
 * technical line of what the provider answered — e.g. "HTTP 401 · UNAUTHENTICATED ·
 * ACCESS_TOKEN_TYPE_UNSUPPORTED" — selectable and with "Kopieren", so it can be sent along with
 * a question. [detail] never holds a key (ai/http/ErrorDiagnostics).
 */
@Composable
fun ErrorDetails(detail: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by rememberSaveable(detail) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = if (open) "Details ausblenden" else "Details zeigen") { open = !open }
                .heightIn(min = 48.dp)
                .padding(end = 8.dp)
                .semantics { stateDescription = if (open) "aufgeklappt" else "zugeklappt" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.width(2.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(visible = open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = color,
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Fehlerdetails", detail)))
                            // Android 13+ confirms copies itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, "Details kopiert", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kopieren")
                }
            }
        }
    }
}
