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
import chat.simplex.common.model.ChatInfo
import chat.simplex.common.model.ChatModel
import chat.simplex.common.export.HtmlExporter
import chat.simplex.common.platform.File
import chat.simplex.common.platform.filesDir
import chat.simplex.common.platform.separator
import chat.simplex.common.platform.NavController
import chat.simplex.common.platform.DummyNavController // Keep if used, or remove if NavController is concrete
import chat.simplex.common.platform.ColumnWithScrollBar
import chat.simplex.common.platform.Log
import chat.simplex.common.util.parseDateString
import chat.simplex.common.views.helpers.AlertManager
import chat.simplex.common.views.helpers.DefaultTopAppBar
import chat.simplex.common.views.helpers.ModalView
import chat.simplex.common.views.helpers.SimpleButton
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import chat.simplex.res.MR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDate // Ensure this is imported for comparison if needed, though parseDateString returns LocalDate

@Composable
fun GroupExportView(
    chatModel: ChatModel,
    chat: Chat,
    navController: NavController,
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
    val exportCompleteTitle = stringResource(MR.strings.export_chat_history_title) // Used for success as well
    val exportCompleteMsgPattern = stringResource(MR.strings.export_complete_message)
    val exportFailedSavingTitle = stringResource(MR.strings.error_alert_title)
    val exportFailedSavingMsg = stringResource(MR.strings.export_failed_saving)
    val exportFailedGenericTitle = stringResource(MR.strings.error_alert_title)
    val exportFailedGenericMsgPattern = stringResource(MR.strings.export_failed_generic)
    val backButtonDesc = stringResource(MR.strings.back)

    // New string resources for date validation alerts
    val invalidStartDateTitle = stringResource(MR.strings.export_error_invalid_start_date_title)
    val invalidEndDateTitle = stringResource(MR.strings.export_error_invalid_end_date_title)
    val invalidDateFormatDetails = stringResource(MR.strings.export_error_invalid_date_format_details)
    val invalidDateRangeTitle = stringResource(MR.strings.export_error_invalid_date_range_title)
    val startAfterEndDateDetails = stringResource(MR.strings.export_error_start_after_end_date_details)


    val startDate = rememberSaveable { mutableStateOf("") }
    val endDate = rememberSaveable { mutableStateOf("") }
    val initialExportPath = try {
        filesDir.absolutePath + separator + "chat_exports"
    } catch (e: Exception) {
        // Fallback for environments where filesDir might not be immediately available or fails
        // This is more a safeguard for preview/testing, actual device should have filesDir
        Log.w("GroupExportView", "Failed to get filesDir: ${e.message}. Using fallback path.")
        "/tmp/chat_exports"
    }
    val exportFolderPath = rememberSaveable { mutableStateOf(initialExportPath) }
    val oneHandUI = ChatModel.controller.appPrefs.oneHandUI.state

    ModalView(
        close = { if (!isExporting.value) close() },
        appBar = {
            DefaultTopAppBar(
                title = { Text(stringResource(MR.strings.export_chat_history_title)) },
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
                     modifier = Modifier.fillMaxSize()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(MR.strings.export_section_date_range), style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = startDate.value,
                            onValueChange = { startDate.value = it },
                            label = { Text(stringResource(MR.strings.export_label_start_date)) },
                            placeholder = { Text(stringResource(MR.strings.export_date_hint_yyyy_mm_dd)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExporting.value
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = endDate.value,
                            onValueChange = { endDate.value = it },
                            label = { Text(stringResource(MR.strings.export_label_end_date)) },
                            placeholder = { Text(stringResource(MR.strings.export_date_hint_yyyy_mm_dd)) },
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
                                val startDateStr = startDate.value
                                val endDateStr = endDate.value

                                val parsedStartDateLocal = parseDateString(startDateStr)
                                val parsedEndDateLocal = parseDateString(endDateStr)

                                if (startDateStr.isNotBlank() && parsedStartDateLocal == null) {
                                    AlertManager.shared.showAlertMsg(
                                        title = invalidStartDateTitle,
                                        text = invalidDateFormatDetails
                                    )
                                    return@SimpleButton
                                }

                                if (endDateStr.isNotBlank() && parsedEndDateLocal == null) {
                                    AlertManager.shared.showAlertMsg(
                                        title = invalidEndDateTitle,
                                        text = invalidDateFormatDetails
                                    )
                                    return@SimpleButton
                                }

                                if (parsedStartDateLocal != null && parsedEndDateLocal != null && parsedStartDateLocal > parsedEndDateLocal) {
                                    AlertManager.shared.showAlertMsg(
                                        title = invalidDateRangeTitle,
                                        text = startAfterEndDateDetails
                                    )
                                    return@SimpleButton
                                }

                                isExporting.value = true
                                exportProgressMessage.value = progressStartingMsg
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        exportProgressMessage.value = progressFetchingMsg
                                        // Pass original string dates to the controller
                                        val (messagesFromController, mediaFilesToExport) = chatModel.controller.exportChatHistory(chat.id, startDateStr, endDateStr)
                                        val messagesForReport = messagesFromController.asReversed()

                                        exportProgressMessage.value = progressGeneratingHtmlMsg
                                        val htmlContent = HtmlExporter.generateHtmlReport(
                                            chatName = chat.chatInfo.displayName,
                                            startDate = startDateStr,
                                            endDate = endDateStr,
                                            messages = messagesForReport,
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
                                        val formattedGenericErrorMsg = exportFailedGenericMsgPattern.format(errorMsgText)
                                        exportProgressMessage.value = formattedGenericErrorMsg
                                        AlertManager.shared.showAlertMsg(title = exportFailedGenericTitle, text = formattedGenericErrorMsg)
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

[end of apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/views/chat/export/GroupExportView.kt]
