package chat.simplex.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import chat.simplex.common.platform.File
import chat.simplex.common.platform.tmpDir
import java.io.FileOutputStream
import java.util.UUID

actual fun saveDataUriToTempFile(dataUri: String): File? {
    if (!dataUri.startsWith("data:image/")) {
        return null // Not a supported image data URI
    }

    val parts = dataUri.split(",")
    if (parts.size != 2) {
        return null // Invalid format
    }

    // parts[0] is something like "data:image/png;base64"
    // parts[1] is the actual base64 encoded data
    val imageTypePart = parts[0].substringAfter("data:image/").substringBefore(";base64")
    val extension = when (imageTypePart.lowercase()) {
        "jpeg", "jpg" -> "jpg"
        "png" -> "png"
        "webp" -> "webp"
        "gif" -> "gif"
        else -> "tmp" // Fallback extension
    }

    val base64ImageData = parts[1]

    return try {
        val decodedBytes = Base64.decode(base64ImageData, Base64.DEFAULT)
        val tempFile = File(tmpDir, "export_preview_${UUID.randomUUID()}.$extension")
        FileOutputStream(tempFile.absolutePath).use { fos ->
            fos.write(decodedBytes)
        }
        tempFile
    } catch (e: Exception) {
        // Log error (actual logging mechanism would be used here)
        println("Error decoding Base64 image or writing to temp file: ${e.message}")
        null
    }
}
