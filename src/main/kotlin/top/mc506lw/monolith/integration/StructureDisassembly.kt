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

        // 先移除控制器并放置展位：转换完成回调需要直接拿到展位引用。
        // （若沿用 BuildSiteRegistry.findAt 会在注册竞态下返回 null——锚点注册发生在异步
        // ghost 构建回调里，而小结构转换 1 tick 内就完成，findAt 必然失败。）
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

        // 转换任务全部完成（含未加载区块的补转队列排空）后，把工地完成度强制置为 100%：
        // 世界已被转换还原为脚手架状态，计数器无需等待玩家触发全量校准。
        controller.disassembleToScaffold(onComplete = {
            anchor.onScaffoldConversionComplete()
        })
        player?.let(anchor::renderForPlayer)
        return true
    }
}
