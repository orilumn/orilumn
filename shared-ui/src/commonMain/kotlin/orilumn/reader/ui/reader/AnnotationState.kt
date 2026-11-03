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
 * **当前章只有一份**：翻到别的章时 [load] 会清空旧章列表，避免把上一章的区间投影到新章。
 * 另外持一小份**相邻章预读**（[prefetched]，只读快照）：翻过去那一刻目标页的装饰与旁注
 * 首帧就有，不必等一次库往返（见 [prefetch]）。
 *
 * 局部刷新以 [revision] 为触发键；它**不是**版式修订号，也不驱动重新分页。
 */
class AnnotationState(private val host: ReaderHost) {

    var chapter by mutableIntStateOf(-1)
        private set

    /** 当前章标注（按起点、id 升序）。 */
    var annotations by mutableStateOf<List<Annotation>>(emptyList())
        private set

    /**
     * 当前章的标注**是否已经从库里读回来了**。
     *
     * `chapter` 在开读之前就先设好了，那段时间 [annotations] 是空表——空表既可能是「这章真的
     * 没有标注」，也可能是「还没读回来」，**读的时候分不出来**。旁注布点按空表算出来就是
     * 「一页没有旁注」，一旦被当成结果存进缓存，标注读回来以后也没人知道该作废它
     * （真机 2026-11-01：翻章回来整页批注全都不见了）。所以布点方必须先看这个标志。
     */
    var loaded by mutableStateOf(false)
        private set

    /** 装饰修订号：列表内容一变就 +1，供 `remember` / 栅格指纹刷新。 */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * **相邻章预读**（章 → 标注，最近 [PREFETCH_CAPACITY] 章）。
     *
     * 为什么要有这一份：进一个从没打开过的章时，`load` 得先读一次库才敢布点，旁注于是比页面
     * 晚到一小截（几百毫秒的布点 + 一次库往返）。而读者会翻到哪一章基本只有前后两章可选，
     * 所以这两章的标注可以**在还读着当前章的时候就备好**：翻过去时装饰 / 旁注首帧即有。
     *
     * 它**不是**「第二份当前章列表」：只按章回答查询（[annotationsFor]），谁也不会拿它冒充
     * 当前章——当前章仍以 [annotations] 为唯一来源（增删改只改那一份）。
     */
    var prefetched by mutableStateOf<Map<Int, List<Annotation>>>(emptyMap())
        private set

    /**
     * 标注数据版本：当前章内容（[revision]）或预读快照（[prefetched]）一变就换一个新值。
     * 给 `LaunchedEffect` 当键用——预热 / 投影要能感知「刚预读回来的那一章」。
     */
    val dataVersion: Any get() = prefetched to revision

    /** 预读在途的章：同一章不重复读（快速翻章时 effect 会连着起两次）。 */
    private val prefetching = mutableSetOf<Int>()

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
     * 预读回来的内容记成 [chapter] 的内容基线：进章时摆的是同一份 ⇒ [noteContentChanged] 认为
     * 「没变」，代数不动，按当前代数存下的布点结果继续有效。
     *
     * 之前见过这一章、且这次读回的内容不同 ⇒ 代数 +1（旧布点结果作废，翻过去重算）。
     * 从没见过（基线为空）时**不** +1：那时还没有任何结果按别的代数存过。
     */
    private fun adoptBaseline(chapter: Int, list: List<Annotation>) {
        val old = snapshots[chapter]
        if (old == list) return
        snapshots[chapter] = list
        if (old != null) generations[chapter] = (generations[chapter] ?: 0) + 1
    }

    /** 放进预读表并按最近使用淘汰（容量见 [PREFETCH_CAPACITY]）。 */
    private fun putPrefetched(
        current: Map<Int, List<Annotation>>,
        chapter: Int,
        list: List<Annotation>,
    ): Map<Int, List<Annotation>> {
        val next = LinkedHashMap(current)
        next.remove(chapter)
        while (next.size >= PREFETCH_CAPACITY) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        next[chapter] = list
        return next
    }

    /**
     * 进入 [chapter]：同一章直接返回（翻页不重读库），换章先清空再异步读。
     *
     * **预读命中就不等库**：这一章若已由 [prefetch] 备好，直接把它摆上去（`loaded = true`），
     * 首帧就能布点；否则照旧清空、读库、再摆。
     *
     * 读取失败按空表处理并保留 [chapter]，避免每帧重试；需要重试由调用方显式 [reload]。
     */
    suspend fun load(chapter: Int) {
        if (chapter == this.chapter) return
        // 刚离开的那一章顺手留在预读表里：回翻时同样不必再读库（内存里这份就是它的全部内容）。
        val previous = this.chapter
        if (previous >= 0 && loaded) prefetched = putPrefetched(prefetched, previous, annotations)
        this.chapter = chapter
        val warm = prefetched[chapter]
        if (warm != null) {
            prefetched = prefetched - chapter
            annotations = warm
            loaded = true
            revision++
            // 预读时已记过基线，同一份内容不算变 ⇒ 代数不 +1，预热好的旁注继续有效。
            noteContentChanged()
            backfillQuotes(chapter)
            return
        }
        annotations = emptyList()
        loaded = false
        revision++
        val read = try {
            host.annotationsForChapter(chapter)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
        // 读库期间又换章：丢弃过期结果（LaunchedEffect 取消时这里通常也不会执行）。
        if (this.chapter != chapter) return
        annotations = sort(read)
        loaded = true
        revision++
        noteContentChanged()
        backfillQuotes(chapter)
    }

    /**
     * 预读 [chapter] 的标注（相邻章用，见 [prefetched]）。
     *
     * 已在缓存里 / 就是当前章 / 正在读 ⇒ 直接返回。**读失败不缓存**：把「读失败」记成
     * 「这章没有标注」会让进章时连重读都不做，整章装饰与旁注一起消失。
     */
    suspend fun prefetch(chapter: Int) {
        if (chapter < 0 || chapter == this.chapter) return
        if (prefetched.containsKey(chapter) || !prefetching.add(chapter)) return
        val read: List<Annotation>? = try {
            host.annotationsForChapter(chapter)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        } finally {
            prefetching.remove(chapter)
        }
        if (read == null) return
        // 读库期间已经进章：这份结果用不上了，交给 load 自己的那次读。
        if (chapter == this.chapter) return
        val list = sort(read)
        prefetched = putPrefetched(prefetched, chapter, list)
        adoptBaseline(chapter, list)
    }

    /** 预读 [chapter] 的前后两章（先备前向，翻页方向以向前为常态）。 */
    suspend fun prefetchNeighbors(chapter: Int) {
        if (chapter < 0) return
        prefetch(chapter + 1)
        prefetch(chapter - 1)
    }

    /**
     * [chapter] 这一章**可用的**标注；`null` = 还没读回来（**空表 = 真的没有标注**，两者不能混）。
     *
     * 当前章取 [annotations]，其他章取预读快照。调用方（投影 / 布点 / 预热）都按「这一页属于
     * 哪一章」来问，于是跨章翻页的目标页也能在首帧拿到自己那一章的装饰。
     */
    fun annotationsFor(chapter: Int): List<Annotation>? =
        if (chapter == this.chapter && loaded) annotations else prefetched[chapter]

    /** [chapter] 的标注是否已就位（当前章已读回，或已被预读）。 */
    fun isReady(chapter: Int): Boolean = annotationsFor(chapter) != null

    /** 强制重读当前章（数据库恢复/外部导入后）。 */
    suspend fun reload() {
        val ch = chapter
        if (ch < 0) return
        // 全书标注在别处改过（笔记清单里删的可能是别的章的标注）：预读快照一律作废，
        // 免得翻过去时拿旧数据布点。重备由调用方决定（见 ReaderScreen 的 epoch effect）。
        if (prefetched.isNotEmpty()) prefetched = emptyMap()
        val read = try {
            host.annotationsForChapter(ch)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
        if (chapter != ch) return
        annotations = sort(read)
        loaded = true
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

    /** 更新范围/类型/颜色/批注文本/摘录，失败回滚。 */
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

    /** 离开阅读面/换书：清空内存，避免旧章标注残留（预读快照同理，换书后不再成立）。 */
    fun clear() {
        chapter = -1
        annotations = emptyList()
        loaded = false
        prefetched = emptyMap()
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

    private companion object {
        /** 预读缓存最多留几章：前后各一章 + 翻过之后回退的那一章，够用且只占几 KB。 */
        const val PREFETCH_CAPACITY = 4
    }
}
