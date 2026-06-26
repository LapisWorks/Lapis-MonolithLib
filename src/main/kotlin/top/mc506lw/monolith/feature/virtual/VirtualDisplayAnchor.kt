package top.mc506lw.monolith.feature.virtual

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.MonolithLib

class VirtualDisplayAnchor(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context), EntityHolderRebarBlock, BlockBreakRebarBlockHandler {

    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "virtual_display_anchor")
        val MATERIAL = Material.BARRIER

        const val ENTITY_PREFIX = "vde_"
        const val DISPLAY_GROUP_KEY = "display_group"

        private val log = MonolithLogger.getLogger("VDA")
    }

    override var disableBlockTextureEntity = true

    override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext) {
        drops.clear()
        tryRemoveAllEntities()
    }

    override fun postLoad() {
        log.debug("pos=$blockLocationStr", "检查展示实体恢复")
        if (!isHeldEntityPresent(DISPLAY_GROUP_KEY)) {
            log.warn("pos=$blockLocationStr", "展示实体组丢失，需要重新生成")
        }
    }

    private val blockLocationStr get() = "pos=(${block.x},${block.y},${block.z})"
}
