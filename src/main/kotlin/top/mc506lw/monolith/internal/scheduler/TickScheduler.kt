package top.mc506lw.monolith.internal.scheduler

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class TickScheduler(plugin: JavaPlugin) {
    private val asyncScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "Monolith-Scheduler-Thread").apply { isDaemon = true }
    }

    private val tasks = ConcurrentHashMap<String, ScheduledTask>()

    fun runAsync(delayMs: Long, intervalMs: Long, taskId: String, task: () -> Unit) {
        cancelTask(taskId)

        val scheduledFuture = asyncScheduler.scheduleAtFixedRate({
            if (Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTaskAsynchronously(top.mc506lw.monolith.MonolithLib.instance, task)
            } else {
                task()
            }
        }, delayMs, intervalMs, TimeUnit.MILLISECONDS)

        tasks[taskId] = ScheduledTask(taskId, scheduledFuture)
    }

    fun runLaterAsync(delayMs: Long, taskId: String, task: () -> Unit) {
        cancelTask(taskId)

        val scheduledFuture = asyncScheduler.schedule({
            if (Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTaskAsynchronously(top.mc506lw.monolith.MonolithLib.instance, task)
            } else {
                task()
            }

            tasks.remove(taskId)
        }, delayMs, TimeUnit.MILLISECONDS)

        tasks[taskId] = ScheduledTask(taskId, scheduledFuture)
    }

    fun runSync(task: () -> Unit) {
        Bukkit.getScheduler().runTask(top.mc506lw.monolith.MonolithLib.instance, task)
    }

    fun runLaterSync(tickDelay: Long, task: () -> Unit) {
        Bukkit.getScheduler().runTaskLater(top.mc506lw.monolith.MonolithLib.instance, task, tickDelay)
    }

    fun runTimerSync(tickDelay: Long, tickInterval: Long, taskId: String, task: Runnable) {
        cancelTask(taskId)

        val bukkitTask = Bukkit.getScheduler().runTaskTimer(
            top.mc506lw.monolith.MonolithLib.instance,
            task,
            tickDelay,
            tickInterval
        )

        tasks[taskId] = ScheduledTask(taskId, bukkitTask)
    }

    fun cancelTask(taskId: String) {
        tasks.remove(taskId)?.cancel()
    }

    fun cancelAllTasks() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }

    fun shutdown() {
        cancelAllTasks()
        asyncScheduler.shutdown()
    }

    data class ScheduledTask(val id: String, private val future: Any) {
        fun cancel() {
            when (future) {
                is java.util.concurrent.ScheduledFuture<*> -> future.cancel(false)
                is org.bukkit.scheduler.BukkitTask -> future.cancel()
            }
        }
    }
}
