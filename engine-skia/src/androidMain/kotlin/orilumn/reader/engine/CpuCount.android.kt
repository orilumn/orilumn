package orilumn.reader.engine

/** R6: 平台 CPU 核数 actual（Android）。 */
internal actual fun platformCpuCount(): Int = Runtime.getRuntime().availableProcessors()
