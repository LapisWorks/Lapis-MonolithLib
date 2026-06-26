package top.mc506lw.monolith.feature.buildsite

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LitematicaModeManager {
    
    private const val PROXIMITY_RANGE = 16.0
    private const val AWAY_TIMEOUT_TICKS = 60 * 20L
    
    private data class PlayerModeState(
        var easyBuild: Boolean = false,
        var printer: Boolean = false,
        var awayTask: BukkitTask? = null,
        var wasNearSite: Boolean = true
    )
    
    private val playerStates = ConcurrentHashMap<UUID, PlayerModeState>()
    private val legacy = LegacyComponentSerializer.legacySection()
    
    fun isEasyBuildEnabled(player: Player): Boolean {
        return playerStates[player.uniqueId]?.easyBuild == true
    }
    
    fun isPrinterEnabled(player: Player): Boolean {
        return playerStates[player.uniqueId]?.printer == true
    }
    
    fun toggleEasyBuild(player: Player): Boolean? {
        val playerId = player.uniqueId
        val state = playerStates.getOrPut(playerId) { PlayerModeState() }
        
        if (state.easyBuild) {
            state.easyBuild = false
            cancelAwayTask(playerId)
            if (!state.printer) {
                playerStates.remove(playerId)
            }
            return false
        }
        
        if (BuildSitePreviewManager.hasActivePreview(player)) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errHasActivePreview))
            return null
        }

        val nearestSite = findNearestSite(player)
        if (nearestSite == null) {
            return null
        }
        
        state.easyBuild = true
        state.wasNearSite = true
        return true
    }
    
    fun togglePrinter(player: Player): Boolean? {
        val playerId = player.uniqueId
        val state = playerStates.getOrPut(playerId) { PlayerModeState() }
        
        if (state.printer) {
            state.printer = false
            top.mc506lw.monolith.feature.buildmode.PrinterTask.stop(player)
            cancelAwayTask(playerId)
            if (!state.easyBuild) {
                playerStates.remove(playerId)
            }
            return false
        }
        
        if (BuildSitePreviewManager.hasActivePreview(player)) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errHasActivePreview))
        }
        
        val nearestSite = findNearestSite(player)
        if (nearestSite == null) {
            return null
        }
        
        state.printer = true
        state.wasNearSite = true
        top.mc506lw.monolith.feature.buildmode.PrinterTask.start(player)
        return true
    }
    
    fun isNearAnySite(player: Player): Boolean {
        return findNearestSite(player) != null
    }
    
    fun findNearestSite(player: Player): BuildSite? {
        val playerLoc = player.location
        val px = playerLoc.x
        val py = playerLoc.y
        val pz = playerLoc.z
        val range = PROXIMITY_RANGE
        var nearest: BuildSite? = null
        var nearestDistSq = Double.MAX_VALUE
        
        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.anchorLocation.world?.name != playerLoc.world?.name) continue
            if (site.isCompleted) continue
            
            val closestX = px.coerceIn(site.boundingMinX.toDouble(), site.boundingMaxX.toDouble())
            val closestY = py.coerceIn(site.boundingMinY.toDouble(), site.boundingMaxY.toDouble())
            val closestZ = pz.coerceIn(site.boundingMinZ.toDouble(), site.boundingMaxZ.toDouble())
            
            val dx = px - closestX
            val dy = py - closestY
            val dz = pz - closestZ
            val distSq = dx * dx + dy * dy + dz * dz
            
            if (distSq <= range * range && distSq < nearestDistSq) {
                nearest = site
                nearestDistSq = distSq
            }
        }
        
        return nearest
    }
    
    fun onPlayerTick(player: Player) {
        val state = playerStates[player.uniqueId] ?: return
        if (!state.easyBuild && !state.printer) return
        
        val nearSite = isNearAnySite(player)
        
        if (nearSite) {
            state.wasNearSite = true
            cancelAwayTask(player.uniqueId)
        } else if (state.wasNearSite) {
            state.wasNearSite = false
            startAwayTimer(player)
        }
    }
    
    private fun startAwayTimer(player: Player) {
        val playerId = player.uniqueId
        cancelAwayTask(playerId)
        
        val state = playerStates[playerId] ?: return
        
        var countdown = 60
        
        state.awayTask = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
            countdown--
            
            val currentState = playerStates[playerId]
            if (currentState == null || (!currentState.easyBuild && !currentState.printer)) {
                cancelAwayTask(playerId)
                return@Runnable
            }
            
            if (isNearAnySite(player)) {
                currentState.wasNearSite = true
                cancelAwayTask(playerId)
                return@Runnable
            }
            
            if (countdown <= 0) {
                val modes = mutableListOf<String>()
                if (currentState.easyBuild) modes.add("轻松放置")
                if (currentState.printer) modes.add("自动打印")
                
                if (currentState.printer) {
                    top.mc506lw.monolith.feature.buildmode.PrinterTask.stop(player)
                }
                
                currentState.easyBuild = false
                currentState.printer = false
                cancelAwayTask(playerId)
                playerStates.remove(playerId)
                
                val msg = I18n.Message.BuildMode.errModeAutoDisabled(modes.joinToString("、"))
                player.sendMessage(legacy.serialize(msg))
            } else if (countdown <= 10 && countdown % 5 == 0) {
                val msg = I18n.Message.BuildMode.leftRangeCountdown(countdown)
                player.sendMessage(legacy.serialize(msg))
            }
        }, 20L, 20L)
    }
    
    private fun cancelAwayTask(playerId: UUID) {
        playerStates[playerId]?.awayTask?.cancel()
        playerStates[playerId]?.let { 
            @Suppress("UNUSED_EXPRESSION")
            it.awayTask = null 
        }
    }
    
    fun onPlayerQuit(playerId: UUID) {
        cancelAwayTask(playerId)
        playerStates.remove(playerId)
    }
    
    fun cleanup() {
        playerStates.values.forEach { it.awayTask?.cancel() }
        playerStates.clear()
    }
}