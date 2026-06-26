package top.mc506lw.monolith.feature.preview

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object StructurePreviewManager {

    private val previewSessions = ConcurrentHashMap<UUID, PreviewSession>()
    private val sessionCreatedAt = ConcurrentHashMap<UUID, Long>()
    private val updateTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val log = MonolithLogger.getLogger("Preview")
    
    val TIMEOUT_TICKS = 200L
    
    fun startPreview(
        player: Player,
        blueprint: Blueprint,
        controllerLocation: Location,
        facing: Facing
    ): PreviewSession? {
        cancelPreview(player)

        val assembledShape = blueprint.assembledShape
        if (assembledShape.blocks.isEmpty()) {
            player.sendMessage(I18n.Message.Preview.errEmptyStage(blueprint.id))
            log.warn("preview", "蓝图assembledShape为空", "blueprintId" to blueprint.id, "blockCount" to blueprint.blockCount)
            return null
        }

        val session = PreviewSession(
            sessionId = "${player.uniqueId}-${System.currentTimeMillis()}",
            playerId = player.uniqueId,
            blueprintId = blueprint.id,
            blueprint = blueprint,
            controllerLocation = controllerLocation,
            facing = facing,
            renderRadius = 64
        )

        log.info("player=${player.name}", "启动预览", "blueprintId" to blueprint.id, "scaffoldBlocks" to blueprint.scaffoldShape.blocks.size, "assembledBlocks" to blueprint.assembledShape.blocks.size, "ghosts" to session.ghostBlockCount)

        previewSessions[player.uniqueId] = session
        sessionCreatedAt[player.uniqueId] = System.currentTimeMillis()
        session.showAllLayers = true
        session.start()

        startUpdateLoop(player, session)

        return session
    }

    private fun startUpdateLoop(player: Player, session: PreviewSession) {
        log.debug("player=${player.name}", "启动update循环", "session" to session.sessionId, "isActive" to session.isActive)

        val task = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
            if (!session.isActive) {
                log.debug("player=${player.name}", "update循环退出：session不再active")
                updateTasks.remove(player.uniqueId)?.cancel()
                return@Runnable
            }

            checkTimeouts()

            val p = Bukkit.getPlayer(player.uniqueId) ?: run {
                log.debug("player=${player.name}", "update循环退出：玩家离线")
                cancelPreview(player)
                return@Runnable
            }

            val result = session.update(p)

            when (result) {
                PreviewSession.UpdateResult.COMPLETED -> {
                    p.sendMessage(I18n.Message.Preview.buildFinished)
                    cancelPreview(p)
                }
                PreviewSession.UpdateResult.CONTROLLER_BROKEN -> {
                    p.sendMessage(I18n.Message.Preview.controllerBrokenCancel)
                    cancelPreview(p)
                }
                PreviewSession.UpdateResult.STOPPED -> {
                    log.debug("player=${player.name}", "update返回STOPPED，取消预览")
                    cancelPreview(p)
                }
                PreviewSession.UpdateResult.CONTINUE -> {
                }
            }
        }, 5L, 5L)
        
        updateTasks[player.uniqueId] = task
    }
    
    fun cancelPreview(player: Player) {
        val task = updateTasks.remove(player.uniqueId)
        task?.cancel()
        
        val session = previewSessions.remove(player.uniqueId)
        sessionCreatedAt.remove(player.uniqueId)
        session?.stop()
    }
    
    fun getPreview(player: Player): PreviewSession? {
        return previewSessions[player.uniqueId]
    }
    
    fun hasActivePreview(player: Player): Boolean {
        return previewSessions.containsKey(player.uniqueId)
    }
    
    fun checkTimeouts(): Int {
        val now = System.currentTimeMillis()
        val timeoutMs = TIMEOUT_TICKS * 50L
        var cancelled = 0
        
        val toCancel = mutableListOf<UUID>()
        
        for ((playerId, createdAt) in sessionCreatedAt) {
            if (now - createdAt > timeoutMs) {
                toCancel.add(playerId)
            }
        }
        
        for (playerId in toCancel) {
            val session = previewSessions.remove(playerId)
            sessionCreatedAt.remove(playerId)
            session?.stop()
            val player = Bukkit.getPlayer(playerId)
            player?.sendMessage(I18n.Message.Preview.timeoutAutoCancel)
            cancelled++
        }
        
        return cancelled
    }
    
    fun cleanup() {
        updateTasks.values.forEach { it.cancel() }
        updateTasks.clear()
        
        previewSessions.values.forEach { it.stop() }
        previewSessions.clear()
        sessionCreatedAt.clear()
    }
}
