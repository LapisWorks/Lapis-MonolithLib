package top.mc506lw.monolith.core.model

import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.core.math.Vector3i
import org.joml.Quaternionf
import org.joml.Vector3f

enum class DisplayType {
    BLOCK,
    ITEM
}

data class DisplayEntityData(
    val position: Vector3i,
    val entityType: DisplayType,
    val rotation: Quaternionf,
    val scale: Vector3f,
    val translation: Vector3f,
    val itemStack: ItemStack? = null,
    val blockData: BlockData? = null,
    /** Stable runtime group name used by controllers (defaults to the default group). */
    val group: String = "default"
)
