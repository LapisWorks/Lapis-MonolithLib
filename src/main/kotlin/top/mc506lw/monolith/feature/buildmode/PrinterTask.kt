package top.mc506lw.monolith.feature.buildmode

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.feature.buildsite.EasyBuildManager
import top.mc506lw.monolith.feature.buildsite.LitematicaModeManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Places at most one nearby scaffold block every four ticks. */
class PrinterTask private constructor(private val playerId: UUID) : BukkitRunnable() {
    companion object {
        private const val RADIUS = 3
        private val tasks = ConcurrentHashMap<UUID, PrinterTask>()

        fun start(player: Player): Boolean {
            if (tasks.containsKey(player.uniqueId)) return false
            return PrinterTask(player.uniqueId).also {
                tasks[player.uniqueId] = it
                it.runTaskTimer(MonolithLib.instance, 0L, 4L)
            }.let { true }
        }

        fun stop(player: Player) { tasks.remove(player.uniqueId)?.cancel() }
        fun isRunning(player: Player): Boolean = tasks.containsKey(player.uniqueId)
        fun stopAll() { tasks.values.forEach { it.cancel() }; tasks.clear() }
    }

    override fun run() {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline || !LitematicaModeManager.isPrinterEnabled(player)) {
            tasks.remove(playerId)
            cancel()
            return
        }
        val origin = player.location
        for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) continue
            val entry = EasyBuildManager.findAnchorGhostEntryAt(
                player.world.getBlockAt(origin.blockX + dx, origin.blockY + dy, origin.blockZ + dz)
            ) ?: continue
            val block = player.world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
            if (!block.type.isAir && !block.isReplaceable) continue
            if (place(player, block, entry.previewBlockData)) {
                entry.anchor.renderForPlayer(player)
                return
            }
        }
    }

    private fun place(player: Player, block: Block, data: BlockData): Boolean {
        if (player.gameMode != GameMode.CREATIVE) {
            val item = matchingItem(player, data.material) ?: return false
            if (item.amount == 1) player.inventory.removeItem(item) else item.amount--
        }
        block.setBlockData(data.clone(), false)
        return true
    }

    private fun matchingItem(player: Player, material: Material): ItemStack? = sequenceOf(
        player.inventory.itemInMainHand,
        *player.inventory.contents.filterNotNull().toTypedArray()
    ).firstOrNull { it.type == material }
}
