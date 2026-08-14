package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.common.MonolithLogger

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val boundingBox: BoundingBox
)

data class ValidationError(
    val type: ErrorType,
    val message: String,
    val positions: List<Vector3i> = emptyList()
)

enum class ErrorType {
    HEIGHT_LIMIT,
    UNBREAKABLE_BLOCK,
    SITE_CONFLICT,
    WORLD_BORDER
}

data class ValidationWarning(
    val type: WarningType,
    val message: String,
    val positions: List<Vector3i> = emptyList()
)

enum class WarningType {
    NEARBY_SITE,
    LIQUID_BLOCK,
    REPLACEABLE_BLOCK
}

data class BoundingBox(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val depth: Int get() = maxZ - minZ + 1
    
    val center: Vector3i get() = Vector3i(
        (minX + maxX) / 2,
        (minY + maxY) / 2,
        (minZ + maxZ) / 2
    )
    
    fun contains(pos: Vector3i): Boolean {
        return pos.x in minX..maxX && pos.y in minY..maxY && pos.z in minZ..maxZ
    }
    
    fun getAllPositions(): Sequence<Vector3i> = sequence {
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    yield(Vector3i(x, y, z))
                }
            }
        }
    }
}

object BuildSiteValidator {

    private val logger = MonolithLogger.getLogger("Validator")

    private val UNBREAKABLE_MATERIALS = setOf(
        Material.BEDROCK,
        Material.BARRIER,
        Material.STRUCTURE_VOID,
        Material.STRUCTURE_BLOCK,
        Material.COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.JIGSAW,
        Material.MOVING_PISTON,
        Material.END_PORTAL,
        Material.END_GATEWAY,
        Material.NETHER_PORTAL,
        Material.SPAWNER
    )
    
    private val REPLACEABLE_MATERIALS = setOf(
        Material.AIR,
        Material.CAVE_AIR,
        Material.VOID_AIR,
        Material.WATER,
        Material.LAVA,
        Material.SHORT_GRASS,
        Material.TALL_GRASS,
        Material.SEAGRASS,
        Material.TALL_SEAGRASS,
        Material.SNOW,
        Material.DEAD_BUSH,
        Material.FERN,
        Material.LARGE_FERN
    )
    
    fun validate(
        blueprint: Blueprint,
        anchorLocation: Location,
        facing: Facing
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        
        val world = anchorLocation.world
        if (world == null) {
            errors.add(ValidationError(
                ErrorType.WORLD_BORDER,
                "世界不存在"
            ))
            return ValidationResult(false, errors, warnings, BoundingBox(0, 0, 0, 0, 0, 0))
        }
        
        val transform = CoordinateTransform(facing)
        val centerOffset = blueprint.meta.controllerOffset
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )
        
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        
        val blockPositions = mutableListOf<Vector3i>()

        val shapeToValidate = blueprint.scaffoldShape
        logger.trace("validate", "开始验证", "blueprint" to blueprint.id, "shapeType" to if (shapeToValidate === blueprint.assembledShape) "assembled" else "scaffold")

        for (blockEntry in shapeToValidate.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )
            
            blockPositions.add(worldPos)
            minX = minOf(minX, worldPos.x)
            minY = minOf(minY, worldPos.y)
            minZ = minOf(minZ, worldPos.z)
            maxX = maxOf(maxX, worldPos.x)
            maxY = maxOf(maxY, worldPos.y)
            maxZ = maxOf(maxZ, worldPos.z)
        }
        
        val boundingBox = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        
        val heightLimitErrors = checkHeightLimit(world, boundingBox)
        errors.addAll(heightLimitErrors)
        
        val unbreakableErrors = checkUnbreakableBlocks(world, blockPositions)
        errors.addAll(unbreakableErrors)
        
        val siteConflicts = checkSiteConflicts(world, boundingBox, anchorLocation)
        errors.addAll(siteConflicts)
        
        val nearbyWarnings = checkNearbySites(world, boundingBox, anchorLocation)
        warnings.addAll(nearbyWarnings)
        
        val liquidWarnings = checkLiquidBlocks(world, blockPositions)
        warnings.addAll(liquidWarnings)
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            boundingBox = boundingBox
        )
    }
    
    /**
     * O(1) 计算旋转后的世界 AABB：只变换 Shape 已缓存包围盒的 8 个角点，
     * 不遍历方块。用于预览旋转的高频路径（每次滚轮一格）。
     */
    fun computeBoundingBox(blueprint: Blueprint, anchorLocation: Location, facing: Facing): BoundingBox {
        val transform = CoordinateTransform(facing)
        val centerOffset = blueprint.meta.controllerOffset
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )
        val b = blueprint.scaffoldShape.boundingBox

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE

        for (cx in intArrayOf(b.minX, b.maxX)) {
            for (cy in intArrayOf(b.minY, b.maxY)) {
                for (cz in intArrayOf(b.minZ, b.maxZ)) {
                    val p = transform.toWorldPosition(controllerPos, Vector3i(cx, cy, cz), centerOffset)
                    minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
                    maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
                }
            }
        }
        return BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * 轻量校验（预览旋转用）：AABB + 高度限制 + 站点冲突 + 附近站点，
     * 全部基于缓存 AABB，无逐方块世界读取。液体/不可破坏等逐块检查留到确认时全量校验。
     */
    fun validateLight(blueprint: Blueprint, anchorLocation: Location, facing: Facing): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        val world = anchorLocation.world
        if (world == null) {
            errors.add(ValidationError(
                ErrorType.WORLD_BORDER,
                "世界不存在"
            ))
            return ValidationResult(false, errors, warnings, BoundingBox(0, 0, 0, 0, 0, 0))
        }

        val boundingBox = computeBoundingBox(blueprint, anchorLocation, facing)

        errors.addAll(checkHeightLimit(world, boundingBox))
        errors.addAll(checkSiteConflicts(world, boundingBox, anchorLocation))
        warnings.addAll(checkNearbySites(world, boundingBox, anchorLocation))

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            boundingBox = boundingBox
        )
    }
    
    private fun checkHeightLimit(world: World, box: BoundingBox): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight - 1
        
        val invalidPositions = mutableListOf<Vector3i>()
        
        if (box.minY < minHeight) {
            for (y in box.minY until minHeight) {
                for (x in box.minX..box.maxX) {
                    for (z in box.minZ..box.maxZ) {
                        invalidPositions.add(Vector3i(x, y, z))
                    }
                }
            }
        }
        
        if (box.maxY > maxHeight) {
            for (y in maxHeight + 1..box.maxY) {
                for (x in box.minX..box.maxX) {
                    for (z in box.minZ..box.maxZ) {
                        invalidPositions.add(Vector3i(x, y, z))
                    }
                }
            }
        }
        
        if (invalidPositions.isNotEmpty()) {
            val underCount = maxOf(0, minHeight - box.minY)
            val overCount = maxOf(0, box.maxY - maxHeight)
            
            errors.add(ValidationError(
                ErrorType.HEIGHT_LIMIT,
                "结构超出世界高度限制! (世界范围: $minHeight ~ $maxHeight, 结构范围: ${box.minY} ~ ${box.maxY})" +
                    if (underCount > 0) " 下方超出 $underCount 格" else "" +
                    if (overCount > 0) " 上方超出 $overCount 格" else "",
                invalidPositions.take(10)
            ))
        }
        
        return errors
    }
    
    private fun checkUnbreakableBlocks(world: World, positions: List<Vector3i>): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val unbreakablePositions = mutableMapOf<Material, MutableList<Vector3i>>()
        
        for (pos in positions) {
            // 未加载区块跳过：避免百万级结构在校验时主线程逐个同步加载区块
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            
            if (block.type in UNBREAKABLE_MATERIALS) {
                unbreakablePositions.getOrPut(block.type) { mutableListOf() }.add(pos)
            }
        }
        
        for ((material, positions) in unbreakablePositions) {
            errors.add(ValidationError(
                ErrorType.UNBREAKABLE_BLOCK,
                "检测到 ${positions.size} 个不可破坏方块: ${material.name}",
                positions.take(5)
            ))
        }
        
        return errors
    }
    
    private fun checkSiteConflicts(world: World, box: BoundingBox, anchorLocation: Location): List<ValidationError> {
        val conflict = BuildSiteRegistry.overlaps(world.name, box)
        return if (conflict != null) {
            listOf(ValidationError(
                ErrorType.SITE_CONFLICT,
                "与现有工地冲突: ${conflict.blueprintId}",
                listOf(Vector3i(conflict.block.x, conflict.block.y, conflict.block.z))
            ))
        } else emptyList()
    }
    
    private fun checkNearbySites(world: World, box: BoundingBox, anchorLocation: Location): List<ValidationWarning> {
        val warnings = mutableListOf<ValidationWarning>()
        val nearbySites = mutableListOf<String>()
        val checkRadius = 16
        
        for (site in BuildSiteRegistry.all()) {
            if (site.block.world.name != world.name) continue
            val siteBox = site.boundingBox()
            
            val nearX = box.maxX + checkRadius >= siteBox.minX && box.minX - checkRadius <= siteBox.maxX
            val nearY = box.maxY + checkRadius >= siteBox.minY && box.minY - checkRadius <= siteBox.maxY
            val nearZ = box.maxZ + checkRadius >= siteBox.minZ && box.minZ - checkRadius <= siteBox.maxZ
            
            if (nearX && nearY && nearZ) {
                nearbySites.add(site.blueprintId ?: "unknown")
            }
        }
        
        if (nearbySites.isNotEmpty()) {
            warnings.add(ValidationWarning(
                WarningType.NEARBY_SITE,
                "附近有 ${nearbySites.size} 个工地: ${nearbySites.joinToString(", ")}"
            ))
        }
        
        return warnings
    }
    
    private fun checkLiquidBlocks(world: World, positions: List<Vector3i>): List<ValidationWarning> {
        val warnings = mutableListOf<ValidationWarning>()
        val liquidPositions = mutableListOf<Vector3i>()
        
        for (pos in positions) {
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (block.type == Material.WATER || block.type == Material.LAVA) {
                liquidPositions.add(pos)
            }
        }
        
        if (liquidPositions.isNotEmpty()) {
            warnings.add(ValidationWarning(
                WarningType.LIQUID_BLOCK,
                "检测到 ${liquidPositions.size} 个液体方块位置",
                liquidPositions.take(5)
            ))
        }
        
        return warnings
    }
}
