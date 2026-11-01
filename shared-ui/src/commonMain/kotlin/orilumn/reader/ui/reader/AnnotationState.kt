package orilumn.reader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind

/**
 * 当前章标注的内存态（D-19，用户层）。
 *
 * 数据流：进章读一次库 → 放本状态 → 投影 / 长按命中 / 覆盖层都读这里；
 * 增删改先改内存并递增 [revision]，再异步落库，失败回滚。
 *
 * **只持当前章**：翻到别的章时 [load] 会清空旧章列表，避免把上一章的区间投影到新章。
 * 局部刷新以 [revision] 为触发键；它**不是**版式修订号，也不驱动重新分页。
 */
class AnnotationState(private val host: ReaderHost) {

    var chapter by mutableIntStateOf(-1)
        private set

    /** 当前章标注（按起点、id 升序）。 */
    var annotations by mutableStateOf<List<Annotation>>(emptyList())
        private set

    /** 装饰修订号：列表内容一变就 +1，供 `remember` / 栅格指纹刷新。 */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * 章 → 标注代数：**这一章的标注内容**每变一次 +1。
     *
     * 与 [revision] 的区别：进出章时 [revision] 会连续 bump（清空 + 读回），代数只在内容
     * 真的变了时才变。旁注缓存（`PageNoteMemo`）按它判有效性——否则翻到下一章再翻回来，
     * 上一章刚算好的旁注全部作废，只能重算（真机 2026-11-01：回翻的那一页旁注晚到近一秒）。
     */
    private val generations = mutableMapOf<Int, Int>()
    /** 每章上一次见到的标注内容（判「真的变了没有」）。 */
    private val snapshots = mutableMapOf<Int, List<Annotation>>()

    /** [chapter] 这一章当前的标注代数（没变过就是 0）。 */
    fun generationOf(chapter: Int): Int = generations[chapter] ?: 0

    /** 记一笔：这一章的标注内容若与上次不同，代数 +1（进出章时是同一份，不白作废缓存）。 */
    private fun noteContentChanged() {
        val ch = chapter
        if (ch < 0) return
        if (snapshots[ch] == annotations) return
        snapshots[ch] = annotations
        generations[ch] = (generations[ch] ?: 0) + 1
    }

    /**
     * 进入 [chapter]：同一章直接返回（翻页不重读库），换章先清空再异步读。
     *
     * 读取失败按空表处理并保留 [chapter]，避免每帧重试；需要重试由调用方显式 [reload]。
     */
    suspend fun load(chapter: Int) {
        if (chapter == this.chapter) return
        this.chapter = chapter
        annotations = emptyList()
        revision++
        val loaded = try {
            host.annotationsForChapter(chapter)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
        // 读库期间又换章：丢弃过期结果（LaunchedEffect 取消时这里通常也不会执行）。
        if (this.chapter != chapter) return
        annotations = sort(loaded)
        revision++
        noteContentChanged()
        backfillQuotes(chapter)
    }

    /** 强制重读当前章（数据库恢复/外部导入后）。 */
    suspend fun reload() {
        val ch = chapter
        if (ch < 0) return
        val loaded = try {
            host.annotationsForChapter(ch)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
        if (chapter != ch) return
        annotations = sort(loaded)
        revision++
        noteContentChanged()
    }

    /** 长按命中：包含 [charOffset] 的最后一条（同起点后加的优先），不查库。 */
    fun annotationAt(charOffset: Int): Annotation? =
        annotations.lastOrNull { it.charStart <= charOffset && charOffset < it.charEnd }

    /**
     * 新增：先插入临时 id 的记录（立即出高亮/划线），落库成功后换成真实 id；失败回滚。
     * [annotation] 的 chapter 必须等于 [chapter]，否则返回 null（防止跨章串位）。
     */
    suspend fun add(annotation: Annotation): Annotation? {
        if (annotation.chapter != chapter) return null
        val pending = annotation.copy(id = nextTempId())
        annotations = sort(annotations + pending)
        revision++
        noteContentChanged()
        val saved = try {
            host.addAnnotation(annotation)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        if (saved == null) {
            annotations = annotations.filterNot { it.id == pending.id }
            revision++
            noteContentChanged()
            return null
        }
        // 落库期间可能已经换章（或离开阅读面）：旧章的记录不能混进新章列表。
        if (chapter != pending.chapter) {
            annotations = annotations.filterNot { it.id == pending.id }
            revision++
            noteContentChanged()
            return saved
        }
        annotations = sort(annotations.filterNot { it.id == pending.id } + saved)
        revision++
        noteContentChanged()
        return saved
    }

    /** 更新类型/颜色/批注文本：锚点不变，失败回滚。 */
    suspend fun update(annotation: Annotation): Boolean {
        val old = annotations.firstOrNull { it.id == annotation.id } ?: return false
        annotations = sort(annotations.map { if (it.id == annotation.id) annotation else it })
        revision++
        noteContentChanged()
        val ok = try {
            host.updateAnnotation(annotation)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            annotations = sort(annotations.map { if (it.id == old.id) old else it })
            revision++
            noteContentChanged()
        }
        return ok
    }

    /** 删除：先移除再落库，失败回滚。 */
    suspend fun delete(id: Long): Boolean {
        val old = annotations.firstOrNull { it.id == id } ?: return false
        annotations = annotations.filterNot { it.id == id }
        revision++
        noteContentChanged()
        val ok = try {
            host.deleteAnnotation(id)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            annotations = sort(annotations + old)
            revision++
            noteContentChanged()
        }
        return ok
    }

    /** 离开阅读面/换书：清空内存，避免旧章标注残留。 */
    fun clear() {
        chapter = -1
        annotations = emptyList()
        revision++
    }

    /**
     * 老记录（v1 已有标注）没有原文摘录：开章时章节文本已在缓存里，顺手回填一次（D-24）。
     * 逐条 UPDATE，失败静默保留空值——清单显示「（无摘录）」兜底，不阻塞跳转与删除。
     */
    private suspend fun backfillQuotes(chapter: Int) {
        val missing = annotations.filter { it.quote.isNullOrBlank() }
        if (missing.isEmpty()) return
        for (old in missing) {
            val quote = host.textOf(chapter, old.charStart, old.charEnd)?.take(200)
            if (quote.isNullOrBlank()) continue
            val filled = old.copy(quote = quote)
            val ok = try {
                host.updateAnnotation(filled)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (!ok) continue
            // 回填期间可能已经换章：只改仍在当前章内存里的那条。
            if (this.chapter != chapter) return
            annotations = sort(annotations.map { if (it.id == filled.id) filled else it })
            revision++
            noteContentChanged()
        }
    }

    private fun sort(list: List<Annotation>): List<Annotation> =
        list.sortedWith(compareBy<Annotation>({ it.charStart }, { it.id }))

    private fun nextTempId(): Long = tempId--

    /** 仅内存期使用的负数 id；数据库 id 为正，不会碰撞。 */
    private var tempId = -1L
}
