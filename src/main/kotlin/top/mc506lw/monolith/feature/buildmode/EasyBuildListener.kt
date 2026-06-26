package top.mc506lw.monolith.feature.buildmode

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
import top.mc506lw.monolith.feature.buildsite.BuildSite
import top.mc506lw.monolith.feature.buildsite.BuildSiteManager
import top.mc506lw.monolith.feature.buildsite.BuildSiteState
import top.mc506lw.monolith.feature.buildsite.EasyBuildManager
import top.mc506lw.monolith.MonolithLib

class EasyBuildListener : Listener {

    private val legacy = LegacyComponentSerializer.legacySection()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!EasyBuildManager.isEnabled(player)) return

        val targetBlock = player.getTargetBlockExact(7) ?: return
        val targetPos = Vector3i(targetBlock.x, targetBlock.y, targetBlock.z)

        val entry = EasyBuildManager.findGhostEntryAt(targetPos.x, targetPos.y, targetPos.z) ?: return
        val (site, ghost) = entry

        if (site.state != BuildSiteState.BUILDING) return
        if (site.isCompleted) return
        if (!BuildSiteManager.isActiveSite(site.id)) return

        val block = site.anchorLocation.world?.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z) ?: return
        if (!block.type.isAir && !block.isReplaceable) return

        val requiredMaterial = ghost.previewBlockData.material
        val requiredBlockData = ghost.previewBlockData.clone()

        if (player.gameMode == GameMode.CREATIVE) {
            doPlaceBlock(block, requiredBlockData, player)
            site.recordPlacement(ghost.worldPos)
            event.isCancelled = true
            EasyBuildManager.onSiteUpdated(site)
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
        EasyBuildManager.onSiteUpdated(site)
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
                EasyBuildManager.onSiteUpdated(site)
            }, 5L)

            BuildSiteManager.saveAll()
        } else {
            player.sendMessage(legacy.serialize(I18n.Message.BuildMode.allComplete))
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
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellDoneController))
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellControllerKey(rebarKey.key)))
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellCoreMarker))
            } else {
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellDoneNoController))
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.shellCoreMarker))
            }
        }
    }

    private fun doPlaceBlock(block: Block, blockData: BlockData, player: Player) {
        try {
            block.setBlockData(blockData, false)
            val sound = block.blockData.material.createBlockData().soundGroup.placeSound
            block.world.playSound(block.location, sound, 1.0f, 1.0f)
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("EasyBuild place failed: ${e.message}")
        }
    }
}
