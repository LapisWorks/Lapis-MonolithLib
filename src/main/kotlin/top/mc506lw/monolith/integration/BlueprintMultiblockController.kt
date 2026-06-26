package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import io.github.pylonmc.rebar.util.position.ChunkPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.api.MonolithAPI

abstract class BlueprintMultiblockController(
    val blueprintId: String
) : RebarMultiblock {
    
    abstract val rebarBlock: RebarBlock
    
    override val block: Block
        get() = rebarBlock.block
    
    protected val adapter: BlueprintMultiblockAdapter? by lazy {
        val api = try { MonolithAPI.getInstance() } catch (_: IllegalStateException) { null }
        val blueprint = api?.registry?.get(blueprintId) ?: return@lazy null
        
        val facing = detectFacing()
        BlueprintMultiblockAdapter(blueprint, block.location, facing)
    }
    
    protected open fun detectFacing(): Facing {
        return Facing.NORTH
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
    
    fun getCompletionRate(): Double {
        return adapter?.getCompletionRate() ?: 0.0
    }
    
    fun getMissingBlocks(): List<Vector3i> {
        return adapter?.getMissingBlocks() ?: emptyList()
    }
}