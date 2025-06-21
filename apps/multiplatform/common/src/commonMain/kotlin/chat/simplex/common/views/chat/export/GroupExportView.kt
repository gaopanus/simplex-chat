package chat.simplex.common.views.chat.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.simplex.common.model.Chat
import chat.simplex.common.model.ChatInfo // Added import
import chat.simplex.common.model.ChatModel
import chat.simplex.common.export.HtmlExporter
import chat.simplex.common.platform.File
import chat.simplex.common.platform.filesDir
import chat.simplex.common.platform.separator
import chat.simplex.common.platform.NavController
import chat.simplex.common.platform.DummyNavController // Assuming it's moved here
import chat.simplex.common.platform.ColumnWithScrollBar
import chat.simplex.common.platform.Log
// import chat.simplex.common.platform.showToast // showToast might be platform specific, using AlertManager
import chat.simplex.common.views.helpers.AlertManager
import chat.simplex.common.views.helpers.DefaultTopAppBar
import chat.simplex.common.views.helpers.ModalView
import chat.simplex.common.views.helpers.SimpleButton
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import chat.simplex.res.MR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun GroupExportView(
    chatModel: ChatModel,
    chat: Chat,
    navController: NavController, // Using the imported NavController
    close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isExporting = remember { mutableStateOf(false) }
    val exportProgressMessage = remember { mutableStateOf("") }

    // Pre-resolve string resources
    val progressStartingMsg = stringResource(MR.strings.export_progress_starting)
    val progressFetchingMsg = stringResource(MR.strings.export_progress_fetching)
    val progressGeneratingHtmlMsg = stringResource(MR.strings.export_progress_generating_html)
    val progressSavingMsg = stringResource(MR.strings.export_progress_saving)
    val exportCompleteTitle = stringResource(MR.strings.export_chat_history_title)
    val exportCompleteMsgPattern = stringResource(MR.strings.export_complete_message)
    val exportFailedSavingTitle = stringResource(MR.strings.error_alert_title)
    val exportFailedSavingMsg = stringResource(MR.strings.export_failed_saving)
    val exportFailedGenericTitle = stringResource(MR.strings.error_alert_title)
    val exportFailedGenericMsgPattern = stringResource(MR.strings.export_failed_generic)
    val backButtonDesc = stringResource(MR.strings.back)


    val startDate = rememberSaveable { mutableStateOf("") }
    val endDate = rememberSaveable { mutableStateOf("") }
    val initialExportPath = try {
        filesDir.absolutePath + separator + "chat_exports"
    } catch (e: Exception) {
        "/tmp/chat_exports"
    }
    val exportFolderPath = rememberSaveable { mutableStateOf(initialExportPath) }
    val oneHandUI = ChatModel.controller.appPrefs.oneHandUI.state

    ModalView(
        close = { if (!isExporting.value) close() },
        appBar = {
            DefaultTopAppBar(
                title = { Text(exportCompleteTitle) }, // Using pre-resolved for consistency, though direct is fine here
                navigationIcon = {
                    IconButton(onClick = { if (!isExporting.value) close() }) {
                        Icon(
                            painter = painterResource(MR.images.ic_arrow_back),
                            contentDescription = backButtonDesc
                        )
                    }
                },
                onTop = !oneHandUI.value
            )
        },
        content = { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                ColumnWithScrollBar(
                     modifier = Modifier.fillMaxSize() // Fill the box
                ) {
                    Column(modifier = Modifier.padding(16.dp)) { // Content padding
                        Text(stringResource(MR.strings.export_section_date_range), style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = startDate.value,
                            onValueChange = { startDate.value = it },
                            label = { Text(stringResource(MR.strings.export_label_start_date)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExporting.value
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = endDate.value,
                            onValueChange = { endDate.value = it },
                            label = { Text(stringResource(MR.strings.export_label_end_date)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExporting.value
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(stringResource(MR.strings.export_section_location), style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = exportFolderPath.value,
                            onValueChange = { exportFolderPath.value = it },
                            label = { Text(stringResource(MR.strings.export_label_folder_path)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExporting.value
                        )
                        Text(
                            stringResource(MR.strings.export_path_note),
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        SimpleButton(
                            modifier = Modifier.fillMaxWidth(),
                            buttonText = stringResource(MR.strings.export_button_start),
                            disabled = isExporting.value,
                            click = {
                                isExporting.value = true
                                exportProgressMessage.value = progressStartingMsg
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        exportProgressMessage.value = progressFetchingMsg
                                        val (messagesFromController, mediaFilesToExport) = chatModel.controller.exportChatHistory(chat.id, startDate.value, endDate.value)
                                        val messagesForReport = messagesFromController.asReversed() // Reverse for oldest-first display

                                        exportProgressMessage.value = progressGeneratingHtmlMsg
                                        val htmlContent = HtmlExporter.generateHtmlReport(
                                            chatName = chat.chatInfo.displayName, // Or a more specific name if available
                                            startDate = startDate.value,
                                            endDate = endDate.value,
                                            messages = messagesForReport, // Use the reversed list
                                            mediaFiles = mediaFilesToExport
                                        )

                                        exportProgressMessage.value = progressSavingMsg
                                        File(exportFolderPath.value).mkdirs()
                                        val success = HtmlExporter.saveExportedData(
                                            targetDirectoryPath = exportFolderPath.value,
                                            htmlContent = htmlContent,
                                            mediaFiles = mediaFilesToExport,
                                            chatName = chat.chatInfo.displayName
                                        )

                                        if (success) {
                                            val successAlertMsg = exportCompleteMsgPattern.format(exportFolderPath.value)
                                            exportProgressMessage.value = successAlertMsg
                                            AlertManager.shared.showAlertMsg(title = exportCompleteTitle, text = successAlertMsg, onDismiss = { close() })
                                        } else {
                                            exportProgressMessage.value = exportFailedSavingMsg
                                            AlertManager.shared.showAlertMsg(title = exportFailedSavingTitle, text = exportFailedSavingMsg)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("GroupExportView", "Export failed: ${e.localizedMessage ?: e.toString()}", e)
                                        val errorMsgText = e.localizedMessage ?: "Unknown error"
                                        exportProgressMessage.value = exportFailedGenericMsgPattern.format(errorMsgText)
                                        AlertManager.shared.showAlertMsg(title = exportFailedGenericTitle, text = exportProgressMessage.value)
                                    } finally {
                                        isExporting.value = false
                                    }
                                }
                            }
                        )
                    }
                }

                if (isExporting.value) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colors.surface.copy(alpha = 0.7f))
                            .clickable(enabled = false, onClick = {}),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = exportProgressMessage.value,
                                style = MaterialTheme.typography.body1,
                                color = MaterialTheme.colors.onSurface
                            )
                        }
                    }
                }
            }
        }
    )
}
