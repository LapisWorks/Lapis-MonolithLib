package top.mc506lw.monolith.feature.buildsite

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PrinterManager {

    private const val PRINTER_RADIUS = 4
    private const val TICKS_PER_PLACE = 4L

    private val activePrinters = ConcurrentHashMap<UUID, Int>()
    private val log = MonolithLogger.getLogger("Printer")
    
    private data class PosKey(val x: Int, val y: Int, val z: Int)
    private val legacy = LegacyComponentSerializer.legacySection()
    
    fun isEnabled(player: Player): Boolean {
        return LitematicaModeManager.isPrinterEnabled(player)
    }
    
    fun toggle(player: Player): Boolean? {
        return LitematicaModeManager.togglePrinter(player)
    }
    
    fun startPrinter(player: Player) {
        val playerId = player.uniqueId
        if (activePrinters.containsKey(playerId)) return
        
        activePrinters[playerId] = 0
        scheduleNextPlace(player)
    }
    
    fun stopPrinter(player: Player) {
        activePrinters.remove(player.uniqueId)
    }
    
    fun isRunning(player: Player): Boolean {
        return activePrinters.containsKey(player.uniqueId)
    }
    
    private fun scheduleNextPlace(player: Player) {
        val playerId = player.uniqueId
        if (!activePrinters.containsKey(playerId)) return
        if (!isEnabled(player)) {
            activePrinters.remove(playerId)
            return
        }
        if (!player.isOnline) {
            activePrinters.remove(playerId)
            return
        }
        
        Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
            if (!activePrinters.containsKey(playerId)) return@Runnable
            if (!isEnabled(player) || !player.isOnline) {
                activePrinters.remove(playerId)
                return@Runnable
            }
            
            val placed = tryPlaceOneBlock(player)
            
            if (placed) {
                activePrinters[playerId] = activePrinters.getOrDefault(playerId, 0) + 1
            }
            
            scheduleNextPlace(player)
        }, TICKS_PER_PLACE)
    }
    
    private fun tryPlaceOneBlock(player: Player): Boolean {
        val playerLoc = player.location
        val px = playerLoc.blockX
        val py = playerLoc.blockY
        val pz = playerLoc.blockZ
        
        for (dx in -PRINTER_RADIUS..PRINTER_RADIUS) {
            for (dy in -PRINTER_RADIUS..PRINTER_RADIUS) {
                for (dz in -PRINTER_RADIUS..PRINTER_RADIUS) {
                    if (dx * dx + dy * dy + dz * dz > PRINTER_RADIUS * PRINTER_RADIUS) continue
                    
                    val x = px + dx
                    val y = py + dy
                    val z = pz + dz
                    
                    val entry = EasyBuildManager.findGhostEntryAt(x, y, z) ?: continue
                    val (site, ghost) = entry
                    
                    val block = site.anchorLocation.world?.getBlockAt(x, y, z) ?: continue
                    if (!block.type.isAir && !block.isReplaceable) continue
                    
                    val requiredMaterial = ghost.previewBlockData.material
                    val requiredBlockData = ghost.previewBlockData.clone()
                    
                    if (player.gameMode == GameMode.CREATIVE) {
                        doPlaceBlock(block, requiredBlockData)
                        site.recordPlacement(ghost.worldPos)
                        EasyBuildManager.onSiteUpdated(site)
                        checkAdvancement(site, player)
                        return true
                    }
                    
                    val usedItem = findMatchingItem(player, requiredMaterial)
                    if (usedItem == null) continue
                    
                    if (usedItem.amount > 1) {
                        usedItem.amount = usedItem.amount - 1
                    } else {
                        player.inventory.removeItem(usedItem)
                    }
                    
                    doPlaceBlock(block, requiredBlockData)
                    site.recordPlacement(ghost.worldPos)
                    EasyBuildManager.onSiteUpdated(site)
                    checkAdvancement(site, player)
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun findMatchingItem(player: Player, material: Material): ItemStack? {
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.type == material && itemInHand.type != Material.AIR) {
            return itemInHand
        }
        
        for (item in player.inventory.contents) {
            if (item != null && item.type == material) {
                return item
            }
        }
        return null
    }
    
    private fun doPlaceBlock(block: org.bukkit.block.Block, blockData: org.bukkit.block.data.BlockData) {
        try {
            block.setBlockData(blockData, false)
            val sound = block.blockData.material.createBlockData().soundGroup.placeSound
            block.world.playSound(block.location, sound, 0.5f, 1.2f)
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("Printer 放置方块失败: ${e.message}")
        }
    }
    
    private fun checkAdvancement(site: BuildSite, player: Player) {
        if (!site.checkLayerCompletion()) return

        log.debug("site=${site.id}", "层完成检查通过", "layer" to site.currentLayer)

        val advancedCount = site.advanceToNextIncompleteLayer()

        if (advancedCount > 0) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.layerCompleted(site.currentLayer)))
            log.info("site=${site.id}", "层推进完成", "advancedCount" to advancedCount, "currentLayer" to site.currentLayer)

            Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
                for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                    site.renderForPlayer(onlinePlayer)
                }
                EasyBuildManager.onSiteUpdated(site)
            }, 5L)

            BuildSiteManager.saveAll()
        } else {
            log.info("site=${site.id}", "所有层已完成，进入最终阶段")
            startFinalPhase(site, player)
        }
    }
    
    private fun startFinalPhase(site: BuildSite, player: Player) {
        player.sendMessage(legacy.serialize(I18n.Message.BuildMode.allComplete))
        
        val result = site.validateDetailed()
        
        player.sendMessage(legacy.serialize(I18n.Message.BuildMode.progress(
            (result.completionRate * 100).toInt(),
            result.matchedCount,
            result.totalCount
        )))
        
        if (result.needsFix) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.needFix(result.blocksToFix.size)))
            
            val world = site.anchorLocation.world
            if (world != null) {
                val fixedCount = top.mc506lw.monolith.validation.AutoFixer.fixBlocksSync(result.blocksToFix, world)
                if (fixedCount > 0) {
                    player.sendMessage(legacy.serialize(I18n.Message.BuildMode.fixed(fixedCount)))
                }
            }
        }
        
        site.enterAwaitingCore()
        
        val rebarKey = site.coreRebarKey
        if (rebarKey != null) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellComplete))
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellController(rebarKey.key)))
        } else {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellCompleteNoCore))
        }
        
        Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
            for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                site.renderForPlayer(onlinePlayer)
            }
        }, 10L)
        
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }
    
    fun onPlayerQuit(playerId: UUID) {
        activePrinters.remove(playerId)
    }
    
    fun cleanup() {
        activePrinters.clear()
    }
}