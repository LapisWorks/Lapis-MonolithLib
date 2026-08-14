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
    val previewBlockData: BlockData,
    val rebarKey: org.bukkit.NamespacedKey? = null
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
        if (!place(event.player, block, target)) return
        event.isCancelled = true
        target.anchor.renderForPlayer(event.player)
    }

    fun findAnchorGhostEntryAt(block: Block): AnchorGhostEntry? = BuildSiteRegistry.all()
        .asSequence()
        .filter { it.block.world == block.world }
        .mapNotNull { anchor -> anchor.getGhostAt(top.mc506lw.monolith.core.math.Vector3i(block.x, block.y, block.z))
            ?.let { AnchorGhostEntry(anchor, it.worldPos, it.previewBlockData, it.rebarKey) } }
        .firstOrNull()

    private fun place(player: Player, block: Block, target: AnchorGhostEntry): Boolean {
        val key = target.rebarKey
        if (key != null) {
            // 该位置要求 Rebar 方块：必须持有对应 key 的 RebarItem，通过 BlockStorage 放置
            if (player.gameMode != GameMode.CREATIVE) {
                val item = matchingRebarItem(player, key) ?: return false
                if (item.amount == 1) player.inventory.removeItem(item) else item.amount--
            }
            return try {
                io.github.pylonmc.rebar.block.BlockStorage.placeBlock(block, key) != null
            } catch (e: Exception) {
                MonolithLib.instance.logger.warning("EasyBuild 放置 Rebar 方块失败: ${e.message}")
                false
            }
        }

        if (player.gameMode != GameMode.CREATIVE) {
            val item = matchingItem(player, target.previewBlockData.material) ?: return false
            if (item.amount == 1) player.inventory.removeItem(item) else item.amount--
        }
        return try {
            block.setBlockData(target.previewBlockData.clone(), false)
            block.world.playSound(block.location, target.previewBlockData.material.createBlockData().soundGroup.placeSound, 0.5f, 1.2f)
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

    private fun matchingRebarItem(player: Player, key: org.bukkit.NamespacedKey): ItemStack? = sequenceOf(
        player.inventory.itemInMainHand,
        *player.inventory.contents.filterNotNull().toTypedArray()
    ).firstOrNull { item ->
        try {
            io.github.pylonmc.rebar.item.RebarItem.fromStack(item)?.key == key
        } catch (_: Exception) {
            false
        }
    }

    fun onPlayerQuit(playerId: java.util.UUID) = Unit
    fun cleanup() = Unit
}
