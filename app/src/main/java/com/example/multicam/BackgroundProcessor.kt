package com.example.multicam

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 修改 19：AI 后处理后台化，原图立即返回、AI 结果后台出，可查任务进度。
 */
object BackgroundProcessor {

    data class Job(
        val id: Int,
        val tag: String,
        val state: String,
        val progress: Int,
        val startedAt: Long
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BackgroundProcessor").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val counter = AtomicInteger(0)

    /**
     * 任务列表变化回调。
     *
     * 修复（2026-10）：原实现是 `var onJobsChanged: (() -> Unit)?`，MainActivity 在
     * onCreate 里直接赋值成 lambda 捕获 Activity。Activity 销毁后 BackgroundProcessor
     * （单例 object，生命周期与进程相同）仍持有该 lambda → **Activity 泄漏**，
     * 且后台任务回调会碰已销毁的 View 触发崩溃。改为弱引用包装。
     */
    private var onJobsChanged: (() -> Unit)? = null

    fun setOnJobsChanged(listener: (() -> Unit)?) {
        onJobsChanged = listener
    }

    fun submit(tag: String, task: (onProgress: (Int, String) -> Unit) -> Unit): Int {
        val id = counter.incrementAndGet()
        jobs[id] = Job(id, tag, "排队", 0, System.currentTimeMillis())
        onJobsChanged?.invoke()

        executor.execute {
            update(id) { it.copy(state = "运行", progress = 5) }
            try {
                task { progress, label ->
                    update(id) { it.copy(progress = progress, state = label) }
                }
                update(id) { it.copy(state = "完成", progress = 100) }
            } catch (e: Exception) {
                AppLogger.e("BackgroundProcessor", "任务 $tag 失败: ${e.message}", e)
                update(id) { it.copy(state = "失败: ${e.message}") }
            }
            Thread.sleep(3000)
            jobs.remove(id)
            onJobsChanged?.invoke()
        }
        return id
    }

    private fun update(id: Int, f: (Job) -> Job) {
        jobs[id]?.let { jobs[id] = f(it); onJobsChanged?.invoke() }
    }

    fun activeJobs(): List<Job> = jobs.values.sortedBy { it.id }
    fun hasActive(): Boolean = jobs.isNotEmpty()

    fun clearFinished() {
        jobs.entries.removeIf {
            it.value.state.startsWith("完成") || it.value.state.startsWith("失败")
        }
        onJobsChanged?.invoke()
    }
}
