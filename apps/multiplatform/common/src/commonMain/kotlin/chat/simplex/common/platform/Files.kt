package chat.simplex.common.platform

import androidx.compose.runtime.Composable
import chat.simplex.common.model.*
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.res.MR
import com.charleskorn.kaml.*
import kotlinx.serialization.encodeToString
// import java.io.* // Replaced by expect declarations
// import java.nio.file.Files // Commented out for now
// import java.nio.file.StandardCopyOption // Commented out for now
import java.net.URI // Assuming this is fine for now, or needs expect/actual later
import java.net.URLDecoder // Assuming this is fine for now
import java.net.URLEncoder // Assuming this is fine for now

// Imports from the new FileUtils.kt (or this file if expect File is here)
// Assuming FileUtils.kt is in the same package: chat.simplex.common.platform
// No explicit import needed for classes in the same package if FileUtils.kt provides them.
// If FileUtils.kt is created in a different package, imports would be needed.
// For now, expect declarations for File, etc., will be moved into THIS file.

expect class File(pathname: String) {
    fun getAbsolutePath(): String
    fun getName(): String
    fun exists(): Boolean
    fun mkdirs(): Boolean
    fun getParentFile(): File?
    fun length(): Long
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun listFiles(): List<File>?
    fun delete(): Boolean
    fun writeBytes(bytes: ByteArray)
    fun readBytes(): ByteArray
    fun outputStream(): OutputStream // Added
    fun inputStream(): InputStream  // Added
}

expect fun createFile(pathname: String): File
expect val fileSeparator: String

// Expect declarations for Stream types, moved here or from FileUtils.kt
expect abstract class InputStream {
    abstract fun read(b: ByteArray, off: Int, len: Int): Int
    fun readBytes(): ByteArray
    fun close()
    fun copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long
}
expect abstract class OutputStream {
    abstract fun write(b: ByteArray, off: Int, len: Int)
    fun write(b: ByteArray)
    fun close()
}
expect class ByteArrayInputStream(buf: ByteArray) : InputStream {
    fun read(): Int
}
expect class BufferedOutputStream(stream: OutputStream) : OutputStream

expect val defaultTmpPrefix: String
expect fun createTmpFile(dir: File, prefix: String = defaultTmpPrefix, suffix: String? = null): File

// Helper for a common pattern found in Files.kt
inline fun createTmpFileAndDelete(tmpDirFile: File, prefix: String = defaultTmpPrefix, suffix: String? = null, action: (tmpFile: File) -> Unit) {
    val tmpFile = createTmpFile(tmpDirFile, prefix, suffix)
    try {
        action(tmpFile)
    } finally {
        tmpFile.delete()
    }
}

internal const val DEFAULT_BUFFER_SIZE_INTERNAL = 8 * 1024 // Renamed to avoid conflict if expect val is named same
internal const val EOF_INTERNAL = -1 // Renamed
internal const val TAG_FILES = "Files" // Renamed

// If MR (message resources) are used by logic moved from HtmlExporter
expect object MR {
    object strings {
        val file_saved: StringResource
        val error_saving_file: StringResource
    }
}
expect class StringResource // Dummy if MR is not actually used

// If Log is used
expect object Log {
    fun e(tag: String, message: String)
    fun d(tag: String, message: String)
}

// If showToast is used
expect fun showToast(message: String)

// If generalGetString is used
expect fun generalGetString(resource: StringResource): String


expect val dataDir: File
expect val tmpDir: File
expect val filesDir: File
expect val appFilesDir: File
expect val wallpapersDir: File
expect val coreTmpDir: File
expect val dbAbsolutePrefixPath: String
expect val preferencesDir: File
expect val preferencesTmpDir: File

expect val chatDatabaseFileName: String
expect val agentDatabaseFileName: String

/**
* This is used only for temporary storing db archive for export.
* Providing [tmpDir] instead crashes the app on Android (only). Check db export before moving from this path to something else
* */
expect val databaseExportDir: File

expect val remoteHostsDir: File

expect fun desktopOpenDatabaseDir()

expect fun desktopOpenDir(dir: File)

fun createURIFromPath(absolutePath: String): URI = URI.create(URLEncoder.encode(absolutePath, "UTF-8"))

fun URI.toFile(): File = createFile(URLDecoder.decode(rawPath, "UTF-8").removePrefix("file:"))

fun copyFileToFile(from: File, to: URI, finally: () -> Unit) {
  try {
    to.outputStream().use { stream ->
      // Assuming BufferedOutputStream can take the expect OutputStream
      val bos = BufferedOutputStream(stream) // Removed .use for manual close in finally
      try {
        from.inputStream().use { it.copyTo(bos, DEFAULT_BUFFER_SIZE_INTERNAL) }
      } finally {
        bos.close() // Ensure BufferedOutputStream is closed
      }
    }
    showToast(generalGetString(MR.strings.file_saved))
  } catch (e: Throwable) {
    showToast(generalGetString(MR.strings.error_saving_file))
    Log.e(TAG_FILES, "copyFileToFile error saving file $e")
  } finally {
    finally()
  }
}

fun copyBytesToFile(bytes: ByteArrayInputStream, to: URI, finally: () -> Unit) {
  try {
    to.outputStream().use { stream ->
      val bos = BufferedOutputStream(stream) // Removed .use for manual close in finally
      try {
        bytes.use { it.copyTo(bos, DEFAULT_BUFFER_SIZE_INTERNAL) }
      } finally {
        bos.close() // Ensure BufferedOutputStream is closed
      }
    }
    showToast(generalGetString(MR.strings.file_saved))
  } catch (e: Throwable) {
    showToast(generalGetString(MR.strings.error_saving_file))
    Log.e(TAG_FILES, "copyBytesToFile error saving file $e")
  } finally {
    finally()
  }
}

fun getMigrationTempFilesDirectory(): File = createFile(dataDir.getAbsolutePath() + fileSeparator + "migration_temp_files")

fun getAppFilePath(fileName: String): String {
  val rh = chatModel.currentRemoteHost.value
  val s = fileSeparator
  return if (rh == null) {
    appFilesDir.getAbsolutePath() + s + fileName
  } else {
    remoteHostsDir.getAbsolutePath() + s + rh.storePath + s + "simplex_v1_files" + s + fileName
  }
}

fun getWallpaperFilePath(fileName: String): String {
  val rh = chatModel.currentRemoteHost.value
  val s = fileSeparator
  val path = if (rh == null) {
    wallpapersDir.getAbsolutePath() + s + fileName
  } else {
    remoteHostsDir.getAbsolutePath() + s + rh.storePath + s + "simplex_v1_assets" + s + "wallpapers" + s + fileName
  }
  createFile(path).getParentFile()?.mkdirs() // Use getParentFile()
  return path
}

fun getPreferenceFilePath(fileName: String = "themes.yaml"): String = preferencesDir.getAbsolutePath() + fileSeparator + fileName

fun getLoadedFilePath(file: CIFile?): String? {
  val f = file?.fileSource?.filePath
  return if (f != null && file.loaded) {
    val filePath = getAppFilePath(f)
    if (fileReady(file, filePath)) filePath else null
  } else {
    null
  }
}

fun getLoadedFileSource(file: CIFile?): CryptoFile? {
  val f = file?.fileSource?.filePath
  return if (f != null && file.loaded) {
    val filePath = getAppFilePath(f)
    if (fileReady(file, filePath)) file.fileSource else null
  } else {
    null
  }
}

fun readThemeOverrides(): List<ThemeOverrides> {
  return try {
    val file = createFile(getPreferenceFilePath("themes.yaml"))
    if (!file.exists()) return emptyList()

    file.inputStream().use { stream -> // Assuming expect File has inputStream()
      val map = yaml.parseToYamlNode(stream).yamlMap // Pass stream directly
      val list = map.get<YamlList>("themes")
      val res = ArrayList<ThemeOverrides>()
      list?.items?.forEach {
        try {
          res.add(yaml.decodeFromYamlNode(ThemeOverrides.serializer(), it))
        } catch (e: Throwable) {
          Log.e(TAG, "Error while reading specific theme: ${e.stackTraceToString()}")
        }
      }
      res.skipDuplicates()
    }
  } catch (e: Throwable) {
    Log.e(TAG_FILES, "Error while reading themes file: ${e.stackTraceToString()}")
    emptyList()
  }
}

private const val themesWriterLock = "themesWriter" // Renamed lock

fun writeThemeOverrides(overrides: List<ThemeOverrides>): Boolean =
  synchronized(themesWriterLock) {
    try {
      val themesFile = createFile(getPreferenceFilePath("themes.yaml"))
      createTmpFileAndDelete(preferencesTmpDir) { tmpFile ->
        val string = yaml.encodeToString(ThemesFile(themes = overrides))
        // tmpFile.bufferedWriter().use { it.write(string) } // .bufferedWriter() is a JVM extension
        // Use basic outputStream and writeBytes instead for common code
        tmpFile.outputStream().use { it.write(string.encodeToByteArray()) } // Simplified
        themesFile.getParentFile()?.mkdirs()
        // Files.move(tmpFile.toPath(), themesFile.toPath(), StandardCopyOption.REPLACE_EXISTING) // Commented out java.nio
        // Manual move: read from tmpFile, write to themesFile, then delete tmpFile.
        // This is a simplified stand-in. A proper multiplatform move is complex.
        themesFile.delete() // Attempt to delete target first
        val content = tmpFile.readBytes()
        themesFile.writeBytes(content)
        // tmpFile.delete() is handled by createTmpFileAndDelete
      }
      true
    } catch (e: Exception) {
      Log.e(TAG_FILES, "Error writing themes file: ${e.stackTraceToString()}")
      false
    }
  }

private fun fileReady(file: CIFile, filePath: String) =
  createFile(filePath).exists() &&
  CIFile.cachedRemoteFileRequests[file.fileSource] != false // This might need adjustment based on how CIFile and its cache works
  && createFile(filePath).length() >= file.fileSize

/**
* [rememberedValue] is used in `remember(rememberedValue)`. So when the value changes, file saver will update a callback function
* */
@Composable
expect fun rememberFileChooserLauncher(getContent: Boolean, rememberedValue: Any? = null, onResult: (URI?) -> Unit): FileChooserLauncher

@Composable
expect fun rememberFileChooserMultipleLauncher(onResult: (List<URI>) -> Unit): FileChooserMultipleLauncher

expect class FileChooserLauncher() {
  suspend fun launch(input: String)
}

expect class FileChooserMultipleLauncher() {
  suspend fun launch(input: String)
}

expect fun URI.inputStream(): InputStream?
expect fun URI.outputStream(): OutputStream
