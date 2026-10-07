package org.tekfive.keep.text

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.IColumnType

/** Locale-independent Unicode case conversion applied after optional whitespace trimming. */
enum class TextCase {
    UNCHANGED,
    LOWER,
    UPPER,
}

/**
 * Normalizes string values sent through this column, preserving its storage type and nullability.
 * Trims surrounding Kotlin whitespace first, then applies invariant Unicode case conversion.
 * Null stays null; trimming whitespace-only strings produces empty strings, never null.
 *
 * Applies to writes and parameters using this column's type (including eq, neq, and inList).
 * Length validation applies to the normalized value, including query parameters. LIKE/ILIKE
 * patterns and SQL expressions use their own types and are not rewritten. Raw SQL and other
 * database writers bypass this application-side transformation.
 *
 * Reads return the stored value unchanged. Existing rows and the supplied Data object's
 * properties are not rewritten. For encrypted text, normalization occurs before encryption;
 * it does not make randomized ciphertext searchable. Declare this before indexes and defaults.
 */
fun <T : String?> Column<T>.normalizeText(
    trim: Boolean = false,
    letterCase: TextCase = TextCase.UNCHANGED,
): Column<T> {
    if (!trim && letterCase == TextCase.UNCHANGED) return this
    @Suppress("UNCHECKED_CAST")
    val source = this as Column<String?>
    val transformer = TextNormalizationTransformer(source.columnType, trim, letterCase)
    // Use Exposed's nullable transform for its delegated SQL literal rendering as well as bound
    // parameters. It copies all column metadata and preserves the underlying nullability flag.
    // The transformation preserves T exactly: null never becomes a string or vice versa.
    @Suppress("UNCHECKED_CAST")
    return table.run { source.transform(transformer) as Column<T> }
}

private class TextNormalizationTransformer(
    private val delegate: IColumnType<String>,
    private val trim: Boolean,
    private val letterCase: TextCase,
) : ColumnTransformer<String?, String?> {
    override fun wrap(value: String?): String? = value

    override fun unwrap(value: String?): String? {
        if (value == null) {
            require(delegate.nullable) { "Cannot store null in a non-nullable normalized text column" }
            return null
        }
        val trimmed = if (trim) value.trim() else value
        val normalized = when (letterCase) {
            TextCase.UNCHANGED -> trimmed
            TextCase.LOWER -> trimmed.lowercase()
            TextCase.UPPER -> trimmed.uppercase()
        }
        delegate.validateValueBeforeUpdate(normalized)
        return normalized
    }
}
