package chat.simplex.common.util

import kotlinx.datetime.LocalDate // Ensure this import is present

/**
 * Parses a date string in "YYYY-MM-DD" format to a LocalDate.
 * Returns null if the string is blank or if parsing fails.
 */
fun parseDateString(dateString: String): LocalDate? {
    if (dateString.isBlank()) {
        return null // Treat blank as no date specified
    }
    return try {
        // kotlinx.datetime.LocalDate can parse ISO 8601 YYYY-MM-DD by default
        LocalDate.parse(dateString)
    } catch (e: Exception) {
        // Consider logging the exception if a proper logger is available
        // For now, returning null indicates failure
        println("Error parsing date string '$dateString': ${e.message}")
        null
    }
}
