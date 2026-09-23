package orilumn.reader.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the temp-session "birth window" fix (报告 R1 / 改进方案 P1).
 *
 * The race: [BookDocumentController.startAnchorStream] used to bind the empty session
 * (`unit.inProgress`) *before* synchronously shaping the anchor page (350–550ms), so a flip in that
 * window could on-demand-shape into what would become `forwardPages[0]` → duplicate anchor block /
 * bounce-back. The fix: keep the session unbound while shaping, hang a `tempBirth` signal, commit the
 * whole born window atomically under `tempStateLock`, and have [BookDocumentController.findAdjacentPage]
 * await the signal before navigating.
 *
 * The engine-level concurrency (two threads racing a birth) is exercised on device/emulator; these
 * JVM tests pin down the ChapterUnit-level contract the fix relies on:
 *  - a fresh unit has no birth marker;
 *  - while a stream is unborn the marker is not complete (a flip awaiting it stays suspended);
 *  - the birth commit releases waiters (a waiter on a completed marker returns immediately);
 *  - `invalidateLayout()` (the abandon path) unblocks waiters and clears the marker so a flip can
 *    never hang on an abandoned session.
 */
class TempBirthWaitRegressionTest {

    @Test
    fun `全新章节无出生标记`() {
        val unit = ChapterUnit(0)
        assertNull(unit.tempBirth)
        assertNull(unit.inProgress)
    }

    @Test
    fun `出生中标记未完成_翻页等待者保持挂起`() {
        val unit = ChapterUnit(0)
        val birth = CompletableDeferred<Unit>()
        unit.tempBirth = birth
        // The flip's `birth.await()` must NOT return while the stream is still being born — a half
        // initialized window would otherwise be navigated/shaped into (the R1 race).
        assertFalse("birth marker must not be completed before the commit", birth.isCompleted)
        runBlocking {
            val released = withTimeoutOrNull(100) { birth.await(); true } ?: false
            assertFalse("waiter must remain suspended while the stream is unborn", released)
        }
    }

    @Test
    fun `出生提交后等待者立即释放`() {
        val unit = ChapterUnit(0)
        val birth = CompletableDeferred<Unit>()
        unit.tempBirth = birth
        // Birth commit (engine): bind + fill window atomically under tempStateLock, then complete the
        // marker. A waiter blocked on it is released and re-reads the (now born) session state.
        unit.tempBirth = null
        birth.complete(Unit)
        assertTrue(birth.isCompleted)
        runBlocking {
            val released = withTimeoutOrNull(2000) { birth.await(); true } ?: false
            assertTrue("waiter must be released at the birth commit", released)
        }
    }

    @Test
    fun `invalidateLayout 释放出生等待者并清除标记`() {
        val unit = ChapterUnit(0)
        val birth = CompletableDeferred<Unit>()
        unit.tempBirth = birth
        // Abandon path (relayout invalidation while a stream is still being born): waiters must be
        // unblocked (they re-read state and fall back) — never hang.
        unit.invalidateLayout()
        assertNull(unit.tempBirth)
        assertTrue("invalidateLayout must complete the birth marker", birth.isCompleted)
        runBlocking {
            val released = withTimeoutOrNull(2000) { birth.await(); true } ?: false
            assertTrue("invalidateLayout must release birth waiters", released)
        }
    }
}