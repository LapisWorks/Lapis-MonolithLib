package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.interfaces.SimpleRebarMultiblock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates

class BlueprintMultiblockAdapter(
    val blueprint: Blueprint,
    val controllerLocation: Location,
    val facing: Facing
) {
    val transform: CoordinateTransform = CoordinateTransform(facing)
    
    private val centerOffset = blueprint.meta.controllerOffset
    
    val components: Map<Vector3i, BlueprintBlockComponent> by lazy {
        val result = mutableMapOf<Vector3i, BlueprintBlockComponent>()
        
        for (blockEntry in blueprint.assembledShape.blocks) {
            val relativePos = Vector3i(
                blockEntry.position.x - centerOffset.x,
                blockEntry.position.y - centerOffset.y,
                blockEntry.position.z - centerOffset.z
            )
            
            val rotatedBlockData = top.mc506lw.monolith.core.transform.BlockStateRotator.rotate(
                blockEntry.blockData,
                facing.rotationSteps
            )
            
            val isController = (relativePos.x == 0 && relativePos.y == 0 && relativePos.z == 0)
            
            result[relativePos] = BlueprintBlockComponent(
                blockData = rotatedBlockData,
                material = rotatedBlockData.material,
                isControllerPos = isController
            )
        }
        
        result
    }
    
    fun checkFormed(): Boolean {
        val world = controllerLocation.world ?: return false
        
        for ((offset, component) in components) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(controllerLocation.blockX, controllerLocation.blockY, controllerLocation.blockZ),
                relativePos = offset,
                centerOffset = Vector3i(0, 0, 0)
            )
            
            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            
            if (!component.matches(block)) {
                return false
            }
        }
        
        return true
    }
    
    fun getMissingBlocks(): List<Vector3i> {
        val world = controllerLocation.world ?: return emptyList()
        val missing = mutableListOf<Vector3i>()
        
        for ((offset, component) in components) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(controllerLocation.blockX, controllerLocation.blockY, controllerLocation.blockZ),
                relativePos = offset,
                centerOffset = Vector3i(0, 0, 0)
            )
            
            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            
            if (!component.matches(block)) {
                missing.add(offset)
            }
        }
        
        return missing
    }
    
    fun getCompletionRate(): Double {
        val world = controllerLocation.world ?: return 0.0
        var matched = 0
        val total = components.size
        
        for ((offset, _) in components) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(controllerLocation.blockX, controllerLocation.blockY, controllerLocation.blockZ),
                relativePos = offset,
                centerOffset = Vector3i(0, 0, 0)
            )
            
            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            val component = components[offset]!!
            
            if (component.matches(block)) {
                matched++
            }
        }
        
        return if (total > 0) matched.toDouble() / total.toDouble() else 1.0
    }
    
    class BlueprintBlockComponent(
        val blockData: BlockData,
        val material: Material,
        val isControllerPos: Boolean = false
    ) {
        fun matches(block: Block): Boolean {
            if (isControllerPos && BlockStorage.isRebarBlock(block)) {
                return true
            }
            
            if (BlockStorage.isRebarBlock(block)) {
                return false
            }
            
            return block.type == material
        }
        
        fun placeDefaultBlock(player: org.bukkit.entity.Player?, block: Block): Boolean {
            if (block.type.isAir && !BlockStorage.isRebarBlock(block)) {
                block.blockData = blockData
                return true
            }
            return false
        }
    }
    
    companion object {
        fun create(blueprintId: String, controllerLocation: Location, facing: Facing): BlueprintMultiblockAdapter? {
            val api = try { top.mc506lw.monolith.api.MonolithAPI.getInstance() } catch (_: IllegalStateException) { null }
            val blueprint = api?.registry?.get(blueprintId) ?: return null
            return BlueprintMultiblockAdapter(blueprint, controllerLocation, facing)
        }
    }
}
