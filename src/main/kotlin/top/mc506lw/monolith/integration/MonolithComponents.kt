package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.interfaces.SimpleRebarMultiblock.MultiblockComponent
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.BlockStateRotator

/**
 * The MNB supplies the structure's default components. Controllers may replace
 * any role or position with a component that accepts several vanilla/Rebar blocks.
 */
class MonolithComponents private constructor(
    private val components: MutableMap<Vector3i, MultiblockComponent>
) {
    fun replace(position: Vector3i, component: MultiblockComponent): MonolithComponents {
        components[position] = component
        return this
    }

    fun replaceSlots(blueprint: Blueprint, slotType: String, component: MultiblockComponent): MonolithComponents {
        val center = blueprint.meta.controllerOffset
        blueprint.getSlotPositions(slotType).values.forEach { position ->
            replace(Vector3i(position.x - center.x, position.y - center.y, position.z - center.z), component)
        }
        return this
    }

    fun toMap(): Map<Vector3i, MultiblockComponent> = components.toMap()

    companion object {
        /**
         * Builds one exact vanilla component for every assembled MNB block, **excluding the
         * controller position itself** (that block is the Rebar controller, never a vanilla
         * component). Applies the structure's facing rotation so that `checkFormed` matches
         * the rotated blocks placed during finalize.
         */
        fun fromMNB(blueprint: Blueprint, rotationSteps: Int = 0): MonolithComponents {
            val center = blueprint.meta.controllerOffset
            return MonolithComponents(blueprint.assembledShape.blocks
                .filter { it.position != center }
                .associate { entry ->
                    val relative = Vector3i(
                        entry.position.x - center.x,
                        entry.position.y - center.y,
                        entry.position.z - center.z
                    )
                    val data = if (rotationSteps == 0) {
                        entry.blockData.clone()
                    } else {
                        BlockStateRotator.rotate(entry.blockData.clone(), rotationSteps)
                    }
                    relative to MultiblockComponent.of(data)
                }.toMutableMap())
        }

        /** Convenience factory for a position that accepts any of the supplied Rebar keys. */
        fun rebarAny(vararg keys: NamespacedKey): MultiblockComponent =
            MultiblockComponent.of(emptyList<BlockData>(), keys.toList())
    }
}
