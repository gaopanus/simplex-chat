package chat.simplex.common.export

import chat.simplex.common.model.ChatItem
import chat.simplex.common.model.MediaToExport
import chat.simplex.common.model.CIDirection
import chat.simplex.common.model.MsgContent
import chat.simplex.common.model.CryptoFileArgs // Added for mediaItem.cryptoArgs
import chat.simplex.common.model.decryptCryptoFile // Corrected import
import chat.simplex.common.platform.File // Already present
import chat.simplex.common.platform.Log // Added for logging
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate // Required for LocalDate.Format
import kotlinx.datetime.LocalDateTime // Required for LocalDateTime.Format
import kotlinx.datetime.format.char
import kotlinx.datetime.format.MonthNames
// DayOfWeekNames not used
// FormatStringsInDatetimeFormats not used
// byUnicodePattern not used

object HtmlExporter {

    private const val TAG = "HtmlExporter" // Added TAG for logging

    // Main HTML structure with CSS and JS (content unchanged, truncated for brevity)
    private val HTML_TEMPLATE = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Exported Chat: %CHAT_NAME%</title>
        <style>
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen-Sans, Ubuntu, Cantarell, "Helvetica Neue", sans-serif; margin: 0; padding: 0; background-color: #f0f2f5; color: #1c1e21; display: flex; flex-direction: column; min-height: 100vh; }
            .container { max-width: 800px; margin: 0 auto; padding: 20px; width: 100%; box-sizing: border-box; flex-grow: 1; display: flex; flex-direction: column; }
            .header { text-align: center; padding-bottom: 20px; border-bottom: 1px solid #ced0d4; }
            .header h1 { font-size: 24px; font-weight: 600; margin: 0 0 5px 0; }
            .header p { font-size: 14px; color: #606770; margin: 0; }
            .chat-log { padding-top: 20px; flex-grow: 1; overflow-y: auto; }
            .message { display: flex; margin-bottom: 12px; position: relative; }
            .message .userpic { width: 40px; height: 40px; border-radius: 50%; background-color: #ccc; margin-right: 10px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-weight: bold; color: white; overflow: hidden; }
            .message .userpic .initials { font-size: 16px; }
            .userpic0 { background-color: #FF5733; } .userpic1 { background-color: #33FF57; } .userpic2 { background-color: #3357FF; }
            .userpic3 { background-color: #FF33A1; } .userpic4 { background-color: #FFD700; } .userpic5 { background-color: #ADFF2F; }
            .userpic6 { background-color: #00FFFF; } .userpic7 { background-color: #FF00FF; } .userpic8 { background-color: #D2691E; }
            .userpic9 { background-color: #6A5ACD; }
            .message .content { display: flex; flex-direction: column; max-width: 75%; }
            .message .bubble { background-color: #fff; border-radius: 18px; padding: 8px 12px; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1); position: relative; }
            .message.sent { flex-direction: row-reverse; }
            .message.sent .userpic { margin-left: 10px; margin-right: 0; }
            .message.sent .bubble { background-color: #0084ff; color: white; }
            .message.sent .bubble .name, .message.sent .bubble .time, .message.sent .bubble .details, .message.sent .bubble .forwarded_from_name { color: #e0e0e0; }
            .message.sent .bubble .text a { color: #ccffff; }
            .message.received .bubble { background-color: #e4e6eb; color: #050505; }
            .message .name { font-weight: 600; font-size: 14px; margin-bottom: 2px; }
            .message .forwarded { font-size: 12px; color: #606770; margin-bottom: 3px; }
            .message .forwarded_from_name { font-weight: bold; }
            .message .text { font-size: 15px; line-height: 1.4; word-wrap: break-word; white-space: pre-wrap; }
            .message .text img.emoji { width: 20px; height: 20px; vertical-align: middle; }
            .message .time { font-size: 12px; color: #606770; margin-top: 4px; text-align: right; }
            .message.service { justify-content: center; margin: 15px 0; }
            .message.service .body { background-color: #d6e9ff; color: #303E4D; padding: 8px 15px; border-radius: 15px; font-size: 13px; box-shadow: 0 1px 1px rgba(0,0,0,0.05); text-align: center; }
            .media_attachment { margin-top: 5px; border-radius: 10px; overflow: hidden; max-width: 300px; }
            .media_attachment img, .media_attachment video { display: block; max-width: 100%; height: auto; border-radius: 10px; }
            .media_attachment audio { width: 100%; }
            .media_attachment .file_attachment { padding: 10px; background-color: #f0f0f0; border-radius: 10px; display: flex; align-items: center; }
            .media_attachment .file_attachment .icon { font-size: 24px; margin-right: 10px; }
            .media_attachment .file_attachment .title { font-weight: 500; }
            .media_attachment .file_attachment .size { font-size: 12px; color: #606770; }
            .media_attachment .media_description { padding: 8px; background-color: rgba(0,0,0,0.03); font-size: 13px; }
            .web_page_preview { margin-top: 8px; border: 1px solid #ddd; border-radius: 8px; overflow: hidden; text-decoration: none; color: inherit; display: block; }
            .web_page_preview img { display: block; width: 100%; max-height: 150px; object-fit: cover; }
            .web_page_preview .preview_content { padding: 10px; }
            .web_page_preview .preview_title { font-weight: bold; font-size: 14px; margin-bottom: 4px; }
            .web_page_preview .preview_description { font-size: 13px; color: #606770; margin-bottom: 4px; max-height: 50px; overflow: hidden; }
            .web_page_preview .preview_site_name { font-size: 12px; color: #606770; }
            .footer { text-align: center; padding: 15px 0; margin-top: 20px; border-top: 1px solid #ced0d4; font-size: 12px; color: #606770; }
            .message .content .reply_to { font-size: 0.85em; margin-bottom: 4px; padding: 4px 8px; background-color: rgba(0,0,0,0.05); border-radius: 6px; }
            .message .content .reply_to .name { font-weight: bold; }
            .message .content .reply_to .text { font-style: italic; }

            /* User specific colors for names */
            .name.usercolor0 { color: #d32f2f; } .name.usercolor1 { color: #388e3c; } .name.usercolor2 { color: #1976d2; }
            .name.usercolor3 { color: #c2185b; } .name.usercolor4 { color: #fbc02d; } .name.usercolor5 { color: #7cb342; }
            .name.usercolor6 { color: #0097a7; } .name.usercolor7 { color: #7b1fa2; } .name.usercolor8 { color: #6d4c41; }
            .name.usercolor9 { color: #455a64; }
        </style>
        <script>
            function toggleMedia(mediaId) {
                var media = document.getElementById(mediaId);
                if (media.style.display === "none") {
                    media.style.display = "block";
                } else {
                    media.style.display = "none";
                }
            }
        </script>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>%CHAT_NAME%</h1>
                <p>Exported messages%EXPORT_RANGE%</p>
            </div>
            <div class="chat-log" id="chat-log">
                <!-- Messages will be injected here -->
                %MESSAGES_HTML%
            </div>
            <div class="footer">
                Export generated on %EXPORT_TIMESTAMP%
            </div>
        </div>
    </body>
    </html>
    """.trimIndent()


    fun generateHtmlReport(
        chatName: String,
        startDate: String, // YYYY-MM-DD or empty
        endDate: String,   // YYYY-MM-DD or empty
        messages: List<ChatItem>,
        mediaFiles: List<MediaToExport>
    ): String {
        val messagesHtml = StringBuilder()

        // Date formatting for grouping keys
        val dateGroupFormatter = LocalDate.Format {
            dayOfMonth()
            char(' ')
            monthName(MonthNames.ENGLISH_FULL)
            char(' ')
            year()
        }

        if (messages.isEmpty()) {
            messagesHtml.append("""<div class="message service"><div class="body details">No messages found in the selected range.</div></div>""")
        } else {
            val groupedMessages = messages.groupBy {
                val date = it.meta.itemTs.toLocalDateTime(TimeZone.currentSystemDefault()).date
                date.format(dateGroupFormatter)
            }

            for ((dateStr, messagesOnDate) in groupedMessages) {
                val dateId = dateStr.replace(" ", "-").lowercase()
                messagesHtml.append(
                    """<div class="message service" id="message-DATE-${dateId}"><div class="body details">${htmlEncode(dateStr)}</div></div>"""
                )

                for (message in messagesOnDate) {
                    val senderName = when (message.chatDir) {
                        is CIDirection.GroupRcv -> message.chatDir.groupMember.displayName
                        is CIDirection.DirectSnd, is CIDirection.GroupSnd, is CIDirection.LocalSnd -> "You"
                        else -> "Unknown Sender"
                    }
                    val initials = getInitials(senderName)
                    val userPicColorClass = "userpic${(senderName.hashCode().absoluteValue % 10)}"
                    val nameColorClass = "usercolor${(senderName.hashCode().absoluteValue % 10)}"

                    val itemTsLocal = message.meta.itemTs.toLocalDateTime(TimeZone.currentSystemDefault())
                    val shortTime = itemTsLocal.format(LocalDateTime.Format { hour(); char(':'); minute() })
                    val fullTimestamp = itemTsLocal.format(LocalDateTime.Format {
                        year(); char('-'); monthNumber(padded = true); char('-'); dayOfMonth(padded = true); char(' ');
                        hour(padded = true); char(':'); minute(padded = true); char(':'); second(padded = true)
                    })

                    val msgClass = if (senderName == "You") "message sent" else "message received"
                    val msgId = "message-${message.id}"

                    messagesHtml.append("""<div class="$msgClass" id="$msgId">""")
                    messagesHtml.append("""<div class="userpic $userPicColorClass"><div class="initials">$initials</div></div>""")
                    messagesHtml.append("""<div class="content">""")
                    messagesHtml.append("""<div class="bubble">""")
                    messagesHtml.append("""<div class="name $nameColorClass">${htmlEncode(senderName)}</div>""")

                    message.meta.itemForwarded?.let { fw ->
                        val fromName = htmlEncode(fw.chatName ?: "Unknown Chat")
                        messagesHtml.append("""<div class="forwarded">Forwarded (from <span class="forwarded_from_name">$fromName</span>)</div>""")
                    }

                    message.meta.itemReplied?.let { reply ->
                        val repliedToName = htmlEncode(reply.itemSenderName ?: "Unknown User")
                        var replyTextSnippet = htmlEncode(reply.itemText ?: "")
                        if (replyTextSnippet.length > 75) {
                            replyTextSnippet = replyTextSnippet.substring(0, 72) + "..."
                        }
                        if (replyTextSnippet.isBlank() && reply.itemMedia != null) {
                            replyTextSnippet = "<i>Media content</i>"
                        }
                        messagesHtml.append("""<div class="reply_to">""")
                        messagesHtml.append("""<div class="name">Replied to $repliedToName</div>""")
                        if (replyTextSnippet.isNotBlank()) {
                            messagesHtml.append("""<div class="text">$replyTextSnippet</div>""")
                        }
                        messagesHtml.append("""</div>""")
                    }

                    val textContent = htmlEncode(message.content.text)
                    if (textContent.isNotBlank()) {
                        messagesHtml.append("""<div class="text">$textContent</div>""")
                    }

                    if (message.content.msgContent is MsgContent.MCLink) {
                        val linkContent = message.content.msgContent as MsgContent.MCLink
                        linkContent.preview?.let { preview ->
                            val siteName = htmlEncode(preview.uri?.host ?: "Website")
                            val title = htmlEncode(preview.title ?: "No title")
                            val description = htmlEncode(preview.description ?: "")
                            messagesHtml.append("""<a href="${htmlEncode(preview.uri.toString())}" target="_blank" class="web_page_preview">""")
                            preview.image?.let { imgSrc ->
                                if (imgSrc.startsWith("data:image")) {
                                    messagesHtml.append("""<img src="$imgSrc">""")
                                } else if (imgSrc.isNotBlank()){
                                    messagesHtml.append("""<div class="preview_content"><small><i>Image link: ${htmlEncode(imgSrc)}</i></small></div>""")
                                }
                            }
                            messagesHtml.append("""<div class="preview_content">""")
                            messagesHtml.append("""<div class="preview_title">$title</div>""")
                            if (description.isNotBlank()) {
                                messagesHtml.append("""<div class="preview_description">$description</div>""")
                            }
                            messagesHtml.append("""<div class="preview_site_name">$siteName</div>""")
                            messagesHtml.append("""</div></a>""")
                        }
                    }

                    message.file?.let { chatFile ->
                        mediaFiles.find { it.fileId == chatFile.fileId }?.let { mediaItem ->
                            val mediaPath = "media/${htmlEncode(mediaItem.exportFileName)}"
                            val originalFileNameEnc = htmlEncode(mediaItem.originalFileName)
                            val fileSizeStr = formatFileSize(chatFile.fileSize)
                            val typeStr = when (message.content.msgContent) {
                                is MsgContent.MCImage -> "photo"
                                is MsgContent.MCVideo -> "video"
                                is MsgContent.MCVoice -> "audio"
                                else -> "file"
                            }
                            messagesHtml.append("""<div class="media_attachment">""")
                            when (typeStr) {
                                "photo" -> messagesHtml.append("""<img src="$mediaPath" alt="$originalFileNameEnc" title="$originalFileNameEnc">""")
                                "video" -> messagesHtml.append("""<video controls src="$mediaPath" title="$originalFileNameEnc"><a href="$mediaPath">Download $originalFileNameEnc</a></video>""")
                                "audio" -> messagesHtml.append("""<audio controls src="$mediaPath" title="$originalFileNameEnc"><a href="$mediaPath">Download $originalFileNameEnc</a></audio>""")
                                "file" -> {
                                    messagesHtml.append("""<div class="file_attachment">""")
                                    messagesHtml.append("""<span class="icon">&#128190;</span>""")
                                    messagesHtml.append("""<div><div class="title"><a href="$mediaPath" target="_blank">$originalFileNameEnc</a></div>""")
                                    messagesHtml.append("""<div class="size">$fileSizeStr</div>""")
                                    messagesHtml.append("""</div></div>""")
                                }
                            }
                            messagesHtml.append("""</div>""")
                        }
                    }

                    messagesHtml.append("""<div class="time" title="$fullTimestamp">$shortTime</div>""")
                    messagesHtml.append("""</div>""") // bubble
                    messagesHtml.append("""</div>""") // content
                    messagesHtml.append("""</div>""") // message
                }
            }
        }

        val exportRangeStr = if (startDate.isNotBlank() || endDate.isNotBlank()) {
            val start = if (startDate.isNotBlank()) startDate else "Beginning of chat"
            val end = if (endDate.isNotBlank()) endDate else "End of chat"
            " from $start to $end"
        } else { "" }

        val exportTimestamp = Instant.now().toLocalDateTime(TimeZone.currentSystemDefault())
            .format(LocalDateTime.Format {
                year(); char('-'); monthNumber(padded = true); char('-'); dayOfMonth(padded = true);
                char(' '); hour(padded = true); char(':'); minute(padded = true)
            })

        return HTML_TEMPLATE
            .replace("%CHAT_NAME%", htmlEncode(chatName))
            .replace("%EXPORT_RANGE%", htmlEncode(exportRangeStr))
            .replace("%MESSAGES_HTML%", messagesHtml.toString())
            .replace("%EXPORT_TIMESTAMP%", htmlEncode(exportTimestamp))
    }

    private fun htmlEncode(text: String?): String {
        if (text == null) return ""
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "N/A"
        if (bytes < 1024) return "$bytes B"
        val k = bytes.toDouble() / 1024.0
        if (k < 1024) return "%.1f KB".format(k)
        val m = k / 1024.0
        if (m < 1024) return "%.1f MB".format(m)
        val g = m / 1024.0
        return "%.1f GB".format(g)
    }

    private fun getInitials(name: String): String {
        if (name.isBlank()) return "?"
        if (name == "You") return "Y"
        val parts = name.split(" ").filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) {
            if (parts.size >= 2) {
                "${parts[0].first()}${parts[1].first()}".uppercase()
            } else {
                parts[0].take(2).uppercase()
            }
        } else "?"
    }

    suspend fun saveExportedData(
        targetDirectoryPath: String,
        htmlContent: String,
        mediaFiles: List<MediaToExport>,
        chatName: String
    ): Boolean {
        return try {
            val targetDir = File(targetDirectoryPath)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val cleanChatName = chatName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val htmlFile = File(targetDir, "${cleanChatName}_export.html")
            htmlFile.writeText(htmlContent)
            Log.i(TAG, "HTML report saved to: ${htmlFile.absolutePath}")

            val mediaDir = File(targetDir, "media")
            if (mediaFiles.isNotEmpty() && !mediaDir.exists()) {
                mediaDir.mkdirs()
            }
            Log.d(TAG, "Media directory potentially created at: ${mediaDir.absolutePath}")

            for (mediaItem in mediaFiles) {
                val destinationFile = File(mediaDir, mediaItem.exportFileName) // Define destination once
                var fullMediaSavedSuccessfully = false

                if (mediaItem.originalPath != null && !mediaItem.originalPath.startsWith("needs_download/")) {
                    val sourceFile = File(mediaItem.originalPath)
                    if (sourceFile.exists()) {
                        try {
                            if (mediaItem.cryptoArgs != null) {
                                Log.d(TAG, "Decrypting ${mediaItem.originalFileName} to ${destinationFile.absolutePath ?: destinationFile.path}...")
                                decryptCryptoFile(
                                    fromPath = sourceFile.absolutePath ?: sourceFile.path,
                                    args = mediaItem.cryptoArgs,
                                    toPath = destinationFile.absolutePath ?: destinationFile.path
                                )
                                Log.i(TAG, "Successfully decrypted and saved ${mediaItem.exportFileName}")
                                fullMediaSavedSuccessfully = true
                            } else {
                                sourceFile.copyTo(destinationFile, overwrite = true)
                                Log.i(TAG, "Successfully copied plaintext file ${mediaItem.exportFileName}")
                                fullMediaSavedSuccessfully = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to copy/decrypt full media file ${mediaItem.originalFileName} to ${destinationFile.name}: ${e.message}")
                            // fullMediaSavedSuccessfully remains false
                        }
                    } else {
                        Log.w(TAG, "Full media source file not found: ${mediaItem.originalPath} for ${mediaItem.originalFileName}")
                        // fullMediaSavedSuccessfully remains false
                    }
                } else { // This covers null originalPath or "needs_download/"
                    Log.i(TAG, "Full media for ${mediaItem.originalFileName} marked as 'needs_download' or path is null. Skipping full media save attempt here.")
                    // fullMediaSavedSuccessfully remains false
                }

                // Fallback to local preview if full media wasn't saved
                if (!fullMediaSavedSuccessfully && mediaItem.localPreviewPath != null) {
                    Log.i(TAG, "Full media for ${mediaItem.originalFileName} not saved. Attempting to copy local preview from ${mediaItem.localPreviewPath}.")
                    val previewSourceFile = File(mediaItem.localPreviewPath)
                    if (previewSourceFile.exists()) {
                        try {
                            previewSourceFile.copyTo(destinationFile, overwrite = true)
                            Log.i(TAG, "Successfully copied local preview for ${mediaItem.originalFileName} to ${destinationFile.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to copy local preview for ${mediaItem.originalFileName} from ${mediaItem.localPreviewPath}: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "Local preview file not found at: ${mediaItem.localPreviewPath}")
                    }
                } else if (!fullMediaSavedSuccessfully && mediaItem.localPreviewPath == null && (mediaItem.originalPath != null && !mediaItem.originalPath.startsWith("needs_download/"))) {
                    Log.w(TAG, "Full media for ${mediaItem.originalFileName} failed to save, and no local preview path was available.")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving exported data: ${e.message}", e)
            false
        }
    }
}

// kotlin.math.abs(Int) should be used instead.
