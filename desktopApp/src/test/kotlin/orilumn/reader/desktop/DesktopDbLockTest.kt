package orilumn.reader.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 桌面库并发写回归（「导入图书报数据库被锁定」）。
 *
 * SQLDelight 2.4 的 `JdbcSqliteDriver` 对文件库用 ThreadedConnectionManager：**每条线程一条
 * 连接**。字体扫描批量写 font_faces、导入回填封面写 books，各在自己连接上写同一个文件，
 * 撞锁时只能靠驱动自身的 busy_timeout 排队；xerial 原生默认只有 3s，等不到就抛
 * SQLITE_BUSY（上层看到的就是「数据库被锁定」）。
 *
 * 复现：另开一条连接 `BEGIN IMMEDIATE` 攥住写锁 **4.2s**（超过 xerial 默认的 3s，
 * 但短于本仓配的 5s），同时发起书架写入。不配 busy_timeout 时写入在 3s 处抛 SQLITE_BUSY
 * （A/B 已验证）；配了就会一路等到锁释放再写成功。
 *
 * 持锁者**自己定时释放**，不等写入方——否则两边互等，测试就变成验证 5s 超时了。
 */
class DesktopDbLockTest {

    private fun tmpRoot(): File = Files.createTempDirectory("orilumn-db-lock-test").toFile()

    @Test
    fun writerWaitsOutALongHeldLockInsteadOfThrowingBusy() = runBlocking {
        val root = tmpRoot()
        val store = DesktopShelfStore(root)
        store.load()
        val entry = store.addBook("lock-probe", null, File(root, "x.epub").absolutePath, null)

        val dbFile = File(root, "orilumn.db")
        val locked = CountDownLatch(1)
        val holderError = AtomicReference<Throwable?>(null)
        val holder = thread(name = "db-write-holder") {
            try {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
                    // 注意：不能用 autoCommit=false 再发 BEGIN —— xerial 会先自己开一个
                    // 隐式事务，显式 BEGIN IMMEDIATE 撞成 "cannot start a transaction within
                    // a transaction"，持锁者当场死掉，测试就退化成「没人占锁」的假绿。
                    c.createStatement().use { st ->
                        st.execute("BEGIN IMMEDIATE")
                        locked.countDown()
                        Thread.sleep(HOLD_MILLIS)
                        st.execute("ROLLBACK")
                    }
                }
            } catch (t: Throwable) {
                holderError.set(t)
                locked.countDown()
            }
        }
        check(locked.await(10, TimeUnit.SECONDS)) { "写锁持有者没拿到锁，测试前提不成立" }
        assertNull("持锁连接自身出错", holderError.get())

        // 此刻写锁被占着：这一句必须排队等，而不是立刻抛 SQLITE_BUSY。
        store.updateCover(entry.id, File(root, "covers/c.png").absolutePath)

        holder.join(10_000)
        assertNull("持锁连接自身出错", holderError.get())
        assertEquals(
            File(root, "covers/c.png").absolutePath,
            store.getEntry(entry.id)?.coverPath,
        )
    }

    private companion object {
        /** > xerial 原生默认 3000ms（不配 busy_timeout 必败），< 本仓配置的 5000ms。 */
        const val HOLD_MILLIS = 4_200L
    }
}
