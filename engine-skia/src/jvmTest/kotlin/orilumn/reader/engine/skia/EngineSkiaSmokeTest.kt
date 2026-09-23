package orilumn.reader.engine.skia

import org.junit.Assert.assertTrue
import org.junit.Test

/** S22 骨架冒烟：engine-skia 模块可被 JVM target 编译并可运行 jvmTest。 */
class EngineSkiaSmokeTest {
    @Test
    fun skeletonIsReady() {
        assertTrue(EngineSkia.moduleReady)
    }
}