package top.mc506lw.monolith.feature.editor

import org.bukkit.Bukkit
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.ItemDisplay
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.BlockEntry
import top.mc506lw.monolith.core.model.BoundingBox
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.DisplayType
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.internal.selection.PlayerSelection

/**
 * 从世界选区扫描方块（与展示实体），构建脚手架/成型阶段形状。
 *
 * 坐标系处理：所有方块/实体位置都减去选区 min 角，使 Shape 对齐到 (0,0,0) 原点，
 * 便于在两阶段尺寸校验时直接比较 boundingBox。
 */
object SelectionScanner {

    data class ScanResult(
        val shape: Shape,
        val displayEntities: List<DisplayEntityData>,
        val controllerOffset: Vector3i
    )

    /**
     * 扫描选区。
     * @param captureDisplay 是否同时录制展示实体
     */
    fun captureShape(selection: PlayerSelection, captureDisplay: Boolean): ScanResult? {
        if (!selection.isComplete) return null
        val worldName = selection.worldName ?: return null
        val world = Bukkit.getWorld(worldName) ?: return null
        val min = selection.getMinPos() ?: return null
        val max = selection.getMaxPos() ?: return null
        val pos1 = selection.pos1 ?: return null

        // 方块扫描
        val blocks = mutableListOf<BlockEntry>()
        for (x in min.x..max.x) {
            for (y in min.y..max.y) {
                for (z in min.z..max.z) {
                    val b = world.getBlockAt(x, y, z)
                    if (b.type.isAir || b.type == org.bukkit.Material.STRUCTURE_VOID) continue
                    val relPos = Vector3i(x - min.x, y - min.y, z - min.z)
                    blocks.add(BlockEntry(position = relPos, blockData = b.blockData.clone()))
                }
            }
        }
        // boundingBox 记录选区完整体积（含空气），供 merge 校验"占据空间一致"
        val shape = Shape(
            blocks,
            BoundingBox(
                0, 0, 0,
                max.x - min.x,
                max.y - min.y,
                max.z - min.z
            )
        )

        // controller 偏移：pos1 视为 controller 位置（沿用 WorldEdit 习惯），相对 min
        val controllerOffset = Vector3i(pos1.x - min.x, pos1.y - min.y, pos1.z - min.z)

        // 展示实体扫描
        val displayEntities = if (captureDisplay) {
            captureDisplayEntities(world, min, max)
        } else emptyList()

        return ScanResult(
            shape = shape,
            displayEntities = displayEntities,
            controllerOffset = controllerOffset
        )
    }

    private fun captureDisplayEntities(
        world: org.bukkit.World,
        min: Vector3i,
        max: Vector3i
    ): List<DisplayEntityData> {
        val result = mutableListOf<DisplayEntityData>()
        // 扫描选区附近的 BlockDisplay / ItemDisplay
        for (entity in world.entities) {
            if (entity !is BlockDisplay && entity !is ItemDisplay) continue
            // 跳过非持久实体（避免录制到工地 ghost 等临时展示）
            if (!entity.isPersistent) continue
            val loc = entity.location
            val bx = loc.blockX
            val by = loc.blockY
            val bz = loc.blockZ
            if (bx !in min.x..max.x || by !in min.y..max.y || bz !in min.z..max.z) continue

            val relPos = Vector3i(bx - min.x, by - min.y, bz - min.z)
            val transformation = entity.transformation
            // Preserve the entity's sub-block location; blockX/Y/Z alone loses layout precision.
            val translation = org.joml.Vector3f(transformation.translation).add(
                (loc.x - (bx + 0.5)).toFloat(),
                (loc.y - (by + 0.5)).toFloat(),
                (loc.z - (bz + 0.5)).toFloat()
            )
            when (entity) {
                is BlockDisplay -> {
                    val bd = entity.block
                    result.add(DisplayEntityData(
                        position = relPos,
                        entityType = DisplayType.BLOCK,
                        rotation = transformation.leftRotation,
                        scale = transformation.scale,
                        translation = translation,
                        itemStack = null,
                        blockData = bd.clone()
                    ))
                }
                is ItemDisplay -> {
                    result.add(DisplayEntityData(
                        position = relPos,
                        entityType = DisplayType.ITEM,
                        rotation = transformation.leftRotation,
                        scale = transformation.scale,
                        translation = translation,
                        itemStack = entity.itemStack.clone(),
                        blockData = null
                    ))
                }
            }
        }
        return result
    }
}
