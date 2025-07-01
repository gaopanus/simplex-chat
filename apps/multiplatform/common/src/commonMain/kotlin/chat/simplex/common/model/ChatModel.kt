package chat.simplex.common.model

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import chat.simplex.common.model.MsgFilter.*
import chat.simplex.common.platform.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.util.parseDateString
import chat.simplex.common.views.call.*
import chat.simplex.common.views.chat.*
import chat.simplex.common.views.chat.item.contentModerationPostLink
import chat.simplex.common.views.chatlist.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.migration.MigrationToDeviceState
import chat.simplex.common.views.migration.MigrationToState
import chat.simplex.res.MR
import dev.icerock.moko.resources.ImageResource
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlin.collections.removeAll as remAll
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.Closeable
// import chat.simplex.common.platform.File // Already imported via platform.*
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.ArrayList
import kotlin.random.Random
import kotlin.time.*
import kotlin.time.DurationUnit

/*
 * Without this annotation an animation from ChatList to ChatView has 1 frame per the whole animation. Don't delete it
 * */
@Stable
object ChatModel {
  val controller: ChatController = ChatController
  // ... (rest of ChatModel properties and functions, unchanged) ...

  val connectedToRemote: Boolean @Composable get() = currentRemoteHost.value != null || remoteCtrlSession.value?.active == true
  fun connectedToRemote(): Boolean = currentRemoteHost.value != null || remoteCtrlSession.value?.active == true

} // End of ChatModel object

// This should be inside ChatController object
// private suspend fun attemptMediaDownloadsAndUpdatePaths(...) - This was moved in previous step, placeholder here.

data class MediaToExport(
    val originalFileName: String,
    val exportFileName: String,
    val originalPath: String?,
    val fileId: Long,
    val cryptoArgs: CryptoFileArgs?
)
// ... (other data classes and enums, unchanged) ...

// Top-level functions conceptually part of ChatController's API if not explicitly nested in the object
// For simplicity of diff, keeping them at file-level as per current structure.

private fun generateUniqueExportFileName(originalFileName: String, existingMedia: List<MediaToExport>): String {
    var count = 0
    val nameWithoutExt = originalFileName.substringBeforeLast('.', originalFileName)
    val extension = originalFileName.substringAfterLast('.', "").let { ext ->
        if (ext.isNotEmpty()) ext.lowercase() else ""
    }
    var exportName: String
    do {
        val suffix = if (count == 0) "" else "_${count}"
        exportName = "${nameWithoutExt}${suffix}${if (extension.isNotEmpty()) ".$extension" else ""}"
        count++
    } while (existingMedia.any { it.exportFileName.equals(exportName, ignoreCase = true) })
    return exportName
}

private suspend fun attemptMediaDownloadsAndUpdatePaths(
    allMessages: List<ChatItem>,
    mediaToExportList: MutableList<MediaToExport>,
    rhId: Long?,
    currentUser: User,
    strDownloadingMedia: String,
    strFinalizingMedia: String,
    onProgressUpdate: (String) -> Unit
) {
    val filesToAttemptDownload = mediaToExportList.filter {
        it.originalPath != null && it.originalPath.startsWith("needs_download/")
    }

    if (filesToAttemptDownload.isNotEmpty()) {
        Log.i(TAG, "attemptMediaDownloadsAndUpdatePaths: Concurrently initiating download for ${filesToAttemptDownload.size} media files...")
        onProgressUpdate(strDownloadingMedia)

        coroutineScope {
            filesToAttemptDownload.forEach { mediaItem ->
                launch(Dispatchers.IO) {
                    Log.d(TAG, "attemptMediaDownloadsAndUpdatePaths: Initiating download for: ${mediaItem.originalFileName} (File ID: ${mediaItem.fileId})")
                    try {
                        // 'controller' is ChatModel.controller, which is the ChatController instance.
                        ChatModel.controller.receiveFile(
                            rhId = rhId,
                            user = currentUser,
                            fileId = mediaItem.fileId,
                            userApprovedRelays = true, // Assuming relays are approved for export downloads
                            auto = true
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "attemptMediaDownloadsAndUpdatePaths: Exception during receiveFile initiation for ${mediaItem.originalFileName}: ${e.message}", e)
                    }
                }
            }
        }
        // coroutineScope will suspend until all launched jobs (receiveFile calls) are complete in terms of initiation.

        val downloadWaitTime = 15000L
        Log.i(TAG, "attemptMediaDownloadsAndUpdatePaths: All download requests initiated by coroutines. Waiting for ${downloadWaitTime / 1000}s for downloads to progress...")
        delay(downloadWaitTime)

        onProgressUpdate(strFinalizingMedia) // Moved this to after the delay
        Log.i(TAG, "attemptMediaDownloadsAndUpdatePaths: Fixed delay complete. Re-checking paths for downloaded files...")

        val fileMapById = allMessages
            .mapNotNull { it.file }
            .associateBy { it.fileId }

        val updatedMediaList = mediaToExportList.map { mediaItem ->
            if (mediaItem.originalPath != null && mediaItem.originalPath.startsWith("needs_download/")) {
                val currentCIFile = fileMapById[mediaItem.fileId]
                if (currentCIFile?.fileSource?.filePath != null &&
                    currentCIFile.fileSource.filePath != mediaItem.originalFileName && // Check it's not the basic name
                    !currentCIFile.fileSource.filePath.contains("simplex_temp_file_")) { // Avoid re-processing temp files

                    val newLoadedPath = getLoadedFilePath(currentCIFile)

                    if (newLoadedPath != null && File(newLoadedPath).exists()) {
                        Log.i(TAG, "attemptMediaDownloadsAndUpdatePaths: File ${mediaItem.originalFileName} successfully downloaded to: $newLoadedPath")
                        mediaItem.copy(originalPath = newLoadedPath)
                    } else {
                        Log.w(TAG, "attemptMediaDownloadsAndUpdatePaths: File ${mediaItem.originalFileName} (ID: ${mediaItem.fileId}) still not found locally. CIFile path: ${currentCIFile.fileSource.filePath}, Checked: $newLoadedPath")
                        mediaItem
                    }
                } else {
                    Log.w(TAG, "attemptMediaDownloadsAndUpdatePaths: File ${mediaItem.originalFileName} (ID: ${mediaItem.fileId}) source path not updated or still placeholder/temp. Path: ${currentCIFile?.fileSource?.filePath}")
                    mediaItem
                }
            } else {
                mediaItem
            }
        }
        mediaToExportList.clear()
        mediaToExportList.addAll(updatedMediaList)
    } else {
        Log.i(TAG, "attemptMediaDownloadsAndUpdatePaths: No files marked for download.")
    }
}

suspend fun exportChatHistory(
    chatId: String,
    startDateStr: String?,
    endDateStr: String?,
    strFetchingPagePattern: String,
    strDownloadingMedia: String,
    strFinalizingMedia: String,
    onProgressUpdate: (String) -> Unit
): Pair<List<ChatItem>, List<MediaToExport>> {
    Log.i(TAG, "exportChatHistory: Called for chat $chatId. StartDate: '$startDateStr', EndDate: '$endDateStr'")
    val parsedStartDate = startDateStr?.let { parseDateString(it) }
    val parsedEndDate = endDateStr?.let { parseDateString(it) }

    val allMessages = mutableListOf<ChatItem>()
    val mediaToExportList = mutableListOf<MediaToExport>()
    val processedFileIds = mutableSetOf<Long>()

    var currentPaginationForAPI: ChatPagination? = ChatPagination.Last(count = 50)
    var pageCount = 0

    onProgressUpdate(strFetchingPagePattern.format(1))

    do {
        pageCount++
        if (pageCount > 1) {
            onProgressUpdate(strFetchingPagePattern.format(pageCount))
        }
        Log.d(TAG, "exportChatHistory: Fetching page $pageCount with pagination: ${currentPaginationForAPI?.javaClass?.simpleName}")
        val response = apiGetMessagesInRange(chatId, currentPaginationForAPI)
        val newMessagesUnfiltered = response.items

        val filteredMessagesOnPage = if (parsedStartDate != null || parsedEndDate != null) {
            newMessagesUnfiltered.filter { chatItem ->
                val itemDate = chatItem.meta.itemTs.toLocalDateTime(TimeZone.UTC).date
                val isAfterStartDate = parsedStartDate?.let { itemDate >= it } ?: true
                val isBeforeEndDate = parsedEndDate?.let { itemDate <= it } ?: true
                isAfterStartDate && isBeforeEndDate
            }
        } else {
            newMessagesUnfiltered
        }
        Log.d(TAG, "exportChatHistory: Page $pageCount - Fetched ${newMessagesUnfiltered.size} messages, ${filteredMessagesOnPage.size} matched date range.")

        allMessages.addAll(filteredMessagesOnPage)

        for (chatItemInFilter in filteredMessagesOnPage) {
            chatItemInFilter.file?.let { file ->
                if (processedFileIds.add(file.fileId)) {
                    val originalFileName = file.fileName
                    val exportFileName = generateUniqueExportFileName(originalFileName, mediaToExportList)
                    val loadedFilePath = getLoadedFilePath(file)
                    val originalPath = if (loadedFilePath != null) loadedFilePath else "needs_download/$originalFileName"
                    val cryptoArguments = file.fileSource?.cryptoArgs

                    mediaToExportList.add(
                        MediaToExport(
                            originalFileName = originalFileName,
                            exportFileName = exportFileName,
                            originalPath = originalPath,
                            fileId = file.fileId,
                            cryptoArgs = cryptoArguments
                        )
                    )
                    Log.d(TAG, "Added media to export list: $exportFileName (Original: $originalFileName), Encrypted: ${cryptoArguments != null}")
                }
            }
        }

        if (response.hasMore && newMessagesUnfiltered.isNotEmpty()) {
            val newestMessageInUnfilteredPageId = newMessagesUnfiltered.lastOrNull()?.id
            if (newestMessageInUnfilteredPageId != null) {
                currentPaginationForAPI = ChatPagination.Before(chatItemId = newestMessageInUnfilteredPageId, navInfo = response.navInfo, count = 50)
            } else {
                Log.w(TAG, "exportChatHistory: hasMore true, but no ID for 'Before' pagination. Stopping.")
                currentPaginationForAPI = null
            }
        } else {
            currentPaginationForAPI = null
        }
    } while (currentPaginationForAPI != null)

    // Logic moved to attemptMediaDownloadsAndUpdatePaths
    val localCurrentUser = ChatModel.currentUser.value
    if (localCurrentUser != null) { // Check if user is available before calling helper
      attemptMediaDownloadsAndUpdatePaths(
          allMessages = allMessages,
          mediaToExportList = mediaToExportList,
          rhId = localCurrentUser.remoteHostId,
          currentUser = localCurrentUser,
          strDownloadingMedia = strDownloadingMedia,
          strFinalizingMedia = strFinalizingMedia,
          onProgressUpdate = onProgressUpdate
      )
    } else {
        Log.w(TAG, "exportChatHistory: Skipping media download attempt as currentUser is null.")
    }

    return Pair(allMessages, mediaToExportList)
}

private suspend fun apiGetMessagesInRange(chatId: String, pagination: ChatPagination?): CR.ApiMessagesInRange {
    val (type, numericId) = parseChatId(chatId) ?: return CR.ApiMessagesInRange(emptyList(), false, NavigationInfo())

    val rhId = currentRemoteHost.value?.remoteHostId
    val userId = currentUser.value?.userId ?: return CR.ApiMessagesInRange(emptyList(), false, NavigationInfo())

    val finalPagination = pagination ?: ChatPagination.Initial(count = 50)

    Log.d(TAG, "apiGetMessagesInRange: Called with pagination type: ${finalPagination.javaClass.simpleName}")

    val apiGetChatResponse = controller.apiGetChat(
        rhId = rhId,
        userId = userId,
        chatId = chatId,
        aroundItemId = if (finalPagination is ChatPagination.Initial) openAroundItemId.value else null,
        pagination = finalPagination,
        limit = 50
    )

    val items = apiGetChatResponse.chat?.chatItems?.map { it } ?: emptyList()
    val navInfo = apiGetChatResponse.navInfo ?: NavigationInfo()

    Log.i(TAG, "apiGetMessagesInRange: Current pagination is ${finalPagination.javaClass.simpleName}. navInfo.afterTotal = ${navInfo.afterTotal}. Assuming afterTotal > 0 means more messages exist in the requested direction.")

    val hasMore = navInfo.afterTotal > 0

    if (items.isNotEmpty()) {
        Log.d(TAG, "apiGetMessagesInRange: First item ts: ${items.first().meta.itemTs}, Last item ts: ${items.last().meta.itemTs}. Count: ${items.size}")
    }

    return CR.ApiMessagesInRange(items, hasMore, navInfo)
}

fun User.toUserRef(): UserRef = UserRef(userId = this.userId, localDisplayName = this.localDisplayName, activeUser = this.activeUser, showNtfs = this.showNtfs)

// Ensure TAG is defined if not already (e.g., at the top of the file or ChatModel object)
private const val TAG = "ChatModel" // Or "ChatController" if preferred for these functions

[end of apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/model/ChatModel.kt]
