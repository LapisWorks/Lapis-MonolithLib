package top.mc506lw.monolith.validation

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.validation.predicate.Predicate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class ValidationResult {
    data class Success(val matchedBlocks: Int, val totalBlocks: Int) : ValidationResult()
    data class Failure(val errors: List<ValidationError>) : ValidationResult()
    object Pending : ValidationResult()
}

data class ValidationError(
    val position: Location,
    val expected: Predicate,
    val actual: BlockData,
    val message: String
)

data class BlockFixEntry(
    val worldPos: Vector3i,
    val relativePos: Vector3i,
    val targetBlockData: BlockData,
    val currentBlockData: BlockData
)

data class DetailedValidationResult(
    val isComplete: Boolean,
    val matchedCount: Int,
    val totalCount: Int,
    val completionRate: Double,
    val blocksToFix: List<BlockFixEntry>,
    val missingBlocks: List<Vector3i>
) {
    val needsFix: Boolean get() = blocksToFix.isNotEmpty()
}

class ValidationEngine(
    private val blueprint: Blueprint,
    private val transform: CoordinateTransform
) {
    private val lock = ReentrantLock()

    fun checkProgress(scaffoldShape: Shape, worldLocation: Location, facing: Facing): Double {
        val controllerPos = Vector3i(
            worldLocation.blockX,
            worldLocation.blockY,
            worldLocation.blockZ
        )
        val centerOffset = blueprint.meta.controllerOffset
        val world = worldLocation.world ?: return 0.0

        var matched = 0
        val total = scaffoldShape.blocks.size
        if (total == 0) return 1.0

        for (blockEntry in scaffoldShape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )

            if (!world.isChunkLoaded(worldPos.x shr 4, worldPos.z shr 4)) continue

            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            if (blockEntry.effectivePredicate.testMaterialOnly(block.blockData, Predicate.PredicateContext(position = blockEntry.position, block = block))) {
                matched++
            }
        }

        return matched.toDouble() / total
    }

    fun checkLayerProgress(scaffoldShape: Shape, worldLocation: Location, facing: Facing, layerY: Int): Double {
        val controllerPos = Vector3i(
            worldLocation.blockX,
            worldLocation.blockY,
            worldLocation.blockZ
        )
        val centerOffset = blueprint.meta.controllerOffset
        val world = worldLocation.world ?: return 0.0

        val layerBlocks = scaffoldShape.getBlocksInLayer(layerY)
        if (layerBlocks.isEmpty()) return 1.0

        var matched = 0
        for (blockEntry in layerBlocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )

            if (!world.isChunkLoaded(worldPos.x shr 4, worldPos.z shr 4)) continue

            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            if (blockEntry.effectivePredicate.testMaterialOnly(block.blockData, Predicate.PredicateContext(position = blockEntry.position, block = block))) {
                matched++
            }
        }

        return matched.toDouble() / layerBlocks.size
    }

    fun validateAsync(controllerLocation: Location): ValidationResult {
        if (!lock.tryLock()) return ValidationResult.Pending
        return try { validateInternal(controllerLocation) } finally { lock.unlock() }
    }

    fun validateSync(controllerLocation: Location): ValidationResult {
        return lock.withLock { validateInternal(controllerLocation) }
    }

    fun validateDetailed(controllerLocation: Location): DetailedValidationResult {
        return lock.withLock { validateDetailedInternal(controllerLocation) }
    }

    private fun validateInternal(controllerLocation: Location): ValidationResult {
        val controllerPos = Vector3i(controllerLocation.blockX, controllerLocation.blockY, controllerLocation.blockZ)
        val errors = mutableListOf<ValidationError>()
        var matchedCount = 0

        val shape = blueprint.scaffoldShape
        val centerOffset = blueprint.meta.controllerOffset

        for (blockEntry in shape.blocks) {
            val worldPos = transform.toWorldPosition(controllerPos = controllerPos, relativePos = blockEntry.position, centerOffset = centerOffset)
            val blockLocation = Location(controllerLocation.world, worldPos.x.toDouble(), worldPos.y.toDouble(), worldPos.z.toDouble())

            if (!blockLocation.chunk.isLoaded) continue

            val blockData = blockLocation.block.blockData
            val predicate = blockEntry.effectivePredicate
            if (predicate.testMaterialOnly(blockData, Predicate.PredicateContext(position = blockEntry.position, block = blockLocation.block))) {
                matchedCount++
            } else {
                errors.add(ValidationError(position = blockLocation, expected = predicate, actual = blockData, message = "Position ${blockEntry.position} mismatch"))
            }
        }

        return if (errors.isEmpty()) ValidationResult.Success(matchedCount, shape.blocks.size) else ValidationResult.Failure(errors)
    }

    private fun validateDetailedInternal(controllerLocation: Location): DetailedValidationResult {
        val controllerPos = Vector3i(controllerLocation.blockX, controllerLocation.blockY, controllerLocation.blockZ)
        val shape = blueprint.scaffoldShape
        val centerOffset = blueprint.meta.controllerOffset

        var matchedCount = 0
        val blocksToFix = mutableListOf<BlockFixEntry>()
        val missingBlocks = mutableListOf<Vector3i>()

        for (blockEntry in shape.blocks) {
            val worldPos = transform.toWorldPosition(controllerPos = controllerPos, relativePos = blockEntry.position, centerOffset = centerOffset)
            val blockLocation = Location(controllerLocation.world, worldPos.x.toDouble(), worldPos.y.toDouble(), worldPos.z.toDouble())

            if (!blockLocation.chunk.isLoaded) continue

            val actualBlockData = blockLocation.block.blockData
            val predicate = blockEntry.effectivePredicate
            val context = Predicate.PredicateContext(position = blockEntry.position, block = blockLocation.block)

            if (predicate.testMaterialOnly(actualBlockData, context)) {
                matchedCount++
                if (!predicate.test(actualBlockData, context)) {
                    blocksToFix.add(BlockFixEntry(worldPos = worldPos, relativePos = blockEntry.position, targetBlockData = blockEntry.blockData, currentBlockData = actualBlockData))
                }
            } else {
                missingBlocks.add(worldPos)
            }
        }

        val totalCount = shape.blocks.size
        val completionRate = if (totalCount > 0) matchedCount.toDouble() / totalCount else 0.0

        return DetailedValidationResult(
            isComplete = matchedCount == totalCount,
            matchedCount = matchedCount,
            totalCount = totalCount,
            completionRate = completionRate,
            blocksToFix = blocksToFix,
            missingBlocks = missingBlocks
        )
    }
}
