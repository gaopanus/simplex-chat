package chat.simplex.common.views.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import chat.simplex.res.MR // Assuming MR is your resource class

@Composable
fun ExportChatDialog(
    onConfirm: (startDate: String, endDate: String) -> Unit,
    onCancel: () -> Unit,
    isExporting: Boolean,
    exportProgressMessage: String
) {
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onCancel() },
        title = { Text(stringResource(MR.strings.export_chat_history_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (isExporting) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                    Text(exportProgressMessage)
                } else {
                    Text(stringResource(MR.strings.export_date_range_selection))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text(stringResource(MR.strings.export_start_date)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text(stringResource(MR.strings.export_end_date)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )
                }
            }
        },
        buttons = {
            Row(
                modifier = Modifier.padding(all = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel, enabled = !isExporting) {
                    Text(stringResource(MR.strings.cancel_verb))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(startDate, endDate) },
                    enabled = !isExporting
                ) {
                    Text(if (isExporting) stringResource(MR.strings.exporting_button) else stringResource(MR.strings.confirm_verb)) // Add exporting_button string
                }
            }
        }
    )
}
