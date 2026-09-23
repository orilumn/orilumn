package orilumn.reader.ui.reader

/** F4a: 拼音序（与旧 `FontManagerPanel` 同口径；空名沉底兜底）。 */
actual fun familyNameComparator(): Comparator<String> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return Comparator { a, b ->
        if (a.isEmpty()) return@Comparator if (b.isEmpty()) 0 else 1
        if (b.isEmpty()) return@Comparator -1
        val c = collator.compare(a, b)
        if (c != 0) c else a.compareTo(b)
    }
}
