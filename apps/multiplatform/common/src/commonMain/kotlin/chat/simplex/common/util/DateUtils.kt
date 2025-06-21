package chat.simplex.common.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone // Not strictly needed for LocalDate.parse but good for general date/time context
import kotlinx.datetime.toInstant // Not strictly needed for LocalDate.parse
import kotlinx.datetime.toLocalDateTime // Not strictly needed for LocalDate.parse

/**
 * Parses a date string in "YYYY-MM-DD" format to a kotlinx.datetime.LocalDate.
 *
 * @param dateString The date string to parse.
 * @return A LocalDate object if parsing is successful and the string is not blank,
 *         null otherwise (e.g., for blank input or parsing errors).
 */
fun parseDateString(dateString: String): LocalDate? {
    if (dateString.isBlank()) {
        return null // Treat blank as no date specified
    }
    return try {
        // kotlinx.datetime.LocalDate.parse() can handle ISO 8601 YYYY-MM-DD format by default.
        LocalDate.parse(dateString)
    } catch (e: Exception) {
        // Optionally, log the exception e.g., Log.e("DateUtils", "Failed to parse date string: $dateString", e)
        // For now, returning null for any parsing failure as per requirements.
        println("Error parsing date string '$dateString': ${e.message}") // Simple console log for now
        null
    }
}
