package top.mc506lw.monolith.feature.buildmode

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.feature.buildsite.BuildSite
import top.mc506lw.monolith.feature.buildsite.BuildSiteManager
import top.mc506lw.monolith.feature.buildsite.BuildSiteState
import top.mc506lw.monolith.feature.buildsite.EasyBuildManager
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PrinterTask private constructor(
    private val playerId: UUID
) : BukkitRunnable() {

    companion object {
        private const val PRINTER_RADIUS = 4

        private val activeTasks = ConcurrentHashMap<UUID, PrinterTask>()
        private val legacy = LegacyComponentSerializer.legacySection()

        fun start(player: Player): Boolean {
            if (activeTasks.containsKey(player.uniqueId)) return false

            val task = PrinterTask(player.uniqueId)
            task.runTaskTimer(MonolithLib.instance, 0L, 4L)
            activeTasks[player.uniqueId] = task
            return true
        }

        fun stop(player: Player) {
            activeTasks.remove(player.uniqueId)?.cancel()
        }

        fun isRunning(player: Player): Boolean = activeTasks.containsKey(player.uniqueId)

        fun stopAll() {
            activeTasks.values.forEach { it.cancel() }
            activeTasks.clear()
        }
    }

    override fun run() {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline) {
            cancel()
            activeTasks.remove(playerId)
            BuildSiteManager.saveAll()
            return
        }

        if (!top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.isPrinterEnabled(player)) {
            cancel()
            activeTasks.remove(playerId)
            BuildSiteManager.saveAll()
            return
        }

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

                    if (site.state != BuildSiteState.BUILDING) continue
                    if (site.isCompleted) continue
                    if (!BuildSiteManager.isActiveSite(site.id)) continue

                    val block = site.anchorLocation.world?.getBlockAt(x, y, z) ?: continue
                    if (!block.type.isAir && !block.isReplaceable) continue

                    val requiredMaterial = ghost.previewBlockData.material
                    val requiredBlockData = ghost.previewBlockData.clone()

                    if (player.gameMode == GameMode.CREATIVE) {
                        doPlaceBlock(block, requiredBlockData)
                        site.recordPlacement(ghost.worldPos)
                        EasyBuildManager.onSiteUpdated(site)
                        checkAdvancement(site, player)
                        return
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
                    return
                }
            }
        }
    }

    private fun findMatchingItem(player: Player, material: Material): ItemStack? {
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.type == material && itemInHand.type != Material.AIR) return itemInHand

        for (item in player.inventory.contents) {
            if (item != null && item.type == material) return item
        }
        return null
    }

    private fun doPlaceBlock(block: org.bukkit.block.Block, blockData: org.bukkit.block.data.BlockData) {
        try {
            block.setBlockData(blockData, false)
            val sound = block.blockData.material.createBlockData().soundGroup.placeSound
            block.world.playSound(block.location, sound, 0.5f, 1.2f)
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("Printer place failed: ${e.message}")
        }
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
}
