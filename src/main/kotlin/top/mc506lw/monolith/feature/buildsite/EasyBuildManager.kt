package top.mc506lw.monolith.feature.buildsite

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GhostBlockEntry(
    val site: BuildSite,
    val ghost: SiteGhostBlock
)

object EasyBuildManager : Listener {
    
    private data class PosKey(val x: Int, val y: Int, val z: Int)
    
    private val ghostIndex = ConcurrentHashMap<PosKey, GhostBlockEntry>()
    private val legacy = LegacyComponentSerializer.legacySection()
    
    fun isEnabled(player: Player): Boolean {
        return LitematicaModeManager.isEasyBuildEnabled(player)
    }
    
    fun toggle(player: Player): Boolean? {
        return LitematicaModeManager.toggleEasyBuild(player)
    }
    
    fun rebuildIndex() {
        ghostIndex.clear()
        
        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.isCompleted || site.state != BuildSiteState.BUILDING) continue
            
            val currentLayerY = site.getCurrentLayerY() ?: continue
            
            for (ghost in site.allGhostBlocks) {
                if (ghost.isCore) continue
                if (ghost.worldPos.y != currentLayerY) continue
                if (ghost.worldPos in site.placedBlocks) continue
                
                ghostIndex[PosKey(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)] = 
                    GhostBlockEntry(site, ghost)
            }
        }
    }
    
    fun onSiteUpdated(site: BuildSite) {
        if (site.isCompleted || site.state != BuildSiteState.BUILDING) {
            for (ghost in site.allGhostBlocks) {
                ghostIndex.remove(PosKey(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z))
            }
            return
        }
        
        val currentLayerY = site.getCurrentLayerY() ?: return
        
        for (ghost in site.allGhostBlocks) {
            val key = PosKey(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)
            
            if (ghost.isCore || ghost.worldPos.y != currentLayerY || ghost.worldPos in site.placedBlocks) {
                ghostIndex.remove(key)
            } else {
                ghostIndex[key] = GhostBlockEntry(site, ghost)
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        
        val player = event.player
        if (!isEnabled(player)) return
        
        val result = findTargetGhostBlock(event)
        if (result == null) return
        
        val (site, ghost) = result
        
        val block = site.anchorLocation.world?.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z) ?: return
        
        if (!block.type.isAir && !block.isReplaceable) return
        
        val requiredMaterial = ghost.previewBlockData.material
        val requiredBlockData = ghost.previewBlockData.clone()
        
        if (player.gameMode == GameMode.CREATIVE) {
            doPlaceBlock(block, requiredBlockData, player)
            site.recordPlacement(ghost.worldPos)
            event.isCancelled = true
            removeFromIndex(ghost.worldPos)
            checkAdvancement(site, player)
            return
        }
        
        var usedItem: ItemStack? = null
        
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.type == requiredMaterial && itemInHand.type != Material.AIR) {
            usedItem = itemInHand
        } else {
            for (item in player.inventory.contents) {
                if (item != null && item.type == requiredMaterial) {
                    usedItem = item
                    break
                }
            }
        }
        
        if (usedItem == null) return
        
        if (usedItem.amount > 1) {
            usedItem.amount = usedItem.amount - 1
        } else {
            player.inventory.removeItem(usedItem)
        }
        
        doPlaceBlock(block, requiredBlockData, player)
        site.recordPlacement(ghost.worldPos)
        event.isCancelled = true
        removeFromIndex(ghost.worldPos)
        checkAdvancement(site, player)
    }
    
    private fun checkAdvancement(site: BuildSite, player: Player) {
        if (!site.checkLayerCompletion()) return

        val advancedCount = site.advanceToNextIncompleteLayer()

        if (advancedCount > 0) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.layerCompleted(site.currentLayer)))

            Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
                for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                    site.renderForPlayer(onlinePlayer)
                }
                onSiteUpdated(site)
            }, 5L)

            BuildSiteManager.saveAll()
        } else {
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
        
        if (!result.isComplete) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errIncomplete(result.totalCount - result.matchedCount)))
            return
        }
        
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
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()

        Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
            for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                site.renderForPlayer(onlinePlayer)
            }
        }, 10L)

        val rebarKey = site.coreRebarKey
        if (rebarKey != null) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellComplete))
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellController(rebarKey.key)))
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellCoreMarker))
        } else {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.shellCompleteNoCore))
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellCoreMarker))
        }
    }
    
    private fun findTargetGhostBlock(event: PlayerInteractEvent): GhostBlockEntry? {
        val clickedBlock = event.clickedBlock ?: return null
        val blockFace = event.blockFace
        
        val checkPositions = mutableListOf<Vector3i>()
        
        checkPositions.add(Vector3i(clickedBlock.x, clickedBlock.y, clickedBlock.z))
        
        val relative = clickedBlock.getRelative(blockFace)
        checkPositions.add(Vector3i(relative.x, relative.y, relative.z))
        
        for (pos in checkPositions) {
            val entry = ghostIndex[PosKey(pos.x, pos.y, pos.z)]
            if (entry != null) return entry
        }
        
        return null
    }
    
    fun findGhostEntryAt(x: Int, y: Int, z: Int): GhostBlockEntry? {
        return ghostIndex[PosKey(x, y, z)]
    }
    
    private fun doPlaceBlock(block: Block, blockData: BlockData, player: Player) {
        try {
            block.setBlockData(blockData, false)
            
            val sound = block.blockData.material.createBlockData().soundGroup.placeSound
            block.world.playSound(block.location, sound, 1.0f, 1.0f)
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("EasyBuild 放置方块失败: ${e.message}")
        }
    }
    
    private fun removeFromIndex(pos: Vector3i) {
        ghostIndex.remove(PosKey(pos.x, pos.y, pos.z))
    }
    
    fun cleanup() {
        ghostIndex.clear()
    }
    
    fun onPlayerQuit(playerId: UUID) {
    }
}