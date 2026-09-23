package orilumn.reader.engine

/**
 * C2-P2b-3: 引擎诊断日志总闸（was `:app EngineLog.enabled = BuildConfig.DEBUG`，
 * `BuildConfig` 不可搬；语义不变 —— 宿主在 debug 构建打开，release 全程静默）。
 */
object EngineDiag {
    var enabled: Boolean = false
}
