package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Quaternionf
import org.joml.Vector3f
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.validation.AutoFixer
import top.mc506lw.monolith.validation.BlockFixEntry
import top.mc506lw.monolith.validation.DetailedValidationResult
import top.mc506lw.monolith.validation.ValidationEngine
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates
import top.mc506lw.monolith.validation.predicate.RotatedPredicate
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BuildSiteState {
    BUILDING,
    AWAITING_CORE,
    VIRTUAL
}

object BlueprintItem {

    private val BLUEPRINT_KEY = NamespacedKey("monolithlib", "blueprint_id")
    private val BLUEPRINT_FACING_KEY = NamespacedKey("monolithlib", "blueprint_facing")

    fun create(blueprintId: String): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta ?: return item

        meta.displayName(
            Component.text("蓝图: ", NamedTextColor.AQUA).append(Component.text(blueprintId, NamedTextColor.WHITE))
        )
        meta.lore(listOf(
            Component.text("右键放置以创建工地", NamedTextColor.GRAY),
            Component.text("蓝图ID: ", NamedTextColor.GRAY).append(Component.text(blueprintId, NamedTextColor.YELLOW))
        ))

        meta.persistentDataContainer.set(BLUEPRINT_KEY, PersistentDataType.STRING, blueprintId)
        meta.isUnbreakable = true

        try {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.INFINITY, 1, true)
        } catch (_: Exception) {}

        item.itemMeta = meta
        return item
    }

    fun getBlueprintId(item: ItemStack): String? {
        return item.itemMeta?.persistentDataContainer?.get(BLUEPRINT_KEY, PersistentDataType.STRING)
    }

    fun isBlueprintItem(item: ItemStack): Boolean {
        return getBlueprintId(item) != null
    }

    fun setFacing(item: ItemStack, facing: Facing) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(BLUEPRINT_FACING_KEY, PersistentDataType.STRING, facing.name)
        item.itemMeta = meta
    }

    fun getFacing(item: ItemStack): Facing? {
        val name = item.itemMeta?.persistentDataContainer?.get(BLUEPRINT_FACING_KEY, PersistentDataType.STRING)
        return name?.let { try { Facing.valueOf(it) } catch (e: Exception) { null } }
    }
}

data class SiteGhostBlock(
    val worldPos: Vector3i,
    val relativePos: Vector3i,
    val isCore: Boolean,
    val predicate: Predicate,
    val previewBlockData: BlockData
)

class BuildSite(
    val id: UUID,
    var blueprint: Blueprint,
    val anchorLocation: Location,
    val facing: Facing,
    initialLayer: Int = 0,
    initialPlacedBlocks: Set<Vector3i> = emptySet(),
    initialState: BuildSiteState = BuildSiteState.BUILDING
) {
    private val logger = MonolithLogger.getLogger("Site")

    val blueprintId: String get() = blueprint.id

    val transform: CoordinateTransform = CoordinateTransform(facing)

    var currentLayer: Int = initialLayer
        private set

    var state: BuildSiteState = initialState
        private set

    var placedBlocks: MutableSet<Vector3i> = ConcurrentHashMap.newKeySet<Vector3i>()
        private set

    val allGhostBlocks: List<SiteGhostBlock>
    val coreWorldPos: Vector3i
    val coreRebarKey: NamespacedKey? = blueprint.controllerRebarKey

    val boundingMinX: Int
    val boundingMinY: Int
    val boundingMinZ: Int
    val boundingMaxX: Int
    val boundingMaxY: Int
    val boundingMaxZ: Int

    val layerYLevels: List<Int>
    var totalLayers: Int = 0
        private set

    var isCompleted: Boolean = false
        private set

    private val ghostDisplayEntities = ConcurrentHashMap<Vector3i, BlockDisplay>()
    private val playerRenderCache = ConcurrentHashMap<UUID, Set<Vector3i>>()
    private var coreGlassPlaced: Boolean = false
    private var coreItemDisplay: ItemDisplay? = null
    private var displayGroup: top.mc506lw.monolith.feature.display.DisplayEntityGroup? = null

    val backupData: MutableMap<Vector3i, BlockData> = ConcurrentHashMap()

    private val validationEngine: ValidationEngine by lazy {
        ValidationEngine(blueprint, transform)
    }

    init {
        placedBlocks.addAll(initialPlacedBlocks)

        val shape: Shape = blueprint.scaffoldShape
        val centerOffset = blueprint.meta.controllerOffset
        val rotationSteps = facing.rotationSteps
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )

        coreWorldPos = transform.toWorldPosition(
            controllerPos = controllerPos,
            relativePos = centerOffset,
            centerOffset = centerOffset
        )

        val blocks = mutableListOf<SiteGhostBlock>()

        for (blockEntry in shape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )

            val originalPreview = blockEntry.blockData.clone()
            val rotatedPreview = BlockStateRotator.rotate(originalPreview, rotationSteps)

            val isCore = (blockEntry.position == centerOffset)

            val predicate = if (isCore && coreRebarKey != null) {
                Predicates.rebar(coreRebarKey, rotatedPreview)
            } else {
                Predicates.material(blockEntry.blockData.material)
            }

            val rotatedPredicate = RotatedPredicate(predicate, rotationSteps)

            blocks.add(SiteGhostBlock(
                worldPos = worldPos,
                relativePos = blockEntry.position,
                isCore = isCore,
                predicate = rotatedPredicate,
                previewBlockData = if (isCore && coreRebarKey != null) {
                    Bukkit.createBlockData(Material.FURNACE)
                } else {
                    rotatedPreview
                }
            ))
        }

        allGhostBlocks = blocks.toList()

        if (blocks.isEmpty()) {
            boundingMinX = anchorLocation.blockX
            boundingMinY = anchorLocation.blockY
            boundingMinZ = anchorLocation.blockZ
            boundingMaxX = anchorLocation.blockX
            boundingMaxY = anchorLocation.blockY
            boundingMaxZ = anchorLocation.blockZ
        } else {
            boundingMinX = blocks.minOf { it.worldPos.x }
            boundingMinY = blocks.minOf { it.worldPos.y }
            boundingMinZ = blocks.minOf { it.worldPos.z }
            boundingMaxX = blocks.maxOf { it.worldPos.x }
            boundingMaxY = blocks.maxOf { it.worldPos.y }
            boundingMaxZ = blocks.maxOf { it.worldPos.z }
        }

        layerYLevels = allGhostBlocks.map { it.worldPos.y }.distinct().sorted()
        totalLayers = layerYLevels.size

        currentLayer = when {
            layerYLevels.isEmpty() -> 0
            initialLayer >= layerYLevels.size -> 0
            else -> initialLayer
        }
    }

    fun getCurrentLayerY(): Int? = layerYLevels.getOrNull(currentLayer)

    fun getCurrentLayerBlocks(): List<SiteGhostBlock> {
        val layerY = getCurrentLayerY() ?: return emptyList()
        return allGhostBlocks.filter { it.worldPos.y == layerY && !it.isCore }
    }

    fun getCoreBlock(): SiteGhostBlock? = allGhostBlocks.find { it.isCore }

    fun renderForPlayer(player: Player) {
        if (isCompleted) return
        if (state == BuildSiteState.VIRTUAL) return

        val world = anchorLocation.world
        if (world == null || player.world != world) return

        if (state == BuildSiteState.AWAITING_CORE) {
            renderCoreMarker(world)
            return
        }

        val playerLoc = player.location
        val radiusSq = RENDER_RADIUS * RENDER_RADIUS
        val currentLayerY = getCurrentLayerY() ?: return

        placeCoreGlassIfAbsent(world)

        val visiblePositions = mutableSetOf<Vector3i>()

        for (ghost in allGhostBlocks) {
            if (ghost.worldPos.y != currentLayerY) continue
            if (ghost.isCore) continue

            val dx = ghost.worldPos.x - playerLoc.blockX
            val dy = ghost.worldPos.y - playerLoc.blockY
            val dz = ghost.worldPos.z - playerLoc.blockZ
            val distSq = dx.toLong() * dx + dy.toLong() * dy + dz.toLong() * dz

            if (distSq <= radiusSq.toLong()) {
                visiblePositions.add(ghost.worldPos)
                updateGhostDisplay(world, ghost)
            }
        }

        cleanupStaleEntities(visiblePositions)
        playerRenderCache[player.uniqueId] = visiblePositions
    }

    private fun placeCoreGlassIfAbsent(world: org.bukkit.World) {
        if (coreGlassPlaced) return

        val block = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)
        if (!block.type.isAir && !block.isReplaceable) return

        block.type = Material.RED_STAINED_GLASS
        coreGlassPlaced = true
    }

    private fun updateGhostDisplay(world: org.bukkit.World, ghost: SiteGhostBlock) {
        val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)

        val context = Predicate.PredicateContext(position = ghost.relativePos, block = block)

        val isFullyCorrect = ghost.predicate.test(block.blockData, context)
        val isMaterialCorrect = ghost.predicate.testMaterialOnly(block.blockData, context)
        val isAir = block.type.isAir

        val glowColor = when {
            isFullyCorrect -> Color.LIME
            isAir -> Color.RED
            isMaterialCorrect -> Color.YELLOW
            else -> Color.RED
        }

        val existing = ghostDisplayEntities[ghost.worldPos]
        if (existing != null && existing.isValid) {
            existing.glowColorOverride = glowColor
            return
        }

        existing?.remove()

        val location = Location(world,
            ghost.worldPos.x.toDouble(),
            ghost.worldPos.y.toDouble(),
            ghost.worldPos.z.toDouble())

        try {
            val display = world.spawn(location, BlockDisplay::class.java) { d ->
                d.block = ghost.previewBlockData
                d.isGlowing = true
                d.glowColorOverride = glowColor
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)

                val scale = 0.5f
                d.transformation = Transformation(
                    Vector3f(0.25f, 0.25f, 0.25f),
                    AxisAngle4f(),
                    Vector3f(scale, scale, scale),
                    AxisAngle4f()
                )
            }
            ghostDisplayEntities[ghost.worldPos] = display
        } catch (_: Exception) {}
    }

    private fun cleanupStaleEntities(visiblePositions: Set<Vector3i>) {
        val toRemove = ghostDisplayEntities.keys.filter { it !in visiblePositions }
        for (pos in toRemove) {
            ghostDisplayEntities[pos]?.remove()
            ghostDisplayEntities.remove(pos)
        }
    }

    fun checkLayerCompletion(): Boolean {
        if (state == BuildSiteState.AWAITING_CORE || state == BuildSiteState.VIRTUAL || isCompleted) return false

        val currentLayerY = getCurrentLayerY() ?: return false
        val layerBlocks = getCurrentLayerBlocks()

        if (layerBlocks.isEmpty()) return false

        val world = anchorLocation.world ?: return false

        for (ghost in layerBlocks) {
            if (ghost.isCore) continue

            val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)
            if (!block.chunk.isLoaded) return false
            if (block.type.isAir) return false

            val context = Predicate.PredicateContext(position = ghost.relativePos, block = block)
            if (!ghost.predicate.testMaterialOnly(block.blockData, context)) return false
        }

        return true
    }

    fun advanceToNextIncompleteLayer(): Int {
        if (isLastLayer()) return 0

        var advancedCount = 0

        while (currentLayer < totalLayers - 1) {
            currentLayer++
            advancedCount++
            clearCurrentLayerRenderings()

            if (!isLayerFullyPlaced(currentLayer)) {
                break
            }
        }

        return advancedCount
    }

    fun isLastLayer(): Boolean = currentLayer >= totalLayers - 1

    fun isLayerFullyPlaced(layerIndex: Int): Boolean {
        val layerY = layerYLevels.getOrNull(layerIndex) ?: return true

        val layerBlocks = allGhostBlocks.filter { it.worldPos.y == layerY && !it.isCore }
        if (layerBlocks.isEmpty()) return true

        val world = anchorLocation.world ?: return false

        for (ghost in layerBlocks) {
            val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)

            if (!block.chunk.isLoaded) return false
            if (block.type.isAir) return false

            val context = Predicate.PredicateContext(position = ghost.relativePos, block = block)
            if (!ghost.predicate.testMaterialOnly(block.blockData, context)) return false
        }

        return true
    }

    fun checkIfAllNonCoreLayersComplete(): Boolean {
        for (i in layerYLevels.indices) {
            if (!isLayerFullyPlaced(i)) return false
        }
        return true
    }

    fun findFirstIncompleteLayer(): Int {
        for (i in layerYLevels.indices) {
            if (!isLayerFullyPlaced(i)) return i
        }
        return -1
    }

    fun enterAwaitingCore() {
        if (state == BuildSiteState.AWAITING_CORE) return

        state = BuildSiteState.AWAITING_CORE
        clearCurrentLayerRenderings()
        removeCoreGlass()

        val world = anchorLocation.world
        if (world != null) {
            spawnCoreItemDisplay(world)
        }
    }

    fun forceEnterAwaitingCore() {
        if (state == BuildSiteState.AWAITING_CORE) return

        val lastLayerIndex = (totalLayers - 1).coerceAtLeast(0)
        currentLayer = lastLayerIndex

        clearCurrentLayerRenderings()
        removeCoreGlass()

        state = BuildSiteState.AWAITING_CORE

        spawnCoreItemDisplay(anchorLocation.world)
    }

    fun advanceToLayer(targetLayer: Int) {
        if (targetLayer < 0 || targetLayer >= totalLayers) return
        if (targetLayer <= currentLayer) return

        currentLayer = targetLayer
        clearCurrentLayerRenderings()
    }

    fun transitionToVirtual() {
        if (state == BuildSiteState.VIRTUAL) return

        val world = anchorLocation.world ?: return

        logger.info("site=$id", "开始虚拟化转换", "blueprint" to blueprintId)

        backupData.clear()
        for (ghost in allGhostBlocks) {
            val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)
            backupData[ghost.worldPos] = block.blockData.clone()
        }

        for (ghost in allGhostBlocks) {
            val block = world.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z)
            block.setType(Material.STRUCTURE_VOID, false)
        }

        removeCoreItemDisplay()
        clearCurrentLayerRenderings()
        removeCoreGlass()

        placeAnchorAndSpawnEntities(world)

        state = BuildSiteState.VIRTUAL
    }

    private fun placeAnchorAndSpawnEntities(world: org.bukkit.World) {
        val coreBlock = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)

        io.github.pylonmc.rebar.block.BlockStorage.placeBlock(
            coreBlock,
            top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor.KEY
        )

        logger.debug("site=$id", "已放置Anchor方块", "pos" to MonolithLogger.ModuleLogger.formatCoord(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z))

        org.bukkit.Bukkit.getScheduler().runTaskLater(top.mc506lw.monolith.MonolithLib.instance, Runnable {
            val anchor = io.github.pylonmc.rebar.block.BlockStorage.get(coreBlock)
            if (anchor == null) {
                logger.error("site=$id", "无法获取Anchor", "pos" to MonolithLogger.ModuleLogger.formatCoord(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z))
                return@Runnable
            }
            if (anchor !is top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor) {
                logger.error("site=$id", "Anchor类型错误", "actual" to anchor::class.simpleName)
                return@Runnable
            }

            logger.debug("site=$id", "Anchor已就位，开始注册展示实体")

            spawnDisplayEntitiesViaAnchor(anchor)
        }, 1L)
    }

    private fun spawnDisplayEntitiesViaAnchor(
        anchor: top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor
    ) {
        val displayEntityBlocks = blueprint.displayEntities.filter {
            it.entityType == top.mc506lw.monolith.core.model.DisplayType.BLOCK && it.blockData != null &&
            it.blockData!!.material != Material.STRUCTURE_VOID && it.blockData!!.material != Material.AIR
        }

        if (displayEntityBlocks.isEmpty()) {
            logger.warn("site=$id", "无有效展示实体数据")
            return
        }

        logger.debug("site=$id", "创建展示实体组", "entityCount" to displayEntityBlocks.size)

        try {
            val latestBlueprint = top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.get(blueprintId)
            val effectiveDisplayOffset = latestBlueprint?.meta?.displayOffset ?: blueprint.meta.displayOffset

            val facing = top.mc506lw.monolith.core.transform.Facing.fromYaw(anchorLocation.yaw)
            val rotationSteps = facing.rotationSteps
            val baseYaw = top.mc506lw.monolith.feature.display.DisplayTransformSystem.facingToYaw(facing)

            val config = loadBlueprintConfig(blueprintId)
            val assembledRotation = config?.assembledRotation ?: 0
            val initialYaw = baseYaw + assembledRotation

            logger.trace("site=$id", "旋转系统参数", "facing" to facing, "steps" to rotationSteps, "baseYaw" to String.format("%.1f", baseYaw), "assembledRot" to assembledRotation, "initialYaw" to String.format("%.1f", initialYaw))

            if (displayEntityBlocks.isNotEmpty()) {
                val sampleEntity = displayEntityBlocks[0]
                logger.trace("site=$id", "样例实体配置", "index" to 0, "trans" to sampleEntity.translation, "rot" to sampleEntity.rotation)
            }

            val offsetVec = org.joml.Vector3f(
                effectiveDisplayOffset.x.toFloat(),
                effectiveDisplayOffset.y.toFloat(),
                effectiveDisplayOffset.z.toFloat()
            )

            val group = top.mc506lw.monolith.feature.display.DisplayEntityGroup(
                anchor = anchor,
                centerLocation = anchorLocation,
                displayOffset = offsetVec,
                initialYaw = initialYaw
            )

            for ((index, entity) in displayEntityBlocks.withIndex()) {
                group.addBlockDisplay(
                    name = "$index",
                    blockData = entity.blockData!!,
                    translation = entity.translation,
                    scale = entity.scale,
                    rotation = entity.rotation
                )
            }

            this@BuildSite.displayGroup = group

            logger.info("site=$id", "展示实体组创建成功", "entityCount" to group.getSize())
        } catch (e: Exception) {
            logger.error("site=$id", "展示实体组创建失败", "error" to e.message)
            e.printStackTrace()
        }
    }

    private fun applyFacingRotationForAnchor(originalRotation: org.joml.Quaternionf, rotationSteps: Int): org.joml.Quaternionf {
        if (rotationSteps == 0) return org.joml.Quaternionf(originalRotation)

        val facingRotation = when (rotationSteps) {
            1 -> org.joml.Quaternionf().rotateY(kotlin.math.PI.toFloat() / 2f)
            2 -> org.joml.Quaternionf().rotateY(kotlin.math.PI.toFloat())
            3 -> org.joml.Quaternionf().rotateY(-kotlin.math.PI.toFloat() / 2f)
            else -> org.joml.Quaternionf()
        }

        return org.joml.Quaternionf(facingRotation).mul(originalRotation)
    }

    private fun buildDisplayShapeFromBackup(): Shape {
        val entries = mutableListOf<top.mc506lw.monolith.core.model.BlockEntry>()

        for ((worldPos, blockData) in backupData) {
            if (blockData.material == Material.STRUCTURE_VOID ||
                blockData.material == Material.AIR ||
                blockData.material == Material.RED_STAINED_GLASS) continue

            val relativePos = transform.toRelativePosition(
                worldPos = worldPos,
                controllerPos = Vector3i(anchorLocation.blockX, anchorLocation.blockY, anchorLocation.blockZ),
                centerOffset = blueprint.meta.controllerOffset
            )
            entries.add(top.mc506lw.monolith.core.model.BlockEntry(relativePos, blockData))
        }

        return Shape(entries)
    }

    fun disassembleFromVirtual(): Boolean {
        if (state != BuildSiteState.VIRTUAL) {
            logger.warn("site=$id", "解体失败：状态不是VIRTUAL", "currentState" to state)
            return false
        }

        val world = anchorLocation.world ?: return false

        logger.info("site=$id", "开始虚拟解体")

        val coreBlock = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)
        val anchor = io.github.pylonmc.rebar.block.BlockStorage.get(coreBlock)

        if (anchor is top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor) {
            anchor.tryRemoveAllEntities()
            logger.debug("site=$id", "Anchor已清理展示实体")
        } else {
            logger.warn("site=$id", "未找到有效Anchor", "actualType" to anchor?.let { it::class.simpleName })
        }

        if (io.github.pylonmc.rebar.block.BlockStorage.isRebarBlock(coreBlock)) {
            io.github.pylonmc.rebar.block.BlockStorage.breakBlock(coreBlock)
            logger.debug("site=$id", "已移除Anchor方块")
        } else {
            coreBlock.setType(Material.AIR, false)
        }

        for ((pos, originalData) in backupData) {
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (io.github.pylonmc.rebar.block.BlockStorage.isRebarBlock(block)) continue
            block.setBlockData(originalData.clone(), false)
        }

        backupData.clear()

        state = BuildSiteState.AWAITING_CORE

        val lastLayerIndex = (totalLayers - 1).coerceAtLeast(0)
        currentLayer = lastLayerIndex

        spawnCoreItemDisplay(world)

        return true
    }

    private fun spawnCoreItemDisplay(world: org.bukkit.World) {
        if (coreItemDisplay != null && coreItemDisplay!!.isValid) return

        coreItemDisplay?.remove()

        val location = Location(world,
            coreWorldPos.x.toDouble() + 0.5,
            coreWorldPos.y.toDouble() + 0.5,
            coreWorldPos.z.toDouble() + 0.5)

        try {
            val display = world.spawn(location, ItemDisplay::class.java) { d ->
                val coreItem = when {
                    coreRebarKey != null -> {
                        try {
                            io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                                .rebar(Material.LODESTONE, coreRebarKey)
                                .build()
                        } catch (e: Exception) {
                            ItemStack(Material.LODESTONE)
                        }
                    }
                    else -> ItemStack(Material.STRUCTURE_BLOCK)
                }
                d.setItemStack(coreItem)
                d.isGlowing = true
                d.glowColorOverride = Color.fromRGB(255, 215, 0)
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)

                val rotationRad = Math.toRadians((facing.rotationSteps * 90).toDouble()).toFloat()
                d.transformation = Transformation(
                    Vector3f(0f, -0.25f, 0f),
                    AxisAngle4f(0f, 1f, 0f, rotationRad),
                    Vector3f(0.6f, 0.6f, 0.6f),
                    AxisAngle4f()
                )
            }
            coreItemDisplay = display
        } catch (_: Exception) {}
    }

    fun removeCoreItemDisplay() {
        coreItemDisplay?.remove()
        coreItemDisplay = null
    }

    private fun renderCoreMarker(world: org.bukkit.World) {
        spawnCoreItemDisplay(world)
    }

    fun isCorePosition(pos: Vector3i): Boolean = pos == coreWorldPos

    fun isNearCorePosition(pos: Vector3i): Boolean {
        val dx = kotlin.math.abs(pos.x - coreWorldPos.x)
        val dy = kotlin.math.abs(pos.y - coreWorldPos.y)
        val dz = kotlin.math.abs(pos.z - coreWorldPos.z)
        return (dx + dy + dz) <= 1 && pos != coreWorldPos
    }

    fun isCoreGlassPosition(pos: Vector3i): Boolean = pos == coreWorldPos && coreGlassPlaced

    fun containsPosition(pos: Vector3i): Boolean = allGhostBlocks.any { it.worldPos == pos }

    fun isVirtualPosition(pos: Vector3i): Boolean {
        if (state != BuildSiteState.VIRTUAL) return false
        return allGhostBlocks.any { it.worldPos == pos }
    }

    fun getGhostAt(pos: Vector3i): SiteGhostBlock? = allGhostBlocks.find { it.worldPos == pos }

    fun recordPlacement(worldPos: Vector3i) {
        placedBlocks.add(worldPos)
    }

    fun hasPlacedBlock(worldPos: Vector3i): Boolean {
        return placedBlocks.contains(worldPos)
    }

    fun removePlacement(worldPos: Vector3i) {
        placedBlocks.remove(worldPos)
    }

    fun revertToBuilding(): Int {
        if (state != BuildSiteState.AWAITING_CORE) return -1

        removeCoreItemDisplay()

        state = BuildSiteState.BUILDING

        val world = anchorLocation.world
        if (world != null) {
            val coreBlock = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)
            if (coreBlock.type != Material.RED_STAINED_GLASS && coreBlock.type != Material.AIR) {
                val isRebar = io.github.pylonmc.rebar.block.BlockStorage.isRebarBlock(coreBlock)
                if (isRebar) {
                    try {
                        val rebarBlock = io.github.pylonmc.rebar.block.BlockStorage.get(coreBlock)
                        if (rebarBlock != null) {
                            val rebarItem = io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                                .rebar(coreBlock.type, (rebarBlock as io.github.pylonmc.rebar.block.RebarBlock).schema.key)
                                .build()
                            coreBlock.world.dropItemNaturally(coreBlock.location, rebarItem)
                        }
                    } catch (e: Exception) {
                        val fallbackItem = ItemStack(coreBlock.type)
                        coreBlock.world.dropItemNaturally(coreBlock.location, fallbackItem)
                    }
                } else {
                    val dropItem = ItemStack(coreBlock.type)
                    coreBlock.world.dropItemNaturally(coreBlock.location, dropItem)
                }
                coreBlock.setType(Material.AIR, false)
            }
        }

        if (world != null) {
            placeCoreGlassIfAbsent(world)
        }

        var targetLayer = -1
        for (i in layerYLevels.indices) {
            if (!isLayerFullyPlaced(i)) {
                targetLayer = i
                break
            }
        }

        if (targetLayer == -1) {
            targetLayer = 0
        }

        currentLayer = targetLayer

        return targetLayer
    }

    fun revertFromCompleted(player: Player? = null): Boolean {
        if (!isCompleted && state != BuildSiteState.VIRTUAL) return false

        val world = anchorLocation.world ?: return false

        isCompleted = false

        for ((pos, originalData) in backupData) {
            if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
            val block = world.getBlockAt(pos.x, pos.y, pos.z)
            if (block.type == Material.STRUCTURE_VOID) {
                block.blockData = originalData.clone()
            }
        }

        val coreBlock = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)
        if (io.github.pylonmc.rebar.block.BlockStorage.isRebarBlock(coreBlock)) {
            val anchor = io.github.pylonmc.rebar.block.BlockStorage.get(coreBlock)
            if (anchor is top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor) {
                anchor.tryRemoveAllEntities()
            }
            io.github.pylonmc.rebar.block.BlockStorage.breakBlock(coreBlock)
        } else {
            coreBlock.setType(Material.AIR, false)
        }

        removeAllRenderings()
        removeCoreItemDisplay()
        removeCoreGlass()

        state = BuildSiteState.BUILDING

        var targetLayer = -1
        for (i in layerYLevels.indices) {
            if (!isLayerFullyPlaced(i)) {
                targetLayer = i
                break
            }
        }
        currentLayer = if (targetLayer >= 0) targetLayer else 0

        placedBlocks.clear()

        for (ghost in allGhostBlocks) {
            if (ghost.isCore) continue
            val block = anchorLocation.world?.getBlockAt(ghost.worldPos.x, ghost.worldPos.y, ghost.worldPos.z) ?: continue
            if (ghost.predicate.testMaterialOnly(block.blockData, Predicate.PredicateContext(position = ghost.relativePos, block = block))) {
                placedBlocks.add(ghost.worldPos)
            }
        }

        var allLayersDone = true
        for (i in layerYLevels.indices) {
            if (!isLayerFullyPlaced(i)) {
                allLayersDone = false
                break
            }
        }

        if (allLayersDone) {
            enterAwaitingCore()
            player?.sendMessage(I18n.translatable("chat.build_site.all_layers_complete_hint"))
        } else {
            val advanced = advanceToNextIncompleteLayer()
            if (advanced > 0) {
                player?.sendMessage(I18n.translatable("chat.build_site.structure_disassembled_retry", I18n.arg("layer", currentLayer + 1)))
            } else {
                player?.sendMessage(I18n.translatable("chat.build_site.structure_disassembled_retry_no_layer"))
            }
        }

        return true
    }

    fun getProgress(): Pair<Int, Int> {
        val nonCoreBlocks = allGhostBlocks.filter { !it.isCore }
        val placed = nonCoreBlocks.count { it.worldPos in placedBlocks }
        return Pair(placed, nonCoreBlocks.size)
    }

    fun getCompletionRate(): Double {
        val (placed, total) = getProgress()
        return if (total > 0) placed.toDouble() / total.toDouble() else 1.0
    }

    fun removeRenderingForPlayer(playerId: UUID) {
        playerRenderCache.remove(playerId)
    }

    fun removeAllRenderings() {
        ghostDisplayEntities.values.forEach { it.remove() }
        ghostDisplayEntities.clear()
        playerRenderCache.clear()
    }

    fun removeCoreGlass() {
        if (!coreGlassPlaced) return

        val world = anchorLocation.world ?: return
        val block = world.getBlockAt(coreWorldPos.x, coreWorldPos.y, coreWorldPos.z)
        if (block.type == Material.RED_STAINED_GLASS) {
            block.type = Material.AIR
        }
        coreGlassPlaced = false
    }

    fun markCompleted() {
        isCompleted = true
        removeCoreItemDisplay()
        removeAllRenderings()
        removeCoreGlass()
    }

    internal fun restoreCompleted() {
        isCompleted = true
    }

    fun validateDetailed(): DetailedValidationResult {
        val world = anchorLocation.world ?: return DetailedValidationResult(
            isComplete = false, matchedCount = 0, totalCount = allGhostBlocks.size,
            completionRate = 0.0, blocksToFix = emptyList(), missingBlocks = allGhostBlocks.map { it.worldPos }
        )

        var matchedCount = 0
        val blocksToFix = mutableListOf<BlockFixEntry>()
        val missingBlocks = mutableListOf<Vector3i>()

        for (ghostBlock in allGhostBlocks) {
            val blockLocation = Location(world, ghostBlock.worldPos.x.toDouble(), ghostBlock.worldPos.y.toDouble(), ghostBlock.worldPos.z.toDouble())

            if (!blockLocation.chunk.isLoaded) {
                missingBlocks.add(ghostBlock.worldPos)
                continue
            }

            val actualBlock = blockLocation.block
            val actualBlockData = actualBlock.blockData
            val context = Predicate.PredicateContext(position = ghostBlock.relativePos, block = actualBlock)

            if (ghostBlock.predicate.testMaterialOnly(actualBlockData, context)) {
                matchedCount++
                if (!ghostBlock.predicate.test(actualBlockData, context)) {
                    blocksToFix.add(BlockFixEntry(
                        worldPos = ghostBlock.worldPos,
                        relativePos = ghostBlock.relativePos,
                        targetBlockData = ghostBlock.previewBlockData,
                        currentBlockData = actualBlockData
                    ))
                }
            } else {
                missingBlocks.add(ghostBlock.worldPos)
            }
        }

        val totalCount = allGhostBlocks.size
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

    private fun clearCurrentLayerRenderings() {
        ghostDisplayEntities.values.forEach { it.remove() }
        ghostDisplayEntities.clear()
        playerRenderCache.clear()
    }

    private fun getPlayerFacingDirection(yaw: Float): String {
        val normalizedYaw = ((yaw % 360) + 360) % 360
        return when {
            normalizedYaw < 45 || normalizedYaw >= 315 -> "南方 (SOUTH)"
            normalizedYaw < 135 -> "西方 (WEST)"
            normalizedYaw < 225 -> "北方 (NORTH)"
            else -> "东方 (EAST)"
        }
    }

    private fun loadBlueprintConfig(blueprintId: String): top.mc506lw.monolith.core.io.BlueprintConfig? {
        return try {
            val plugin = top.mc506lw.monolith.MonolithLib.instance
            val blueprintsFolder = File(plugin.dataFolder, "blueprints")
            val configFile = File(blueprintsFolder, "$blueprintId/$blueprintId.yml")

            if (!configFile.exists()) {
                logger.trace("config", "未找到蓝图配置文件", "path" to configFile.absolutePath)
                null
            } else {
                val config = top.mc506lw.monolith.core.io.BlueprintConfigLoader.load(configFile)
                if (config != null) {
                    logger.debug("config", "蓝图配置加载成功", "assembledRot" to config.assembledRotation, "scaffoldRot" to config.scaffoldRotation)
                }
                config
            }
        } catch (e: Exception) {
            logger.warn("config", "蓝图配置加载失败", "error" to e.message)
            null
        }
    }

    companion object {
        const val RENDER_RADIUS = 7
        const val UNLOAD_DISTANCE = 50
    }
}
