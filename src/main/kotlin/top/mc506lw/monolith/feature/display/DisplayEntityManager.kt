package top.mc506lw.monolith.feature.display

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.Transformation
import top.mc506lw.monolith.core.math.Matrix3x3
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.common.MonolithLogger
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DisplayEntityManager {

    private val log = MonolithLogger.getLogger("DEManager")

    private val siteDisplays = ConcurrentHashMap<UUID, MutableList<Display>>()

    fun spawnVirtualViewFromEntities(
        siteId: UUID,
        entities: List<DisplayEntityData>,
        anchorLocation: Location,
        facing: Facing,
        controllerOffset: Vector3i,
        displayOffset: Vector3f = Vector3f(),
        scaffoldWorldPositions: List<Vector3i> = emptyList()
    ): Int {
        val world = anchorLocation.world ?: return 0
        val rotationSteps = facing.rotationSteps
        val rotationMatrix = when (rotationSteps) {
            0 -> Matrix3x3.IDENTITY
            1 -> Matrix3x3.rotationY90()
            2 -> Matrix3x3.rotationY180()
            3 -> Matrix3x3.rotationY270()
            else -> Matrix3x3.IDENTITY
        }
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )

        val effectiveOffset = if (scaffoldWorldPositions.isNotEmpty() && entities.isNotEmpty()) {
            val scMinX = scaffoldWorldPositions.minOf { it.x }.toDouble()
            val scMinY = scaffoldWorldPositions.minOf { it.y }.toDouble()
            val scMinZ = scaffoldWorldPositions.minOf { it.z }.toDouble()

            var deMinX = Double.MAX_VALUE
            var deMinY = Double.MAX_VALUE
            var deMinZ = Double.MAX_VALUE
            for (e in entities) {
                val ex = controllerPos.x + rotationMatrix.m00 * e.translation.x + rotationMatrix.m02 * e.translation.z
                val ey = controllerPos.y + e.translation.y.toDouble()
                val ez = controllerPos.z + rotationMatrix.m20 * e.translation.x + rotationMatrix.m22 * e.translation.z
                val hx = kotlin.math.abs(rotationMatrix.m00 * e.scale.x / 2 + rotationMatrix.m02 * e.scale.z / 2)
                val hy = e.scale.y / 2
                val hz = kotlin.math.abs(rotationMatrix.m20 * e.scale.x / 2 + rotationMatrix.m22 * e.scale.z / 2)
                deMinX = minOf(deMinX, ex - hx)
                deMinY = minOf(deMinY, ey - hy)
                deMinZ = minOf(deMinZ, ez - hz)
            }

            val autoX = (scMinX - deMinX).toFloat()
            val autoY = (scMinY - deMinY).toFloat()
            val autoZ = (scMinZ - deMinZ).toFloat()

            log.debug("align", "自动对齐计算", "scaffoldMin" to "($scMinX,$scMinY,$scMinZ)", "deMin" to "($deMinX,$deMinY,$deMinZ)", "auto" to "($autoX,$autoY,$autoZ)", "userConfig" to displayOffset)

            Vector3f(autoX + displayOffset.x, autoY + displayOffset.y, autoZ + displayOffset.z)
        } else {
            displayOffset
        }

        log.debug("site=$siteId", "开始生成虚拟视图", "entityCount" to entities.size, "controllerPos" to controllerPos, "facing" to facing, "offset" to effectiveOffset)

        val displays = mutableListOf<Display>()
        var successCount = 0
        var failCount = 0

        for ((index, entity) in entities.withIndex()) {
            val rotTransX = rotationMatrix.m00 * entity.translation.x + rotationMatrix.m02 * entity.translation.z
            val rotTransY = entity.translation.y.toDouble()
            val rotTransZ = rotationMatrix.m20 * entity.translation.x + rotationMatrix.m22 * entity.translation.z

            val worldX = controllerPos.x + rotTransX + effectiveOffset.x.toDouble()
            val worldY = controllerPos.y + rotTransY + effectiveOffset.y.toDouble()
            val worldZ = controllerPos.z + rotTransZ + effectiveOffset.z.toDouble()

            val location = Location(world, worldX, worldY, worldZ)

            if (index < 3) {
                log.trace("site=$siteId", "实体生成详情", "index" to index, "pos" to entity.position, "trans" to entity.translation, "scale" to entity.scale, "spawnLoc" to "($worldX,$worldY,$worldZ)")
            }

            try {
                val display = when (entity.entityType) {
                    top.mc506lw.monolith.core.model.DisplayType.BLOCK -> {
                        if (entity.blockData == null) continue
                        val rotatedBlockData = BlockStateRotator.rotate(entity.blockData.clone(), rotationSteps)
                        world.spawn(location, BlockDisplay::class.java) { d ->
                            d.block = rotatedBlockData
                            d.transformation = buildTransformation(entity, rotationSteps)
                            d.isPersistent = false
                            d.brightness = Display.Brightness(15, 15)
                        }
                    }
                    top.mc506lw.monolith.core.model.DisplayType.ITEM -> {
                        val itemStackCopy = entity.itemStack?.clone()
                        world.spawn(location, ItemDisplay::class.java) { d ->
                            if (itemStackCopy != null) d.setItemStack(itemStackCopy)
                            d.transformation = buildTransformation(entity, rotationSteps)
                            d.isPersistent = false
                            d.brightness = Display.Brightness(15, 15)
                        }
                    }
                }
                displays.add(display)
                successCount++
            } catch (e: Exception) {
                failCount++
                if (failCount <= 3) {
                    log.warn("site=$siteId", "实体生成失败", "pos" to "($worldX,$worldY,$worldZ)", "error" to e.message)
                }
            }
        }

        if (displays.isNotEmpty()) {
            siteDisplays[siteId] = displays
        }

        log.debug("site=$siteId", "虚拟视图生成完成", "success" to successCount, "failed" to failCount, "total" to entities.size)

        return successCount
    }

    private fun buildTransformation(entity: DisplayEntityData, rotationSteps: Int): Transformation {
        val leftRotation = applyFacingRotation(entity.rotation, rotationSteps)

        return Transformation(
            Vector3f(0f, 0f, 0f),
            leftRotation,
            entity.scale,
            Quaternionf()
        )
    }

    private fun applyFacingRotation(originalRotation: Quaternionf, rotationSteps: Int): Quaternionf {
        if (rotationSteps == 0) return Quaternionf(originalRotation)

        val facingRotation = when (rotationSteps) {
            1 -> Quaternionf().rotateY(kotlin.math.PI.toFloat() / 2f)
            2 -> Quaternionf().rotateY(kotlin.math.PI.toFloat())
            3 -> Quaternionf().rotateY(-kotlin.math.PI.toFloat() / 2f)
            else -> Quaternionf()
        }

        return Quaternionf(facingRotation).mul(originalRotation)
    }

    fun spawnVirtualView(
        siteId: UUID,
        assembledShape: Shape,
        anchorLocation: Location,
        facing: Facing,
        controllerOffset: Vector3i
    ): Int {
        val world = anchorLocation.world ?: return 0
        val transform = CoordinateTransform(facing)
        val rotationSteps = facing.rotationSteps
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )

        log.debug("site=$siteId", "开始生成虚拟视图", "blockCount" to assembledShape.blocks.size, "controllerPos" to controllerPos, "facing" to facing, "offset" to controllerOffset)

        val displays = mutableListOf<Display>()
        var successCount = 0
        var failCount = 0

        for ((index, entry) in assembledShape.blocks.withIndex()) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = entry.position,
                centerOffset = controllerOffset
            )

            if (!world.isChunkLoaded(worldPos.x shr 4, worldPos.z shr 4)) continue

            val rotatedBlockData = BlockStateRotator.rotate(entry.blockData.clone(), rotationSteps)

            if (index < 5) {
                log.trace("site=$siteId", "方块生成详情", "index" to index, "pos" to entry.position, "material" to entry.blockData.material, "worldPos" to worldPos)
            }

            val location = Location(
                world,
                worldPos.x.toDouble(),
                worldPos.y.toDouble(),
                worldPos.z.toDouble()
            )

            try {
                val display = world.spawn(location, BlockDisplay::class.java) { d ->
                    d.block = rotatedBlockData
                    d.isPersistent = false
                    d.brightness = Display.Brightness(15, 15)
                }
                displays.add(display)
                successCount++
            } catch (e: Exception) {
                failCount++
                if (failCount <= 3) {
                    log.warn("site=$siteId", "展示实体生成失败", "pos" to worldPos, "error" to e.message)
                }
            }
        }

        if (displays.isNotEmpty()) {
            siteDisplays[siteId] = displays
        }

        log.debug("site=$siteId", "虚拟视图生成完成", "success" to successCount, "failed" to failCount, "total" to assembledShape.blocks.size)

        return successCount
    }

    fun removeAllForSite(siteId: UUID): Int {
        log.debug("site=$siteId", "开始移除展示实体")
        val displays = siteDisplays.remove(siteId)

        if (displays == null) {
            log.warn("site=$siteId", "未找到展示实体数据", "registeredSites" to siteDisplays.keys)
            return 0
        }

        log.debug("site=$siteId", "找到展示实体", "count" to displays.size)

        var removed = 0
        for (display in displays) {
            try {
                if (display.isValid) {
                    display.remove()
                    removed++
                } else {
                    log.trace("site=$siteId", "实体已失效，跳过")
                }
            } catch (e: Exception) {
                log.warn("site=$siteId", "删除实体失败", "error" to e.message)
            }
        }

        log.debug("site=$siteId", "移除完成", "removed" to removed, "total" to displays.size)

        return removed
    }

    fun hasDisplaysForSite(siteId: UUID): Boolean = siteDisplays.containsKey(siteId)

    fun cleanup() {
        for ((_, displays) in siteDisplays) {
            for (display in displays) {
                try {
                    display.remove()
                } catch (_: Exception) {}
            }
        }
        siteDisplays.clear()
    }
}
