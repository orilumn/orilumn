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
            return null
        }
        annotations = sort(annotations.filterNot { it.id == pending.id } + saved)
        revision++
        return saved
    }

    /** 更新类型/颜色/批注文本：锚点不变，失败回滚。 */
    suspend fun update(annotation: Annotation): Boolean {
        val old = annotations.firstOrNull { it.id == annotation.id } ?: return false
        annotations = sort(annotations.map { if (it.id == annotation.id) annotation else it })
        revision++
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
        }
        return ok
    }

    /** 删除：先移除再落库，失败回滚。 */
    suspend fun delete(id: Long): Boolean {
        val old = annotations.firstOrNull { it.id == id } ?: return false
        annotations = annotations.filterNot { it.id == id }
        revision++
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
        }
        return ok
    }

    /** 离开阅读面/换书：清空内存，避免旧章标注残留。 */
    fun clear() {
        chapter = -1
        annotations = emptyList()
        revision++
    }

    private fun sort(list: List<Annotation>): List<Annotation> =
        list.sortedWith(compareBy<Annotation>({ it.charStart }, { it.id }))

    private fun nextTempId(): Long = tempId--

    /** 仅内存期使用的负数 id；数据库 id 为正，不会碰撞。 */
    private var tempId = -1L
}

/** v1 可绘制的标注类型：批注（Note）等 v2 再补；这里先把类型收敛到一个地方。 */
internal fun Annotation.isDrawable(): Boolean =
    kind == AnnotationKind.Highlight || kind == AnnotationKind.Underline
