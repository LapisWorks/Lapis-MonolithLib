package top.mc506lw.monolith.test.blocks

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.github.pylonmc.rebar.util.position.ChunkPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.integration.BlueprintMultiblockAdapter
import top.mc506lw.monolith.MonolithLib

object TestBlocks {

    private val logger = MonolithLogger.getLogger("TestBlocks")

    fun registerAll() {
        registerBlockWithItem(TestControllerBlock.KEY, TestControllerBlock.MATERIAL, TestControllerBlock::class.java)
        registerBlockWithItem(FurnaceCoreBlock.KEY, FurnaceCoreBlock.MATERIAL, FurnaceCoreBlock::class.java)
        registerBlockWithItem(MachineCoreBlock.KEY, MachineCoreBlock.MATERIAL, MachineCoreBlock::class.java)
        registerBlockWithItem(CustomStructureController.KEY, CustomStructureController.MATERIAL, CustomStructureController::class.java)

        logger.debug { "Registered 4 test Rebar blocks" }
    }
    
    private fun registerBlockWithItem(key: NamespacedKey, material: Material, blockClass: Class<out RebarBlock>) {
        RebarBlock.register(key, material, blockClass)
        
        val itemStack = ItemStackBuilder.rebar(material, key).build()
        
        RebarItem.register(RebarItem::class.java, itemStack, key)
    }
}

class TestControllerBlock(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context) {
    
    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))
    
    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "test_controller")
        val MATERIAL = Material.CRAFTING_TABLE
    }
}

class FurnaceCoreBlock(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context) {
    
    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))
    
    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "furnace_core")
        val MATERIAL = Material.FURNACE
    }
}

class MachineCoreBlock(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context) {
    
    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))
    
    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "machine_core")
        val MATERIAL = Material.BLAST_FURNACE
    }
}

class CustomStructureController(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context), RebarMultiblock, BlockBreakRebarBlockHandler {
    
    private val blueprintId = "custom_controller"
    
    protected val adapter: BlueprintMultiblockAdapter? by lazy {
        val api = try { MonolithAPI.getInstance() } catch (_: IllegalStateException) { null }
        val blueprint = api?.registry?.get(blueprintId) ?: return@lazy null
        
        val facing = detectFacing()
        BlueprintMultiblockAdapter(blueprint, block.location, facing)
    }
    
    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))
    
    protected open fun detectFacing(): Facing {
        return Facing.EAST
    }
    
    override val chunksOccupied: Set<ChunkPosition>
        get() {
            val chunks = mutableSetOf<ChunkPosition>()
            val adapter = this.adapter ?: return chunks
            
            val minCorner = adapter.components.keys.minWithOrNull(compareBy({ it.x }, { it.z })) ?: return chunks
            val maxCorner = adapter.components.keys.maxWithOrNull(compareBy({ it.x }, { it.z })) ?: return chunks
            
            for (x in minCorner.x..maxCorner.x step 16) {
                for (z in minCorner.z..maxCorner.z step 16) {
                    val worldPos = block.position + org.joml.Vector3i(x, 0, z)
                    chunks.add(worldPos.chunk)
                }
            }
            
            chunks.add(block.position.chunk)
            
            return chunks
        }
    
    override fun checkFormed(): Boolean {
        return adapter?.checkFormed() ?: false
    }
    
    override fun isPartOfMultiblock(otherBlock: Block): Boolean {
        val adapter = this.adapter ?: return false
        
        val relativePos = Vector3i(
            otherBlock.x - block.x,
            otherBlock.y - block.y,
            otherBlock.z - block.z
        )
        
        return adapter.components.containsKey(relativePos)
    }
    
    override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext) {
        drops.clear()

        val rebarItem = io.github.pylonmc.rebar.item.builder.ItemStackBuilder
            .rebar(MATERIAL, KEY)
            .build()

        drops.add(rebarItem)

        logger.debug { "onBreak: Dropped Rebar item $KEY" }
    }
    
    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "custom_controller")
        val MATERIAL = Material.LODESTONE
        private val logger = MonolithLogger.getLogger("CustomStructureController")
    }
}