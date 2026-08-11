package top.mc506lw.monolith.feature.buildsite

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
import top.mc506lw.monolith.MonolithLib
data class AnchorGhostEntry(
    val anchor: BuildSiteAnchorBlock,
    val worldPos: top.mc506lw.monolith.core.math.Vector3i,
    val previewBlockData: BlockData
)

/** EasyBuild resolves a clicked world position on demand; it never indexes every ghost block. */
object EasyBuildManager : Listener {
    fun isEnabled(player: Player): Boolean = LitematicaModeManager.isEasyBuildEnabled(player)
    fun toggle(player: Player): Boolean? = LitematicaModeManager.toggleEasyBuild(player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!isEnabled(event.player) || event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val clicked = event.clickedBlock ?: return
        val face = event.blockFace
        // 目标方块是点击的面所朝向的空位（Litematica easy place 语义），
        // 也可能是点击方块本身（已放错材质，需覆盖）。
        val target = findAnchorGhostEntryAt(clicked.getRelative(face))
            ?: findAnchorGhostEntryAt(clicked)
            ?: return
        val block = target.anchor.block.world.getBlockAt(target.worldPos.x, target.worldPos.y, target.worldPos.z)
        if (!block.type.isAir && !block.isReplaceable) return
        if (!place(event.player, block, target.previewBlockData)) return
        event.isCancelled = true
        target.anchor.renderForPlayer(event.player)
    }

    fun findAnchorGhostEntryAt(block: Block): AnchorGhostEntry? = BuildSiteRegistry.all()
        .asSequence()
        .filter { it.block.world == block.world }
        .mapNotNull { anchor -> anchor.getGhostAt(top.mc506lw.monolith.core.math.Vector3i(block.x, block.y, block.z))
            ?.let { AnchorGhostEntry(anchor, it.worldPos, it.previewBlockData) } }
        .firstOrNull()

    private fun place(player: Player, block: Block, data: BlockData): Boolean {
        if (player.gameMode != GameMode.CREATIVE) {
            val item = matchingItem(player, data.material) ?: return false
            if (item.amount == 1) player.inventory.removeItem(item) else item.amount--
        }
        return try {
            block.setBlockData(data.clone(), false)
            block.world.playSound(block.location, data.material.createBlockData().soundGroup.placeSound, 0.5f, 1.2f)
            true
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("EasyBuild 放置失败: ${e.message}")
            false
        }
    }

    private fun matchingItem(player: Player, material: Material): ItemStack? = sequenceOf(
        player.inventory.itemInMainHand,
        *player.inventory.contents.filterNotNull().toTypedArray()
    ).firstOrNull { it.type == material }

    fun onPlayerQuit(playerId: java.util.UUID) = Unit
    fun cleanup() = Unit
}
