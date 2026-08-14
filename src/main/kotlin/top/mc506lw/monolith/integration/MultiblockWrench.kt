package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.github.pylonmc.rebar.item.interfaces.BlockInteractRebarItemHandler
import top.mc506lw.monolith.common.I18n
import org.bukkit.NamespacedKey
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.feature.buildsite.BuildSiteAnchorBlock
import top.mc506lw.monolith.MonolithLib

/**
 * 多方块扳手 — 兼具"定型"和"解体"功能。
 *
 * ## 定型模式（右键工地展位）
 * 当所有方块铺设完毕后，将锚点 + 方块转换为最终的多方块结构。
 *
 * ## 解体模式（右键已形成的多方块）
 * 拆除整个多方块结构，返回可继续调整的脚手架工地。
 */
class MultiblockWrench(stack: ItemStack) : RebarItem(stack), BlockInteractRebarItemHandler {

    override fun onInteractWithBlock(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action == Action.PHYSICAL) return

        val player = event.player
        val clickedBlock = event.clickedBlock ?: return

        event.isCancelled = true

        // === 情况 1：点击了工地展位 → 定型 ===
        val rebarBlock = BlockStorage.get(clickedBlock)
        if (rebarBlock is BuildSiteAnchorBlock) {
            handleAnchorFinalize(rebarBlock, player)
            return
        }

        // === 情况 2：点击了形成的多方块控制器 → 解体 ===
        if (rebarBlock is MNBController) {
            if (!player.isSneaking) {
                player.sendMessage(I18n.Message.Wrench.hintShiftDisassemble)
                return
            }
            handleControllerDisassemble(rebarBlock, player)
            return
        }

        // === 情况 3：点击了多方块的一部分 → 找到控制器后解体 ===
        val controllerBlock = findMultiblockController(clickedBlock)
        if (controllerBlock != null) {
            if (!player.isSneaking) {
                player.sendMessage(I18n.Message.Wrench.hintShiftDisassemble)
                return
            }
            val controllerRebar = BlockStorage.get(controllerBlock)
            if (controllerRebar is MNBController) {
                handleControllerDisassemble(controllerRebar, player)
                return
            }
        }

        player.sendMessage(I18n.Message.Wrench.errNotValidStructure)
    }

    // ========== 定型 ==========

    private fun handleAnchorFinalize(anchor: BuildSiteAnchorBlock, player: org.bukkit.entity.Player) {
        // 检查完成率
        val rate = anchor.getCompletionRate()
        val bp = anchor.blueprint

        if (bp == null) {
            player.sendMessage(I18n.Message.Wrench.errNoBlueprint(""))
            return
        }

        if (rate < 1.0) {
            // missing 基于脚手架实际未匹配数（ghostData 规模），而非 assembled 方块总数
            val missing = anchor.getGhostCount() - anchor.getMatchedCount()
            player.sendMessage(I18n.Message.Wrench.errNotComplete((rate * 100).toInt(), missing))
            player.sendMessage(I18n.Message.Wrench.hintContinueBuilding)

            // 逐位置明细，区分材质缺失与 Rebar 方块缺失
            val issues = anchor.getIssues()
            val materialIssues = issues.filter { !it.isRebar }.take(8)
            val rebarIssues = issues.filter { it.isRebar }.take(8)
            if (materialIssues.isNotEmpty()) {
                player.sendMessage(I18n.Message.Wrench.detailMissingMaterial(materialIssues.size))
                materialIssues.forEach {
                    player.sendMessage(I18n.Message.Wrench.detailPosition(it.worldPos, it.preview_hint))
                }
            }
            if (rebarIssues.isNotEmpty()) {
                player.sendMessage(I18n.Message.Wrench.detailMissingRebar(rebarIssues.size))
                rebarIssues.forEach {
                    player.sendMessage(I18n.Message.Wrench.detailPosition(it.worldPos, it.hint))
                }
            }
            if (issues.size > 16) {
                player.sendMessage(I18n.Message.Wrench.detailMore(issues.size - 16))
            }
            return
        }

        // 检查是否有 controllerRebarKey
        if (bp.controllerRebarKey == null) {
            player.sendMessage(I18n.Message.Wrench.errNoBlueprintController(bp.id))
            return
        }

        if (!ProjectControllerRegistry.isRegistered(bp)) {
            player.sendMessage(I18n.Message.Wrench.errControllerNotRegistered(bp.controllerRebarKey.toString()))
            return
        }

        // 定型
        val success = anchor.finalizeWithWrench(player)
        if (!success) {
            player.sendMessage(I18n.Message.Wrench.errFinalizeFailed)
        }
    }

    // ========== 解体 ==========

    /**
     * 扳手右键已成型多方块 → 回到初始阶段（scaffold）+ 重新生成工地展位，不掉落任何物品。
     */
    private fun handleControllerDisassemble(controller: MNBController, player: org.bukkit.entity.Player) {
        if (controller.blueprintId == null) {
            player.sendMessage(I18n.Message.Wrench.errNoBlueprintController(""))
            return
        }
        if (StructureDisassembly.toScaffold(controller, player)) {
            player.sendMessage(I18n.Message.Wrench.disassembleSuccess)
        } else {
            player.sendMessage(I18n.Message.Wrench.errDisassembleFailed)
        }
    }

    /**
     * 找到点击方块所属的多方块的控制器方块。
     */
    private fun findMultiblockController(clickedBlock: org.bukkit.block.Block): org.bukkit.block.Block? {
        // 直接命中组件 → O(1) AABB 粗筛 + 蓝图精确判定（不再 5³ 扫描 125 个方块）
        MNBController.findControllerForComponent(clickedBlock)?.let { return it.block }

        val clickedRebar = BlockStorage.get(clickedBlock)
        if (clickedRebar is MNBController) {
            return clickedBlock
        }

        // 兜底：在附近搜索 (5x5x5)
        val world = clickedBlock.world
        for (dx in -5..5) {
            for (dy in -5..5) {
                for (dz in -5..5) {
                    val b = world.getBlockAt(clickedBlock.x + dx, clickedBlock.y + dy, clickedBlock.z + dz)
                    val rebar = BlockStorage.get(b)
                    if (rebar is MNBController && rebar.isPartOfMultiblock(clickedBlock)) {
                        return b
                    }
                }
            }
        }
        return null
    }

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "multiblock_wrench")

        val STACK: ItemStack by lazy {
            ItemStackBuilder.rebar(Material.GOLDEN_PICKAXE, KEY)
                .name(top.mc506lw.monolith.common.I18n.translatable("item.multiblock_wrench.name"))
                .build()
        }
    }
}
