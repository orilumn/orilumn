package orilumn.reader.collections

actual typealias SyncLock = Any

actual fun <T> SyncLock.withLock(block: () -> T): T = synchronized(this, block)
