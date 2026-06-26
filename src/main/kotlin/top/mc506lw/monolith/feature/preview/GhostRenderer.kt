package top.mc506lw.monolith.feature.preview

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GhostRenderer(private val plugin: MonolithLib) {
    
    private val sessions = ConcurrentHashMap<String, PreviewSession>()
    private val playerSessions = ConcurrentHashMap<UUID, MutableSet<String>>()
    private var updateTask: BukkitTask? = null
    
    private val updatePeriod = 5L
    
    private fun locationToKey(location: Location): String {
        return "${location.world?.name}_${location.blockX}_${location.blockY}_${location.blockZ}"
    }
    
    fun startPreview(
        player: Player,
        controllerLocation: Location,
        blueprint: Blueprint,
        facing: Facing = Facing.NORTH
    ): PreviewSession? {
        val sessionId = locationToKey(controllerLocation)
        
        stopPreviewAtLocation(sessionId)
        
        val transform = CoordinateTransform(facing)
        
        val session = PreviewSession(
            sessionId = sessionId,
            playerId = player.uniqueId,
            blueprintId = blueprint.id,
            blueprint = blueprint,
            controllerLocation = controllerLocation.clone(),
            _transform = transform
        )
        
        sessions[sessionId] = session
        
        playerSessions.getOrPut(player.uniqueId) { mutableSetOf() }.add(sessionId)
        
        session.start()
        
        ensureUpdateTaskRunning()
        
        return session
    }
    
    fun stopPreview(player: Player) {
        val sessionIds = playerSessions.remove(player.uniqueId) ?: return
        sessionIds.forEach { sessionId ->
            sessions[sessionId]?.stop()
            sessions.remove(sessionId)
        }
    }
    
    fun stopPreviewAtLocation(location: Location) {
        val sessionId = locationToKey(location)
        stopPreviewAtLocation(sessionId)
    }
    
    private fun stopPreviewAtLocation(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.stop()
        
        playerSessions[session.playerId]?.remove(sessionId)
    }
    
    fun stopAllPreviews() {
        sessions.values.forEach { it.stop() }
        sessions.clear()
        playerSessions.clear()
        
        updateTask?.cancel()
        updateTask = null
    }
    
    fun getSession(location: Location): PreviewSession? {
        return sessions[locationToKey(location)]
    }
    
    fun getPlayerSessions(player: Player): List<PreviewSession> {
        val sessionIds = playerSessions[player.uniqueId] ?: return emptyList()
        return sessionIds.mapNotNull { sessions[it] }
    }
    
    fun hasActivePreview(player: Player): Boolean {
        val sessionIds = playerSessions[player.uniqueId] ?: return false
        return sessionIds.any { sessions[it]?.isActive == true }
    }
    
    fun setLayer(player: Player, layer: Int): Boolean {
        val sessionIds = playerSessions[player.uniqueId] ?: return false
        var success = false
        sessionIds.forEach { sessionId ->
            sessions[sessionId]?.let { session ->
                if (session.setLayer(layer)) {
                    success = true
                }
            }
        }
        return success
    }
    
    fun nextLayer(player: Player): Boolean {
        val sessionIds = playerSessions[player.uniqueId] ?: return false
        var success = false
        sessionIds.forEach { sessionId ->
            sessions[sessionId]?.let { session ->
                if (session.nextLayer()) {
                    success = true
                }
            }
        }
        return success
    }
    
    fun prevLayer(player: Player): Boolean {
        val sessionIds = playerSessions[player.uniqueId] ?: return false
        var success = false
        sessionIds.forEach { sessionId ->
            sessions[sessionId]?.let { session ->
                if (session.prevLayer()) {
                    success = true
                }
            }
        }
        return success
    }
    
    private fun ensureUpdateTaskRunning() {
        if (updateTask != null && !updateTask!!.isCancelled) {
            return
        }
        
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateAllSessions()
        }, 0L, updatePeriod)
    }
    
    private fun updateAllSessions() {
        StructurePreviewManager.checkTimeouts()
        
        val toRemove = mutableListOf<String>()
        
        sessions.forEach { (sessionId, session) ->
            val player = Bukkit.getPlayer(session.playerId)
            if (player == null || !player.isOnline) {
                toRemove.add(sessionId)
                return@forEach
            }
            
            val result = session.update(player)
            
            when (result) {
                PreviewSession.UpdateResult.COMPLETED -> {
                    player.sendMessage(I18n.Message.Preview.structureCompleted(session.structureId))
                    toRemove.add(sessionId)
                }
                PreviewSession.UpdateResult.CONTROLLER_BROKEN -> {
                    player.sendMessage(I18n.Message.Preview.structureCtrlBroken)
                    toRemove.add(sessionId)
                }
                PreviewSession.UpdateResult.STOPPED -> {
                    toRemove.add(sessionId)
                }
                PreviewSession.UpdateResult.CONTINUE -> {
                    // 继续运行
                }
            }
        }
        
        toRemove.forEach { sessionId ->
            val session = sessions.remove(sessionId)
            session?.stop()
            playerSessions.values.forEach { it.remove(sessionId) }
        }
        
        if (sessions.isEmpty()) {
            updateTask?.cancel()
            updateTask = null
        }
    }
    
    fun cleanup() {
        stopAllPreviews()
    }
}
