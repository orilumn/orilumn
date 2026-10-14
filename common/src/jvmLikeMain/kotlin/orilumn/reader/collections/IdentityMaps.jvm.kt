package orilumn.reader.collections

actual fun <K, V> identityMap(): MutableMap<K, V> = java.util.IdentityHashMap()
