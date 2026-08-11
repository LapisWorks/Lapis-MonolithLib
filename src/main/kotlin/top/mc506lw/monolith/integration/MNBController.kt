package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import io.github.pylonmc.rebar.entity.display.BlockDisplayBuilder
import io.github.pylonmc.rebar.entity.display.ItemDisplayBuilder
import io.github.pylonmc.rebar.util.position.ChunkPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.DisplayType
import top.mc506lw.monolith.core.model.FormStrategy
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用多方块控制器 — 每个 [Blueprint] 自动对应一个此类的实例。
 *
 * 展示实体通过 [EntityHolderRebarBlock] 托管（UUID 随 Rebar 方块持久化），
 * 由 Rebar 在破坏/卸载时负责清理，与 Pylon 机器一致。
 */
open class MNBController(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context),
    RebarMultiblock,
    EntityHolderRebarBlock,
    BlockBreakRebarBlockHandler {

    /** 反序列化构造函数 — 由 Rebar 在 chunk 加载时调用 */
    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block)) {
        _blueprintId = pdc.get(BLUEPRINT_ID_KEY, PersistentDataType.STRING)
        _facingName = pdc.get(FACING_KEY, PersistentDataType.STRING)
    }

    private val logger = MonolithLogger.getLogger("MNBController")

    init {
        // 确保 EntityHolder 空 map 在首次序列化前已注册，避免 chunk 加载报 "Held entities not found"
        heldEntities
    }

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "blueprint_structure")
        val CONTROLLER_MATERIAL = Material.STRUCTURE_BLOCK

        private val BLUEPRINT_ID_KEY = NamespacedKey(MonolithLib.instance, "blueprint_id")
        private val FACING_KEY = NamespacedKey(MonolithLib.instance, "structure_facing")

        /** world:x:y:z → 成型结构的控制器，用于组件被破坏时立即回退。 */
        private val formedComponentIndex = ConcurrentHashMap<String, MNBController>()

        fun isFormedStructureComponent(block: Block): Boolean =
            formedComponentIndex.containsKey(blockPosKey(block))

        fun findControllerForComponent(block: Block): MNBController? =
            formedComponentIndex[blockPosKey(block)]

        private fun blockPosKey(block: Block): String =
            "${block.world.name}:${block.x}:${block.y}:${block.z}"

    }

    // ========== 运行时数据 ==========

    private var _blueprintId: String? = null
    private var _facingName: String? = null
    private var _structureFormed: Boolean = false
    private val hiddenBlocksOrigData = ConcurrentHashMap<Vector3i, String>()
    private val displayGroups = ConcurrentHashMap<String, MutableList<Display>>()
    private val registeredComponentKeys = mutableSetOf<String>()

    fun getGroup(name: String): List<Display> = displayGroups[name]?.toList() ?: emptyList()

    fun getGroups(): Set<String> = displayGroups.keys.toSet()

    override fun write(pdc: PersistentDataContainer) {
        val id = _blueprintId
        if (id != null) pdc.set(BLUEPRINT_ID_KEY, PersistentDataType.STRING, id)
        val f = _facingName
        if (f != null) pdc.set(FACING_KEY, PersistentDataType.STRING, f)
    }

    /** 由 [BuildSiteAnchorBlock.finalizeWithWrench] 在定型后调用 */
    fun initialize(blueprintId: String, f: Facing) {
        _blueprintId = blueprintId
        _facingName = f.name
        _structureFormed = false
        hiddenBlocksOrigData.clear()
        unregisterFormedComponents()
    }

    val blueprintId: String? get() = _blueprintId

    val facing: Facing
        get() {
            val name = _facingName
            return if (name != null) try { Facing.valueOf(name) } catch (_: Exception) { Facing.NORTH } else Facing.NORTH
        }

    protected val blueprint: Blueprint?
        get() {
            val id = _blueprintId ?: return null
            return try { MonolithAPI.getInstance().registry.get(id) } catch (_: Exception) { null }
        }

    /** 由外部（BuildSiteAnchorBlock）在定型后调用 */
    fun setFacing(f: Facing) {
        _facingName = f.name
    }

    /**
     * Override this in a machine controller to replace a blueprint role with an
     * alternative component, such as basic or advanced input hatches.
     */
    protected open fun configureComponents(components: MonolithComponents) = Unit

    fun getMNBComponents(): Map<Vector3i, io.github.pylonmc.rebar.block.interfaces.SimpleRebarMultiblock.MultiblockComponent> {
        val bp = blueprint ?: return emptyMap()
        return MonolithComponents.fromMNB(bp, facing.rotationSteps).also(::configureComponents).toMap()
    }

    /** Resolves every named port of a role to its actual formed Rebar block. */
    fun slotBlocks(slotType: String): Map<String, RebarBlock> {
        val bp = blueprint ?: return emptyMap()
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        return bp.getSlotPositions(slotType).mapNotNull { (name, position) ->
            val worldPos = transform.toWorldPosition(controllerPos, position, bp.meta.controllerOffset)
            BlockStorage.get(block.world.getBlockAt(worldPos.x, worldPos.y, worldPos.z))?.let { name to it }
        }.toMap()
    }

    // ========== RebarMultiblock ==========

    override val chunksOccupied: Set<ChunkPosition>
        get() {
            // 兜底：控制器尚未 initialize（blueprintId 为 null）时，
            // 至少返回控制器自身所在 chunk，避免 Rebar MultiblockCache 抛
            // "Your multiblock must occupy at least one chunk"。
            val selfChunk = block.position.chunk
            val bp = blueprint
            if (bp == null) return setOf(selfChunk)
        val components = getMNBComponents()
        if (components.isEmpty()) return setOf(selfChunk)

            val chunks = mutableSetOf<ChunkPosition>()
            val transform = CoordinateTransform(facing)
            val controllerPos = Vector3i(block.x, block.y, block.z)
            val world = block.world
            for (offset in components.keys) {
                val wpos = transform.toWorldPosition(controllerPos, offset, Vector3i(0, 0, 0))
                chunks.add(world.getBlockAt(wpos.x, wpos.y, wpos.z).position.chunk)
            }
            chunks.add(selfChunk)
            return chunks
        }

    override fun checkFormed(): Boolean {
        val bp = blueprint ?: return false
        val strategy = bp.formStrategy
        val world = block.world

        // 与 components 相同：相对控制器的 offset（已减 centerOffset）
        val hiddenRelative = getHiddenRelativePositions(bp, strategy)
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)

        for ((offset, component) in getMNBComponents()) {
            if (_structureFormed && offset in hiddenRelative) continue
            val worldPos = transform.toWorldPosition(controllerPos, offset, Vector3i(0, 0, 0))
            if (!component.matches(world.getBlockAt(worldPos.x, worldPos.y, worldPos.z))) return false
        }
        return true
    }

    override fun isPartOfMultiblock(otherBlock: Block): Boolean {
        if (otherBlock.world != block.world) return false
        if (otherBlock.x == block.x && otherBlock.y == block.y && otherBlock.z == block.z) return true
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        // 与 checkFormed 同一套世界坐标变换，避免未旋转相对坐标导致破坏不触发 unform
        for (offset in getMNBComponents().keys) {
            val wpos = transform.toWorldPosition(controllerPos, offset, Vector3i(0, 0, 0))
            if (wpos.x == otherBlock.x && wpos.y == otherBlock.y && wpos.z == otherBlock.z) return true
        }
        return false
    }

    open override fun onMultiblockFormed() {
        val bp = blueprint ?: return
        logger.info { "Structure formed: ${bp.id} at (${block.x}, ${block.y}, ${block.z})" }
        _structureFormed = true
        applyFormStrategy(bp)
        registerFormedComponents(bp)
    }

    open override fun onMultiblockUnformed(partUnloaded: Boolean) {
        val bp = blueprint ?: return
        logger.info { "Structure unformed: ${bp.id} at (${block.x}, ${block.y}, ${block.z})" }
        _structureFormed = false
        unregisterFormedComponents()
        // partUnloaded：只卸载不改世界；实体由 EntityHolder 在 chunk 卸载时自然处理
        if (!partUnloaded) {
            revertFormStrategy(bp)
        } else {
            displayGroups.clear()
        }
    }

    // ========== BlockBreakRebarBlockHandler ==========

    override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext) {
        drops.clear()
        unregisterFormedComponents()
        clearHeldDisplays()
        // 程序化无掉落删除（如扳手解体调用 breakBlock(normallyDrops=false)）：不掉落、不重复 revert
        if (context is BlockBreakContext.PluginBreak && !context.normallyDrops) {
            return
        }
        val bp = blueprint
        if (bp != null && _structureFormed) {
            _structureFormed = false
            revertFormStrategy(bp)
        } else if (bp != null) {
            clearHeldDisplays()
        }
        val controllerKey = try { bp?.controllerRebarKey ?: KEY } catch (_: Exception) { KEY }
        drops.add(io.github.pylonmc.rebar.item.builder.ItemStackBuilder.rebar(block.type, controllerKey).build())
    }

    // ========== 生命周期 ==========

    override fun postLoad() {
        try {
            // Formation state is owned by Rebar; static blueprint data is intentionally not persisted here.
            _structureFormed = checkFormed()
            if (_structureFormed) {
                val bp = blueprint
                if (bp != null) {
                    if (!restoreDisplayEntities(bp)) {
                        clearHeldDisplays()
                        applyFormStrategy(bp)
                    }
                    registerFormedComponents(bp)
                }
            }
        } catch (e: Exception) {
            logger.warn { "postLoad error for ${block.location}: ${e.message}" }
        }
    }

    // ========== FormStrategy 应用 ==========

    /** Returns positions relative to the controller, matching [getMNBComponents] keys. */
    private fun getHiddenRelativePositions(bp: Blueprint, strategy: FormStrategy): Set<Vector3i> {
        val center = bp.meta.controllerOffset
        fun toRel(pos: Vector3i) = Vector3i(pos.x - center.x, pos.y - center.y, pos.z - center.z)
        return when (strategy) {
            is FormStrategy.BlockOnly -> emptySet()
            is FormStrategy.FullDisplay -> bp.assembledShape.blocks.map { toRel(it.position) }.toSet()
            is FormStrategy.Hybrid -> strategy.hiddenPositions.map { toRel(it) }.toSet()
        }
    }

    private fun applyFormStrategy(bp: Blueprint) {
        val strategy = bp.formStrategy
        val world = block.world
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps

        // 成型前清掉旧实体，避免重复 UUID / 残留
        clearHeldDisplays()

        when (strategy) {
            is FormStrategy.BlockOnly -> {
                spawnDisplayEntities(bp, world, transform, controllerPos, centerOffset, rotationSteps)
                logger.info { "Applied display entities for ${bp.id}" }
            }
            is FormStrategy.FullDisplay -> {
                if (strategy.hideOriginalBlocks) hideOriginalBlocks(bp, world, transform, controllerPos, centerOffset)
                spawnDisplayEntities(bp, world, transform, controllerPos, centerOffset, rotationSteps)
                logger.info { "Applied FullDisplay for ${bp.id}" }
            }
            is FormStrategy.Hybrid -> {
                if (strategy.hiddenPositions.isNotEmpty()) {
                    for (pos in strategy.hiddenPositions) {
                        val wpos = transform.toWorldPosition(controllerPos, pos, centerOffset)
                        val b = world.getBlockAt(wpos.x, wpos.y, wpos.z)
                        if (!BlockStorage.isRebarBlock(b)) {
                            hiddenBlocksOrigData[pos] = b.blockData.clone().asString
                        }
                        b.setType(Material.STRUCTURE_VOID, false)
                    }
                }
                spawnDisplayEntities(bp, world, transform, controllerPos, centerOffset, rotationSteps)
                logger.info { "Applied Hybrid for ${bp.id}" }
            }
        }
    }

    private fun hideOriginalBlocks(bp: Blueprint, world: org.bukkit.World, transform: CoordinateTransform, controllerPos: Vector3i, centerOffset: Vector3i) {
        for (be in bp.assembledShape.blocks) {
            val wpos = transform.toWorldPosition(controllerPos, be.position, centerOffset)
            val b = world.getBlockAt(wpos.x, wpos.y, wpos.z)
            if (!BlockStorage.isRebarBlock(b)) {
                hiddenBlocksOrigData[be.position] = b.blockData.clone().asString
            }
        }
        for (be in bp.assembledShape.blocks) {
            val wpos = transform.toWorldPosition(controllerPos, be.position, centerOffset)
            val b = world.getBlockAt(wpos.x, wpos.y, wpos.z)
            if (!BlockStorage.isRebarBlock(b)) b.setType(Material.STRUCTURE_VOID, false)
        }
    }

    private fun revertFormStrategy(bp: Blueprint) {
        val world = block.world
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset

        clearHeldDisplays()

        for (be in bp.assembledShape.blocks) {
            val wpos = transform.toWorldPosition(controllerPos, be.position, centerOffset)
            val b = world.getBlockAt(wpos.x, wpos.y, wpos.z)
            if (b.type == Material.STRUCTURE_VOID) {
                val origDataStr = hiddenBlocksOrigData.remove(be.position)
                if (origDataStr != null) {
                    try { b.blockData = org.bukkit.Bukkit.createBlockData(origDataStr) }
                    catch (_: Exception) { b.setType(Material.AIR, false) }
                } else {
                    b.setType(Material.AIR, false)
                }
            }
        }
        logger.info { "Reverted FormStrategy for ${bp.id}" }
    }

    /**
     * 扳手解体专用：恢复 FullDisplay/Hybrid 隐藏方块 → 把 assembled 方块替换为 scaffold 方块（非控制器位置）。
     * 之后由调用方删除控制器并放置 [BuildSiteAnchorBlock]。
     */
    fun disassembleToScaffold() {
        val bp = blueprint ?: return
        val world = block.world
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps

        // 1. 触发 unform（会调用 revertFormStrategy：恢复 STRUCTURE_VOID → 原方块 + 移除 display）
        if (_structureFormed) {
            try { onMultiblockUnformed(partUnloaded = false) } catch (_: Exception) {}
        } else {
            clearHeldDisplays()
        }

        // 2. 把 assembled 方块替换为 scaffold 方块（非控制器位置）
        val scaffoldMap = bp.scaffoldShape.blocks.associateBy { it.position }
        for (be in bp.assembledShape.blocks) {
            if (be.position == centerOffset) continue
            val wpos = transform.toWorldPosition(controllerPos, be.position, centerOffset)
            val b = world.getBlockAt(wpos.x, wpos.y, wpos.z)
            if (BlockStorage.isRebarBlock(b)) continue
            val scaffoldEntry = scaffoldMap[be.position]
            if (scaffoldEntry != null) {
                val rotatedData = BlockStateRotator.rotate(scaffoldEntry.blockData.clone(), rotationSteps)
                try { b.setBlockData(rotatedData, false) } catch (_: Exception) {}
            }
        }
        logger.info { "Disassembled ${bp.id} back to scaffold at (${block.x}, ${block.y}, ${block.z})" }
    }

    private fun spawnDisplayEntities(
        bp: Blueprint,
        world: org.bukkit.World,
        transform: CoordinateTransform,
        controllerPos: Vector3i,
        centerOffset: Vector3i,
        rotationSteps: Int
    ) {
        if (bp.displayEntities.isEmpty()) return
        val displayOffset = transform.transform(bp.meta.displayOffset)
        var spawned = 0
        for ((index, ed) in bp.displayEntities.withIndex()) {
            val wpos = transform.toWorldPosition(controllerPos, ed.position, centerOffset)
            val visualTranslation = rotateTranslation(ed.translation, rotationSteps)
            val loc = Location(
                world,
                wpos.x + 0.5 + displayOffset.x + visualTranslation.x.toDouble(),
                wpos.y + 0.5 + displayOffset.y + visualTranslation.y.toDouble(),
                wpos.z + 0.5 + displayOffset.z + visualTranslation.z.toDouble()
            )
            try {
                val matrix = buildTransformationMatrix(ed, rotationSteps)
                val key = "de_$index"
                val display: Display = when (ed.entityType) {
                    DisplayType.BLOCK -> {
                        val data = ed.blockData ?: continue
                        val rd = BlockStateRotator.rotate(data.clone(), rotationSteps)
                        BlockDisplayBuilder()
                            .blockData(rd)
                            .transformation(matrix)
                            .brightness(Display.Brightness(15, 15))
                            .persistent(true)
                            .build(loc)
                    }
                    DisplayType.ITEM -> {
                        val builder = ItemDisplayBuilder()
                            .transformation(matrix)
                            .brightness(Display.Brightness(15, 15))
                            .persistent(true)
                        ed.itemStack?.let { builder.itemStack(it.clone()) }
                        builder.build(loc)
                    }
                }
                // 挂到控制器 EntityHolder 上，由 Rebar 负责持久化 UUID / 破坏时移除
                addEntity(key, display)
                displayGroups.computeIfAbsent(ed.group.ifBlank { "default" }) { mutableListOf() }.add(display)
                spawned++
            } catch (e: Exception) {
                logger.warn { "Failed to spawn display for ${bp.id} at $wpos: ${e.message}" }
            }
        }
        logger.info { "Spawned $spawned/${bp.displayEntities.size} display entities for ${bp.id} (offset=$displayOffset)" }
    }

    private fun rotateTranslation(value: Vector3f, steps: Int): Vector3f = when (steps % 4) {
        1 -> Vector3f(value.z, value.y, -value.x)
        2 -> Vector3f(-value.x, value.y, -value.z)
        3 -> Vector3f(-value.z, value.y, value.x)
        else -> Vector3f(value)
    }

    private fun restoreDisplayEntities(bp: Blueprint): Boolean {
        displayGroups.clear()
        if (bp.displayEntities.isEmpty()) return true
        var allOk = true
        for ((index, ed) in bp.displayEntities.withIndex()) {
            val key = "de_$index"
            val entity = getHeldEntity(key) as? Display
            if (entity != null && entity.isValid) {
                displayGroups.computeIfAbsent(ed.group.ifBlank { "default" }) { mutableListOf() }.add(entity)
            } else {
                allOk = false
            }
        }
        return allOk
    }

    /** 移除所有托管展示实体并清空 holder 映射与分组缓存 */
    private fun clearHeldDisplays() {
        try {
            tryRemoveAllEntities()
        } catch (_: Exception) {
        }
        heldEntities.clear()
        displayGroups.clear()
    }

    private fun registerFormedComponents(bp: Blueprint) {
        unregisterFormedComponents()
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        for (offset in getMNBComponents().keys) {
            val wpos = transform.toWorldPosition(controllerPos, offset, Vector3i(0, 0, 0))
            // 控制器本身由 Rebar 破坏逻辑处理，组件索引用于非控制器方块
            if (wpos.x == block.x && wpos.y == block.y && wpos.z == block.z) continue
            val key = "${block.world.name}:${wpos.x}:${wpos.y}:${wpos.z}"
            formedComponentIndex[key] = this
            registeredComponentKeys.add(key)
        }
    }

    private fun unregisterFormedComponents() {
        for (key in registeredComponentKeys) {
            formedComponentIndex.remove(key)
        }
        registeredComponentKeys.clear()
    }

    private fun buildTransformationMatrix(ed: DisplayEntityData, rotationSteps: Int): Matrix4f {
        val facingRot = when (rotationSteps % 4) {
            1 -> Quaternionf().rotateY((Math.PI / 2.0).toFloat())
            2 -> Quaternionf().rotateY(Math.PI.toFloat())
            3 -> Quaternionf().rotateY((-Math.PI / 2.0).toFloat())
            else -> Quaternionf()
        }
        val left = Quaternionf(facingRot).mul(ed.rotation)
        return Matrix4f()
            .translation(0f, 0f, 0f)
            .rotate(left)
            .scale(ed.scale)
    }

    @Suppress("unused")
    private fun buildTransformation(ed: DisplayEntityData, rotationSteps: Int): Transformation {
        val fr = when (rotationSteps % 4) {
            1 -> Quaternionf().rotateY(kotlin.math.PI.toFloat() / 2f)
            2 -> Quaternionf().rotateY(kotlin.math.PI.toFloat())
            3 -> Quaternionf().rotateY(-kotlin.math.PI.toFloat() / 2f)
            else -> Quaternionf()
        }
        return Transformation(Vector3f(0f, 0f, 0f), Quaternionf(fr).mul(ed.rotation), ed.scale, Quaternionf())
    }
}
