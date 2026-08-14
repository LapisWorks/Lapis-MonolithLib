package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import top.mc506lw.monolith.MonolithLib
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工地后台计算服务：把大计算（完成度统计、明细、分片构建）移到后台线程，
 * 结果通过回投到主线程的任务应用，避免阻塞 tick。
 *
 * 规则：
 * - 纯计算（遍历内存数据、predicate 判定）在后台线程执行。
 * - 任何接触 Bukkit/世界对象的结果回投到主线程应用。
 */
object BuildSiteAsync {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Monolith-BuildSite-Async").apply { isDaemon = true }
    }

    private val shutdown = AtomicBoolean(false)

    /** 已失效（被新任务覆盖）的 key 集合。 */
    private val pendingTasks = ConcurrentHashMap.newKeySet<String>()

    /**
     * 在后台线程执行 [compute]，结果经 [onMainThread] 回投主线程。
     * 任务合并：同一 [key] 的新提交会覆盖旧任务（旧任务完成后跳过回投）。
     */
    /**
     * 在后台线程执行 [compute]，结果经 [onMainThread] 回投主线程。
     * 任务合并：同一 [key] 的新提交会覆盖旧任务（旧任务完成后跳过回投）。
     */
    fun <T> enqueue(key: String, compute: () -> T, onMainThread: (T) -> Unit) {
        if (shutdown.get()) return
        pendingTasks.remove(key)
        executor.submit(Runnable {
            if (shutdown.get()) return@Runnable
            val result: T? = try {
                compute()
            } catch (_: Exception) {
                null
            }
            if (result == null) return@Runnable
            if (shutdown.get()) return@Runnable
            // 合并：若提交后被更新的任务覆盖，则跳过本次回投
            if (pendingTasks.contains(key)) return@Runnable
            Bukkit.getScheduler().runTask(MonolithLib.instance, Runnable {
                if (shutdown.get()) return@Runnable
                if (pendingTasks.contains(key)) return@Runnable
                try {
                    onMainThread(result)
                } catch (_: Exception) {
                }
            })
        })
    }

    /** 让后续 [submit] 对该 key 生效（先失效旧任务）。 */
    fun invalidate(key: String) {
        pendingTasks.add(key)
    }

    /** 完成任务后清除失效标记，允许后续再次提交。 */
    fun complete(key: String) {
        pendingTasks.remove(key)
    }

    fun shutdown() {
        shutdown.set(true)
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }
}
