package chat.simplex.common.util

import chat.simplex.common.platform.File // Your expect class File

/**
 * Parses a data URI (expected to be an image, e.g., "data:image/png;base64,..."),
 * decodes the Base64 data, and saves it to a temporary file.
 *
 * @param dataUri The data URI string.
 * @param tempDir The directory where the temporary file should be created.
 * @param baseNameForTempFile A base name to use for creating the temporary file (e.g., "export_preview_123").
 * @return The [File] object pointing to the created temporary file, or null if parsing/saving fails
 *         or if the dataUri is not a supported image data URI.
 */
expect fun saveDataUriToTempFile(
    dataUri: String,
    tempDir: File, // chat.simplex.common.platform.File
    baseNameForTempFile: String
): File? // chat.simplex.common.platform.File
