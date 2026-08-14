package top.mc506lw.monolith.feature.preview

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates
import top.mc506lw.monolith.validation.predicate.RotatedPredicate
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GhostColor(val color: Color) {
    CORRECT(Color.LIME),
    MISSING(Color.RED),
    WRONG(Color.ORANGE)
}

data class GhostBlock(
    val worldPos: Vector3i,
    val relativePos: Vector3i,
    val predicate: Predicate,
    val previewBlockData: BlockData,
    var currentState: GhostColor = GhostColor.MISSING,
    var displayEntity: BlockDisplay? = null
)

class PreviewSession(
    val sessionId: String,
    val playerId: UUID,
    val blueprintId: String,
    val blueprint: Blueprint,
    val controllerLocation: Location,
    private val _transform: CoordinateTransform,
    private val renderRadius: Int = 7
) {
    private val log = MonolithLogger.getLogger("PSession")
    constructor(
        sessionId: String,
        playerId: UUID,
        blueprintId: String,
        blueprint: Blueprint,
        controllerLocation: Location,
        facing: Facing = Facing.NORTH,
        renderRadius: Int = 7
    ) : this(
        sessionId = sessionId,
        playerId = playerId,
        blueprintId = blueprintId,
        blueprint = blueprint,
        controllerLocation = controllerLocation,
        _transform = CoordinateTransform(facing),
        renderRadius = renderRadius
    )
    
    val structureId: String get() = blueprintId
    val transform: CoordinateTransform get() = _transform
    private val ghostBlocks = mutableListOf<GhostBlock>()
    private val displayEntities = ConcurrentHashMap<Vector3i, BlockDisplay>()

    /** 16³ 空间索引：按区块格（x/z 与 chunk 对齐）定位幽灵，替代每 tick 全量遍历。 */
    private data class SectionKey(val x: Int, val y: Int, val z: Int)
    private val ghostsBySection = mutableMapOf<SectionKey, MutableList<GhostBlock>>()
    private val ghostsByLayer = mutableMapOf<Int, MutableList<GhostBlock>>()

    /** 状态计数器：幽灵状态只在 updateGhostBlock 中变化，保证计数始终准确。 */
    private var correctCount = 0
    private val correctByLayer = mutableMapOf<Int, Int>()

    private fun sectionKey(pos: Vector3i) = SectionKey(
        Math.floorDiv(pos.x, 16), Math.floorDiv(pos.y, 16), Math.floorDiv(pos.z, 16)
    )
    
    var currentLayer: Int = 0
        private set
    var totalLayers: Int = 0
        private set
    var isActive: Boolean = false
        private set
    var isComplete: Boolean = false
        private set
    var isCancelled: Boolean = false
        private set
    var showAllLayers: Boolean = false
    
    val ghostBlockCount: Int get() = ghostBlocks.size
    val minLayer: Int
    val maxLayer: Int
    
    init {
        initializeGhostBlocks()
        
        val worldYCoords = ghostBlocks.map { it.worldPos.y }.distinct().sorted()
        
        minLayer = worldYCoords.minOrNull() ?: controllerLocation.blockY
        maxLayer = worldYCoords.maxOrNull() ?: controllerLocation.blockY
        totalLayers = worldYCoords.size
        currentLayer = minLayer
    }
    
    private fun initializeGhostBlocks() {
        val shape: Shape = blueprint.scaffoldShape
        val centerOffset = blueprint.meta.controllerOffset
        val rotationSteps = transform.facing.rotationSteps
        
        for (blockEntry in shape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(
                    controllerLocation.blockX,
                    controllerLocation.blockY,
                    controllerLocation.blockZ
                ),
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )
            
            val originalPreview = blockEntry.blockData.clone()
            val rotatedPreview = BlockStateRotator.rotate(originalPreview, rotationSteps)
            
            val predicate = Predicates.strict(blockEntry.blockData)
            val rotatedPredicate = RotatedPredicate(predicate, rotationSteps)
            
            val ghost = GhostBlock(
                worldPos = worldPos,
                relativePos = blockEntry.position,
                predicate = rotatedPredicate,
                previewBlockData = rotatedPreview
            )
            ghostBlocks.add(ghost)
            ghostsBySection.computeIfAbsent(sectionKey(worldPos)) { mutableListOf() }.add(ghost)
            ghostsByLayer.computeIfAbsent(worldPos.y) { mutableListOf() }.add(ghost)
        }
    }
    
    fun start() {
        isActive = true
    }
    
    fun stop() {
        isActive = false
        cleanup()
    }
    
    fun cancel() {
        isCancelled = true
        isActive = false
        cleanup()
    }
    
    fun cleanup() {
        displayEntities.values.forEach { it.remove() }
        displayEntities.clear()
    }
    
    private var updateTick = 0
    
    fun update(player: Player): UpdateResult {
        if (!isActive || isComplete || isCancelled) {
            if (displayEntities.isEmpty() && ghostBlocks.isNotEmpty()) {
                log.warn("session=$sessionId", "update退出(STOPPED)", "isActive" to isActive, "isComplete" to isComplete, "isCancelled" to isCancelled, "ghosts" to ghostBlocks.size)
            }
            return UpdateResult.STOPPED
        }

        val world = controllerLocation.world
        if (world == null || player.world != world) {
            if (displayEntities.isEmpty()) {
                log.warn("session=$sessionId", "world不匹配", "controllerWorld" to controllerLocation?.world?.name, "playerWorld" to player.world.name)
            }
            return UpdateResult.CONTINUE
        }

        val playerLoc = player.location
        val playerBlockX = playerLoc.blockX
        val playerBlockY = playerLoc.blockY
        val playerBlockZ = playerLoc.blockZ

        val radiusSq = renderRadius * renderRadius

        val visiblePositions = mutableSetOf<Vector3i>()

        // 只遍历玩家周围 (renderRadius 内的 16³ 区块格)，不再每 tick 全量扫描百万级幽灵列表
        val minSX = Math.floorDiv(playerBlockX - renderRadius, 16)
        val maxSX = Math.floorDiv(playerBlockX + renderRadius, 16)
        val minSY = Math.floorDiv(playerBlockY - renderRadius, 16)
        val maxSY = Math.floorDiv(playerBlockY + renderRadius, 16)
        val minSZ = Math.floorDiv(playerBlockZ - renderRadius, 16)
        val maxSZ = Math.floorDiv(playerBlockZ + renderRadius, 16)

        for (sx in minSX..maxSX) for (sy in minSY..maxSY) for (sz in minSZ..maxSZ) {
            for (ghost in ghostsBySection[SectionKey(sx, sy, sz)].orEmpty()) {
                if (!showAllLayers && ghost.worldPos.y != currentLayer) continue

                val dx = ghost.worldPos.x - playerBlockX
                val dy = ghost.worldPos.y - playerBlockY
                val dz = ghost.worldPos.z - playerBlockZ
                val distSq = dx * dx + dy * dy + dz * dz

                if (distSq > radiusSq) continue
                visiblePositions.add(ghost.worldPos)
                // 未加载区块跳过：避免主线程同步加载区块；区块加载后下个 tick 再渲染
                if (!world.isChunkLoaded(ghost.worldPos.x shr 4, ghost.worldPos.z shr 4)) continue
                val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)
                updateGhostBlock(ghost, block)
            }
        }

        updateTick++
        if (updateTick <= 3) {
            val sampleGhost = ghostBlocks.firstOrNull()
            val sampleDist = sampleGhost?.let {
                val ddx = (it.worldPos.x - playerBlockX).toDouble()
                val ddy = (it.worldPos.y - playerBlockY).toDouble()
                val ddz = (it.worldPos.z - playerBlockZ).toDouble()
                kotlin.math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
            }
            log.trace("session=$sessionId", "tick信息", "tick" to updateTick, "player" to "($playerBlockX,$playerBlockY,$playerBlockZ)", "controller" to controllerLocation.block, "visible" to visiblePositions.size, "totalGhosts" to ghostBlocks.size, "entities" to displayEntities.size, "nearestDist" to String.format("%.1f", sampleDist ?: 0.0))

            if (visiblePositions.isNotEmpty() && displayEntities.size < visiblePositions.size) {
                log.warn("session=$sessionId", "部分spawn可能失败", "visible" to visiblePositions.size, "entities" to displayEntities.size)
            }
        }
        
        val toRemove = displayEntities.keys.filter { it !in visiblePositions }
        toRemove.forEach { pos ->
            displayEntities[pos]?.remove()
            displayEntities.remove(pos)
        }
        
        val completed = if (showAllLayers) {
            // 计数器判断，避免接近完成时全量遍历幽灵列表
            ghostBlocks.isNotEmpty() && correctCount == ghostBlocks.size
        } else {
            checkLayerCompletion()
        }
        return if (completed) UpdateResult.COMPLETED else UpdateResult.CONTINUE
    }
    
    private fun updateGhostBlock(ghost: GhostBlock, block: Block) {
        val isCorrect = ghost.predicate.testMaterialOnly(
            block.blockData,
            Predicate.PredicateContext(
                position = ghost.relativePos,
                block = block
            )
        )
        
        val isAir = block.type.isAir
        
        val newState = when {
            isAir -> GhostColor.MISSING
            isCorrect -> GhostColor.CORRECT
            else -> GhostColor.WRONG
        }
        
        if (ghost.currentState != newState) {
            val oldState = ghost.currentState
            ghost.currentState = newState
            if (oldState == GhostColor.CORRECT) {
                correctCount--
                correctByLayer.merge(ghost.worldPos.y, -1, Int::plus)?.let {
                    if (it <= 0) correctByLayer.remove(ghost.worldPos.y)
                }
            }
            if (newState == GhostColor.CORRECT) {
                correctCount++
                correctByLayer.merge(ghost.worldPos.y, 1, Int::plus)
            }
        }
        
        val display = getOrCreateDisplayEntity(ghost)
        
        display?.let { d ->
            d.glowColorOverride = newState.color
        }
    }
    
    private var spawnCount = 0
    
    private fun getOrCreateDisplayEntity(ghost: GhostBlock): BlockDisplay? {
        val existing = displayEntities[ghost.worldPos]
        if (existing != null && existing.isValid) {
            return existing
        }
        
        val world = controllerLocation.world ?: return null
        val location = Location(world, ghost.worldPos.x.toDouble(), ghost.worldPos.y.toDouble(), ghost.worldPos.z.toDouble())
        
        return try {
            val display = world.spawn(location, BlockDisplay::class.java) { d ->
                d.block = ghost.previewBlockData
                d.glowColorOverride = ghost.currentState.color
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                
                val scale = 1.0f
                d.transformation = Transformation(
                    Vector3f(0f, 0f, 0f),
                    AxisAngle4f(),
                    Vector3f(scale, scale, scale),
                    AxisAngle4f()
                )
            }
            
            spawnCount++
            if (spawnCount <= 3) {
                log.trace("session=$sessionId", "spawn实体", "count" to spawnCount, "pos" to ghost.worldPos, "block" to ghost.previewBlockData.material, "valid" to display.isValid, "entityId" to display.entityId)
            }

            displayEntities[ghost.worldPos] = display
            display
        } catch (e: Exception) {
            log.warn("session=$sessionId", "生成BlockDisplay失败", "pos" to ghost.worldPos, "error" to e.javaClass.simpleName, "message" to e.message)
            null
        }
    }
    
    private fun checkLayerCompletion(): Boolean {
        val layerBlocks = ghostsByLayer[currentLayer].orEmpty()
        val allCorrect = layerBlocks.isNotEmpty() && (correctByLayer[currentLayer] ?: 0) == layerBlocks.size
        
        if (allCorrect) {
            if (currentLayer < maxLayer) {
                currentLayer++
                displayEntities.values.forEach { it.remove() }
                displayEntities.clear()
            } else {
                isComplete = true
                cleanup()
                return true
            }
        }
        return false
    }
    
    fun getLayerProgress(): Pair<Int, Int> {
        val layerBlocks = ghostsByLayer[currentLayer].orEmpty()
        val correct = correctByLayer[currentLayer] ?: 0
        return Pair(correct, layerBlocks.size)
    }
    
    fun setLayer(layer: Int): Boolean {
        if (layer in minLayer..maxLayer) {
            currentLayer = layer
            displayEntities.values.forEach { it.remove() }
            displayEntities.clear()
            return true
        }
        return false
    }
    
    fun nextLayer(): Boolean {
        return if (currentLayer < maxLayer) {
            currentLayer++
            displayEntities.values.forEach { it.remove() }
            displayEntities.clear()
            true
        } else false
    }
    
    fun prevLayer(): Boolean {
        return if (currentLayer > minLayer) {
            currentLayer--
            displayEntities.values.forEach { it.remove() }
            displayEntities.clear()
            true
        } else false
    }
    
    enum class UpdateResult {
        CONTINUE,
        COMPLETED,
        CONTROLLER_BROKEN,
        STOPPED
    }
}
