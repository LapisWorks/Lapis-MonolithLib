package top.mc506lw.monolith.feature.buildsite

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object LitematicaModeManager {
    
    private const val PROXIMITY_RANGE = 16.0
    private const val AWAY_TIMEOUT_TICKS = 5 * 20L
    
    private data class PlayerModeState(
        var easyBuild: Boolean = false,
        var printer: Boolean = false,
        var awayTask: BukkitTask? = null,
        var wasNearSite: Boolean = true
    )
    
    private val playerStates = ConcurrentHashMap<UUID, PlayerModeState>()
    
    fun isEasyBuildEnabled(player: Player): Boolean {
        return playerStates[player.uniqueId]?.easyBuild == true
    }
    
    fun isPrinterEnabled(player: Player): Boolean {
        return playerStates[player.uniqueId]?.printer == true
    }

    fun enableEasyBuild(player: Player): Boolean? {
        if (isEasyBuildEnabled(player)) return true
        return toggleEasyBuild(player)
    }

    fun disableEasyBuild(player: Player): Boolean? {
        if (!isEasyBuildEnabled(player)) return false
        return toggleEasyBuild(player)
    }

    fun enablePrinter(player: Player): Boolean? {
        if (isPrinterEnabled(player)) return true
        return togglePrinter(player)
    }

    fun disablePrinter(player: Player): Boolean? {
        if (!isPrinterEnabled(player)) return false
        return togglePrinter(player)
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
            player.sendMessage(I18n.Message.BuildSite.errHasActivePreview)
            return null
        }

        val nearestAnchor = findNearestAnchor(player)
        if (nearestAnchor == null) {
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
            player.sendMessage(I18n.Message.BuildSite.errHasActivePreview)
        }
        
        val nearestAnchor = findNearestAnchor(player)
        if (nearestAnchor == null) {
            return null
        }

        state.printer = true
        state.wasNearSite = true
        top.mc506lw.monolith.feature.buildmode.PrinterTask.start(player)
        return true
    }
    
    fun isNearAnySite(player: Player): Boolean {
        return findNearestAnchor(player) != null
    }

    /** 搜索附近的 [BuildSiteAnchorBlock]（新系统）。step=1 以避免漏检 1 格偏移 */
    fun findNearestAnchor(player: Player): BuildSiteAnchorBlock? {
        return BuildSiteRegistry.nearest(player, PROXIMITY_RANGE)
    }

    /** 返回附近所有有效的 anchor（用于跨工地移动时重索引 ghost）。step=2 平衡精度与开销 */
    fun findAnchorsNearbyActive(player: Player): List<BuildSiteAnchorBlock> {
        return BuildSiteRegistry.all().filter {
            it.block.world == player.world && it.block.location.distanceSquared(player.location) <= PROXIMITY_RANGE * PROXIMITY_RANGE
        }
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
        
        var countdown = (AWAY_TIMEOUT_TICKS / 20L).toInt()
        
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
                
                player.sendMessage(I18n.Message.BuildMode.errModeAutoDisabled(modes.joinToString("、")))
            } else if (countdown in 1..5) {
                player.sendMessage(I18n.Message.BuildMode.leftRangeCountdown(countdown))
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
