package orilumn.reader.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** S26 骨架冒烟：shared-ui 模块可被 JVM target 编译并可运行 jvmTest。 */
class SharedUiSmokeTest {
    @Test
    fun skeletonIsReady() {
        assertTrue(SharedUi.moduleReady)
    }
}