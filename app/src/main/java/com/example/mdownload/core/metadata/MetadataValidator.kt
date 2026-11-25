package com.example.mdownload.core.metadata

object MetadataValidator {

    const val MAX_EMBEDDED_ARTWORK_BYTES = 4 * 1024 * 1024 // 4 MiB ceiling
    private val YEAR_REGEX = Regex("""^\d{4}(?:-\d{2}(?:-\d{2})?)?$""")

    fun validateSingle(draft: EditableTrackMetadata): MetadataValidationResult {
        val errors = linkedMapOf<String, String>()

        if (draft.title.trim().isEmpty()) {
            errors["title"] = "Title cannot be empty."
        }
        validateYear(draft.year)?.let { errors["year"] = it }
        validateNumberPair(draft.trackNumber, draft.trackTotal, "Track", 9999)?.let { (k, v) ->
            errors[k] = v
        }
        validateNumberPair(draft.discNumber, draft.discTotal, "Disc", 999)?.let { (k, v) ->
            errors[k] = v
        }
        if (draft.artworkChanged && !draft.artworkRemoved) {
            val bytes = draft.artworkBytes
            if (bytes != null && bytes.size > MAX_EMBEDDED_ARTWORK_BYTES) {
                errors["artwork"] = "Embedded artwork exceeds 4 MB limit."
            }
        }
        return MetadataValidationResult(errors)
    }

    fun validateBatch(patch: BatchMetadataPatch): MetadataValidationResult {
        val errors = linkedMapOf<String, String>()
        if (!patch.hasAnyChange) {
            errors["general"] = "Select at least one field to update."
        }
        patch.year?.let { y ->
            validateYear(y)?.let { errors["year"] = it }
        }
        if (patch.replaceArtwork && !patch.removeArtwork) {
            val bytes = patch.artworkBytes
            if (bytes != null && bytes.size > MAX_EMBEDDED_ARTWORK_BYTES) {
                errors["artwork"] = "Embedded artwork exceeds 4 MB limit."
            }
        }
        return MetadataValidationResult(errors)
    }

    private fun validateYear(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!YEAR_REGEX.matches(trimmed)) {
            return "Use YYYY or YYYY-MM-DD format."
        }
        val y = trimmed.take(4).toIntOrNull() ?: return "Invalid year."
        if (y !in 1850..2100) {
            return "Year must be between 1850 and 2100."
        }
        return null
    }

    private fun validateNumberPair(
        numRaw: String,
        totalRaw: String,
        label: String,
        max: Int
    ): Pair<String, String>? {
        val keyPrefix = label.lowercase()
        val numTrim = numRaw.trim()
        val totalTrim = totalRaw.trim()
        val num = if (numTrim.isEmpty()) null else numTrim.toIntOrNull()
        val total = if (totalTrim.isEmpty()) null else totalTrim.toIntOrNull()

        if (numTrim.isNotEmpty() && (num == null || num !in 1..max)) {
            return "${keyPrefix}Number" to "$label number must be 1–$max."
        }
        if (totalTrim.isNotEmpty() && (total == null || total !in 1..max)) {
            return "${keyPrefix}Total" to "$label total must be 1–$max."
        }
        if (num != null && total != null && num > total) {
            return "${keyPrefix}Number" to "$label number cannot exceed total ($total)."
        }
        return null
    }
}
