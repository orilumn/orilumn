package orilumn.reader.ui.reader

/**
 * F4a: 字体族名排序（中文拼音序）。JVM/Android 同为 `java.text.Collator`，
 * 两行 actual 保两端列表顺序一致（展示层统一列表的排序单源）。
 */
expect fun familyNameComparator(): Comparator<String>
