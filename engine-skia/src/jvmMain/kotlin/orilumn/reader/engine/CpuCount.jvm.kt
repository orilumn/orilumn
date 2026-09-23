package orilumn.reader.engine

/** R6: 平台 CPU 核数 actual（JVM/桌面）。 */
internal actual fun platformCpuCount(): Int = Runtime.getRuntime().availableProcessors()
