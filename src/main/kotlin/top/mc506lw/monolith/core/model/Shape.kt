package top.mc506lw.monolith.core.model

import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates

data class BlockEntry(
    val position: Vector3i,
    val blockData: BlockData,
    val predicate: Predicate? = null
) {
    val effectivePredicate: Predicate get() = predicate ?: Predicates.strict(blockData)
}

data class BoundingBox(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1
    
    val width: Int get() = sizeX
    val height: Int get() = sizeY
    val depth: Int get() = sizeZ
    
    val volume: Int get() = sizeX * sizeY * sizeZ
    
    val center: Vector3i get() = Vector3i(
        (minX + maxX) / 2,
        (minY + maxY) / 2,
        (minZ + maxZ) / 2
    )
    
    fun contains(x: Int, y: Int, z: Int): Boolean {
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
    
    companion object {
        fun fromBlocks(blocks: List<BlockEntry>): BoundingBox {
            if (blocks.isEmpty()) {
                return BoundingBox(0, 0, 0, 0, 0, 0)
            }
            
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            
            for (block in blocks) {
                val pos = block.position
                minX = minOf(minX, pos.x)
                minY = minOf(minY, pos.y)
                minZ = minOf(minZ, pos.z)
                maxX = maxOf(maxX, pos.x)
                maxY = maxOf(maxY, pos.y)
                maxZ = maxOf(maxZ, pos.z)
            }
            
            return BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        }
        
        fun fromDimensions(sizeX: Int, sizeY: Int, sizeZ: Int): BoundingBox {
            return BoundingBox(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1)
        }
    }
}

class Shape(
    val blocks: List<BlockEntry>,
    val boundingBox: BoundingBox
) {
    private val blocksByPosition: Map<Vector3i, BlockEntry> by lazy { blocks.associateBy { it.position } }
    val blockCount: Int get() = blocks.size
    
    constructor(blocks: List<BlockEntry>) : this(blocks, BoundingBox.fromBlocks(blocks))
    
    fun getBlockAt(x: Int, y: Int, z: Int): BlockEntry? = blocksByPosition[Vector3i(x, y, z)]
    
    fun getBlocksInLayer(y: Int): List<BlockEntry> {
        return blocks.filter { it.position.y == y }
    }
    
    fun getUniqueYLevels(): Set<Int> {
        return blocks.map { it.position.y }.toSet()
    }
    
    companion object {
        val EMPTY = Shape(emptyList(), BoundingBox(0, 0, 0, 0, 0, 0))
        
        fun builder(): ShapeBuilder = ShapeBuilder()
    }
}

class ShapeBuilder {
    private val blocks = mutableListOf<BlockEntry>()
    
    fun addBlock(x: Int, y: Int, z: Int, blockData: BlockData): ShapeBuilder {
        blocks.add(BlockEntry(Vector3i(x, y, z), blockData))
        return this
    }
    
    fun addBlock(position: Vector3i, blockData: BlockData): ShapeBuilder {
        blocks.add(BlockEntry(position, blockData))
        return this
    }
    
    fun addAll(entries: Iterable<BlockEntry>): ShapeBuilder {
        blocks.addAll(entries)
        return this
    }
    
    fun build(): Shape {
        return Shape(blocks.toList())
    }
}
