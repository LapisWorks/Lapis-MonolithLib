package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import org.bukkit.Material
import org.bukkit.entity.Player
import top.mc506lw.monolith.feature.buildsite.BuildSiteAnchorBlock
import top.mc506lw.monolith.feature.buildsite.EasyBuildManager

/** Restores a formed generic controller to its scaffold site without drops. */
object StructureDisassembly {
    fun toScaffold(controller: MNBController, player: Player? = null): Boolean {
        val blueprintId = controller.blueprintId ?: return false
        val block = controller.block
        val facing = controller.facing

        controller.disassembleToScaffold()
        try {
            BlockStorage.breakBlock(
                block,
                BlockBreakContext.PluginBreak(block, normallyDrops = false, shouldSetToAir = false)
            )
        } catch (_: Exception) {
        }
        block.setType(Material.AIR, false)

        val anchor = BlockStorage.placeBlock(block, BuildSiteAnchorBlock.KEY) as? BuildSiteAnchorBlock ?: return false
        anchor.initialize(blueprintId, facing)
        player?.let(anchor::renderForPlayer)
        return true
    }
}
