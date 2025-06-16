package chat.simplex.common.export

import chat.simplex.common.model.ChatItem
import chat.simplex.common.model.MediaToExport
import chat.simplex.common.model.CIDirection
import chat.simplex.common.model.MsgContent
import chat.simplex.common.platform.format
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.format.char

object HtmlExporter {

    fun generateHtmlReport(
        chatName: String,
        startDate: String,
        endDate: String,
        messages: List<ChatItem>,
        mediaFiles: List<MediaToExport>
    ): String {
        val messagesHtml = StringBuilder()

        for (message in messages) {
            val sender = when (message.chatDir) {
                is CIDirection.GroupRcv -> message.chatDir.groupMember.displayName
                is CIDirection.DirectSnd, is CIDirection.GroupSnd, is CIDirection.LocalSnd -> "You"
                else -> "Unknown Sender"
            }
            // Basic timestamp formatting, consider using a more robust date/time library for complex needs
            val timestamp = मानव(message.meta.itemTs)

            val sanitizedText = htmlEncode(message.content.text)

            var mediaHtml = ""
            message.file?.let { chatFile ->
                mediaFiles.find { it.fileId == chatFile.fileId }?.let { mediaItem ->
                    val mediaPath = "media/${mediaItem.exportFileName}" // Assuming media files will be in a 'media' subdirectory
                    mediaHtml = when (message.content.msgContent) {
                        is MsgContent.MCImage -> """<div class="media-attachment"><img src="$mediaPath" alt="${mediaItem.originalFileName}" style="max-width: 300px; max-height: 300px;"/></div>"""
                        is MsgContent.MCVideo -> """<div class="media-attachment"><video controls src="$mediaPath" style="max-width: 300px; max-height: 300px;"><a href="$mediaPath">${mediaItem.originalFileName}</a></video></div>"""
                        is MsgContent.MCVoice -> """<div class="media-attachment"><audio controls src="$mediaPath"><a href="$mediaPath">${mediaItem.originalFileName}</a></audio></div>"""
                        is MsgContent.MCFile -> """<div class="media-attachment">Attachment: <a href="$mediaPath" target="_blank">${mediaItem.originalFileName}</a></div>"""
                        else -> """<div class="media-attachment">Attachment: <a href="$mediaPath" target="_blank">${mediaItem.originalFileName}</a></div>""" // Fallback for other types or unknown
                    }
                }
            }

            val messageClass = if (sender == "You") "message sent" else "message received"

            messagesHtml.append(
                """
                <div class="$messageClass">
                    <div class="message-sender">$sender</div>
                    <div class="message-content">$sanitizedText</div>
                    $mediaHtml
                    <div class="message-timestamp">$timestamp</div>
                </div>
                """.trimIndent()
            )
        }

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Exported Chat - $chatName</title>
            <style>
                body { font-family: sans-serif; margin: 0; padding: 0; background-color: #f4f4f4; color: #333; }
                .container { max-width: 800px; margin: 20px auto; background-color: #fff; padding: 20px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
                h1 { color: #333; text-align: center; border-bottom: 1px solid #eee; padding-bottom: 10px; }
                .message-container { margin-top: 20px; }
                .message { padding: 10px; margin-bottom: 10px; border-radius: 8px; }
                .message.sent { background-color: #dcf8c6; margin-left: auto; width: fit-content; max-width: 70%; text-align: right; }
                .message.received { background-color: #e9e9eb; margin-right: auto; width: fit-content; max-width: 70%; text-align: left; }
                .message-sender { font-weight: bold; margin-bottom: 4px; font-size: 0.9em; color: #555; }
                .message.sent .message-sender { color: #4CAF50; } /* Darker green for sender of sent messages */
                .message.received .message-sender { color: #007bff; } /* Blue for sender of received messages */
                .message-content { font-size: 1em; }
                .message-timestamp { font-size: 0.75em; color: #777; margin-top: 5px; }
                .media-attachment img, .media-attachment video, .media-attachment audio { display: block; margin-top: 5px; border-radius: 4px; }
                .media-attachment a { color: #007bff; text-decoration: none; }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>Chat Export: $chatName</h1>
                <h2>Export Range: $startDate to $endDate</h2>
                <div id="message-container">
                    ${messagesHtml.toString()}
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun htmlEncode(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    // Basic timestamp formatting helper
    private fun मानव(timestamp: Instant): String {
        val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        return localDateTime.format(kotlinx.datetime.format.DateTimeComponents.Formats.ISO_DATE_TIME)
            .replace("T", " ")
            .substringBeforeLast(".") // Remove millis if present
    }

    suspend fun saveExportedData(
        targetDirectoryPath: String, // Changed from UriString for simplicity in common code
        htmlContent: String,
        mediaFiles: List<MediaToExport>,
        chatName: String
    ): Boolean {
        return try {
            val targetDir = File(targetDirectoryPath)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // Create HTML File
            val htmlFile = File(targetDir, "${chatName}_export.html")
            htmlFile.writeText(htmlContent)
            println("HTML report saved to: ${htmlFile.absolutePath}")

            // Create Media Subdirectory
            val mediaDir = File(targetDir, "media")
            if (!mediaDir.exists()) {
                mediaDir.mkdirs()
            }
            println("Media directory created at: ${mediaDir.absolutePath}")

            // Copy Media Files
            mediaFiles.forEach { mediaItem ->
                if (mediaItem.originalPath != null && !mediaItem.originalPath.startsWith("needs_download/")) {
                    val sourceFile = File(mediaItem.originalPath)
                    if (sourceFile.exists()) {
                        val destinationFile = File(mediaDir, mediaItem.exportFileName)
                        try {
                            sourceFile.copyTo(destinationFile, overwrite = true)
                            println("Copied ${mediaItem.originalFileName} to ${destinationFile.absolutePath}")
                        } catch (e: Exception) {
                            println("Error copying file ${mediaItem.originalFileName}: ${e.message}")
                            // Decide if one error should fail the whole export or just skip the file
                        }
                    } else {
                        println("Source file not found for ${mediaItem.originalFileName} at ${mediaItem.originalPath}")
                    }
                } else {
                    println("Simulating: Media file ${mediaItem.originalFileName} would need to be downloaded to media/${mediaItem.exportFileName}.")
                }
            }
            true
        } catch (e: Exception) {
            println("Error saving exported data: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}

// Expect/actual for File might be needed if commonMain doesn't have java.io.File
// For now, assuming it's available or will be handled by platform-specific parts later.
expect class File(pathname: String) {
    constructor(parent: File?, child: String)
    constructor(parent: String, child: String)

    val name: String
    val path: String
    val absolutePath: String
    val parent: String?
    fun exists(): Boolean
    fun mkdirs(): Boolean
    fun writeText(text: String)
    fun copyTo(target: File, overwrite: Boolean = false): File
    fun isDirectory(): Boolean
    fun listFiles(): Array<File>?
}

expect val filesDir: File // To be provided by platform-specific code (e.g., Android context.filesDir)
expect val File.separator: String
