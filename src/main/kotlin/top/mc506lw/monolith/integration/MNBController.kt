package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import io.github.pylonmc.rebar.block.interfaces.SimpleRebarMultiblock.MultiblockComponent
import io.github.pylonmc.rebar.entity.display.BlockDisplayBuilder
import io.github.pylonmc.rebar.entity.display.ItemDisplayBuilder
import io.github.pylonmc.rebar.util.position.ChunkPosition
import io.github.pylonmc.rebar.util.position.position
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
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
import top.mc506lw.monolith.core.model.BoundingBox
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.feature.buildsite.BuildSiteRegistry
import top.mc506lw.monolith.core.model.DisplayType
import top.mc506lw.monolith.core.model.FormStrategy
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.lifecycle.PositionCache
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.validation.predicate.RebarPredicate
import top.mc506lw.monolith.validation.predicate.rebarKeyOfPredicate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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

        /** 世界名 → 已成型结构（AABB 粗筛 + 蓝图精确判定）。成型/解体 O(1)，不再维护百万级位置索引。 */
        private val formedByWorld = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<FormedEntry>>()

        /** 已成型结构条目：持有 assembled 世界包围盒用于 O(1) 粗筛。 */
        class FormedEntry(val controller: MNBController, val worldBox: BoundingBox)

        /** 解体转换时所在区块未加载的方块：chunkKey → 待转换列表，chunk load 时补转。 */
        val pendingScaffoldByChunk = ConcurrentHashMap<Long, PendingScaffold>()

        /** 待转换的 scaffold 方块（持有控制器引用以复用 facing/blueprint）。 */
        class PendingScaffold(val controller: MNBController) {
            val positions: ConcurrentLinkedQueue<Vector3i> = ConcurrentLinkedQueue()
        }

        fun isFormedStructureComponent(block: Block): Boolean =
            findControllerForComponent(block) != null

        fun findControllerForComponent(block: Block): MNBController? {
            val list = formedByWorld[block.world.name] ?: return null
            for (fe in list) {
                val controller = fe.controller
                if (fe.worldBox.contains(block.x, block.y, block.z)) {
                    val bp = controller.blueprint ?: continue
                    val rel = controller.cachedTransform().toRelativePosition(
                        Vector3i(block.x, block.y, block.z),
                        Vector3i(controller.block.x, controller.block.y, controller.block.z),
                        bp.meta.controllerOffset
                    )
                    // 精确判定：反变换后必须是 assembled shape 的成员（含控制器位置，由调用方先处理）
                    if (bp.assembledShape.getBlockAt(rel) != null) return controller
                }
            }
            return null
        }

    }

    // ========== 运行时数据 ==========

    private var _blueprintId: String? = null
    private var _facingName: String? = null
    private var _structureFormed: Boolean = false
    private val hiddenBlocksOrigData = ConcurrentHashMap<Vector3i, String>()
    private val displayGroups = ConcurrentHashMap<String, MutableList<Display>>()

    /** 解体转换全部完成（含补转队列排空）后的回调：由 StructureDisassembly 注入展位引用。 */
    @Volatile
    private var scaffoldConversionOnComplete: (() -> Unit)? = null

    // ---- 性能缓存：Rebar 的 MultiblockCache 会对每个方块事件调用 chunksOccupied /
    // isPartOfMultiblock / checkFormed，百万级结构绝不能每次重建 1M 项映射 ----
    @Volatile private var cachedComponents: Map<Vector3i, MultiblockComponent>? = null
    @Volatile private var cachedChunks: Set<ChunkPosition>? = null
    @Volatile private var cachedTransform: CoordinateTransform? = null
    @Volatile private var cachedHiddenRelative: Pair<FormStrategy, Set<Vector3i>>? = null
    private var componentsCacheKey: String? = null

    private fun invalidateCaches() {
        cachedComponents = null
        cachedChunks = null
        cachedTransform = null
        cachedHiddenRelative = null
        componentsCacheKey = null
    }

    private fun cachedTransform(): CoordinateTransform =
        cachedTransform ?: CoordinateTransform(facing).also { cachedTransform = it }

    private fun cachedHiddenRelative(bp: Blueprint, strategy: FormStrategy): Set<Vector3i> {
        val cached = cachedHiddenRelative
        if (cached != null && cached.first === strategy) return cached.second
        val built = getHiddenRelativePositions(bp, strategy)
        cachedHiddenRelative = strategy to built
        return built
    }

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
        invalidateCaches()
    }

    val blueprintId: String? get() = _blueprintId

    val facing: Facing
        get() {
            val name = _facingName
            return if (name != null) try { Facing.valueOf(name) } catch (_: Exception) { Facing.NORTH } else Facing.NORTH
        }

    internal val blueprint: Blueprint?
        get() {
            val id = _blueprintId ?: return null
            return try { MonolithAPI.getInstance().registry.get(id) } catch (_: Exception) { null }
        }

    /** 由外部（BuildSiteAnchorBlock）在定型后调用 */
    fun setFacing(f: Facing) {
        _facingName = f.name
        invalidateCaches()
    }

    /**
     * Override this in a machine controller to replace a blueprint role with an
     * alternative component, such as basic or advanced input hatches.
     */
    protected open fun configureComponents(components: MonolithComponents) = Unit

    fun getMNBComponents(): Map<Vector3i, MultiblockComponent> {
        val bp = blueprint ?: return emptyMap()
        val key = buildString { append(_blueprintId).append(':').append(facing.rotationSteps) }
        cachedComponents?.let { if (componentsCacheKey == key) return it }
        val built = MonolithComponents.fromMNB(bp, facing.rotationSteps).also(::configureComponents).toMap()
        componentsCacheKey = key
        cachedComponents = built
        return built
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
            cachedChunks?.let { return it }
            val bp = blueprint
            if (bp == null) return setOf(selfChunk)
            val components = getMNBComponents()
            if (components.isEmpty()) return setOf(selfChunk)

            // 只算一次并缓存：Rebar 在 add/remove/refreshFullyLoaded 反复访问本属性
            val chunks = java.util.HashSet<ChunkPosition>()
            val transform = cachedTransform()
            val controllerPos = Vector3i(block.x, block.y, block.z)
            for (offset in components.keys) {
                val rel = transform.transform(offset)
                chunks.add(ChunkPosition(block.world, (controllerPos.x + rel.x) shr 4, (controllerPos.z + rel.z) shr 4))
            }
            chunks.add(selfChunk)
            return chunks.toSet().also { cachedChunks = it }
        }

    override fun checkFormed(): Boolean {
        val bp = blueprint ?: return false
        val strategy = bp.formStrategy
        val world = block.world

        // 与 components 相同：相对控制器的 offset（已减 centerOffset）
        val hiddenRelative = cachedHiddenRelative(bp, strategy)
        val transform = cachedTransform()
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val components = getMNBComponents()

        for ((offset, component) in components) {
            if (_structureFormed && offset in hiddenRelative) continue
            val rel = transform.transform(offset)
            val wx = controllerPos.x + rel.x
            val wy = controllerPos.y + rel.y
            val wz = controllerPos.z + rel.z
            // 未加载区块跳过：避免主线程同步加载区块（Rebar 以 partUnloaded 单独处理）
            val chunk = if (world.isChunkLoaded(wx shr 4, wz shr 4)) {
                world.getChunkAt(wx shr 4, wz shr 4)
            } else {
                null
            } ?: continue
            val matched = if (component.rebarBlocks.isEmpty()) {
                // 纯 vanilla 组件：直接比 Chunk 的 blockData，避免逐方块 BlockStorage 读
                val data = chunk.getBlock(wx and 15, wy, wz and 15).blockData
                component.vanillaBlocks.any { data.matches(it) }
            } else {
                component.matches(chunk.getBlock(wx and 15, wy, wz and 15))
            }
            if (!matched) return false
        }
        return true
    }

    override fun isPartOfMultiblock(otherBlock: Block): Boolean {
        if (otherBlock.world != block.world) return false
        if (otherBlock.x == block.x && otherBlock.y == block.y && otherBlock.z == block.z) return true
        val bp = blueprint ?: return false
        val components = getMNBComponents()
        if (components.isEmpty()) return false
        // O(1)：反变换出相对坐标后查缓存的组件映射（不再遍历百万级 keys）
        val relative = cachedTransform().toRelativePosition(
            Vector3i(otherBlock.x, otherBlock.y, otherBlock.z),
            Vector3i(block.x, block.y, block.z),
            Vector3i(0, 0, 0)
        )
        return components.containsKey(relative)
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
        // 成型机器被破坏时回退到脚手架工地由 StructureDisassembly 处理；
        // 这里不掉落孤立的"控制器物品"，避免产生无意义的散落物品。
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
     *
     * 替换改为分帧批量执行（每 tick ≤8ms，不冻结主线程）；未加载区块的方块
     * 推迟到该区块加载时由 [ScaffoldChunkLoader] 补转。
     *
     * [onComplete]：全部转换完成（含补转队列排空）后调用。由调用方注入展位引用——
     * 修复原 findAt 方案的注册竞态：锚点在异步 ghost 构建回调里才注册，小结构转换
     * 1 tick 内就完成，findAt 必然返回 null，onScaffoldConversionComplete 沦为死代码。
     */
    fun disassembleToScaffold(onComplete: (() -> Unit)? = null) {
        val bp = blueprint ?: return
        _structureFormed = false
        unregisterFormedComponents()
        clearHeldDisplays()
        // 转换循环会覆盖 STRUCTURE_VOID 位置（scaffold 方块 / 空气），无需再走一遍 revert
        hiddenBlocksOrigData.clear()
        scaffoldConversionOnComplete = onComplete
        scheduleScaffoldConversion(bp)
    }

    /** 触发解体转换完成回调（幂等：只触发一次）。由转换任务或补转队列排空时调用。 */
    internal fun fireScaffoldConversionComplete() {
        val cb = scaffoldConversionOnComplete ?: return
        scaffoldConversionOnComplete = null
        cb.invoke()
    }

    private fun scheduleScaffoldConversion(bp: Blueprint) {
        val world = block.world
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps
        val assembledBlocks = bp.assembledShape.blocks
        val size = assembledBlocks.size
        val idx = AtomicInteger(0)
        val scheduler = org.bukkit.Bukkit.getScheduler()
        val task = object : Runnable {
            override fun run() {
                val deadline = System.nanoTime() + 8_000_000L // 每 tick 至多 ~8ms，避免卡服
                var done = false
                while (true) {
                    if (System.nanoTime() >= deadline) break
                    val i = idx.getAndIncrement()
                    if (i >= size) { done = true; break }
                    val be = assembledBlocks[i]
                    if (be.position == centerOffset) continue // 控制器位置由调用方单独处理
                    val rx = be.position.x - centerOffset.x
                    val ry = be.position.y - centerOffset.y
                    val rz = be.position.z - centerOffset.z
                    val wx: Int
                    val wz: Int
                    // 与 Matrix3x3 旋转约定一致的内联变换（避免每方块分配 Vector3i）
                    when (rotationSteps % 4) {
                        1 -> { wx = controllerPos.x + rz; wz = controllerPos.z - rx }
                        2 -> { wx = controllerPos.x - rx; wz = controllerPos.z - rz }
                        3 -> { wx = controllerPos.x - rz; wz = controllerPos.z + rx }
                        else -> { wx = controllerPos.x + rx; wz = controllerPos.z + rz }
                    }
                    val wy = controllerPos.y + ry
                    if (!world.isChunkLoaded(wx shr 4, wz shr 4)) {
                        val key = PositionCache.getChunkKey(wx shr 4, wz shr 4)
                        pendingScaffoldByChunk
                            .computeIfAbsent(key) { PendingScaffold(this@MNBController) }
                            .positions.add(Vector3i(wx, wy, wz))
                        continue
                    }
                    convertScaffoldBlockAtRelative(
                        bp, be.position, wx, wy, wz, rotationSteps, be.predicate is RebarPredicate
                    )
                }
                if (!done) scheduler.runTask(MonolithLib.instance, this)
                else {
                    // 转换全部完成：该位置工地 scaffold 已就位，直接把完成度置为 100%，
                    // 避免"解体后再定型"因计数器未校准而显示 0%（无需敲一块方块触发校准）。
                    // 不再走 BuildSiteRegistry.findAt —— 锚点注册在异步 ghost 构建回调里，
                    // 小结构转换 1 tick 就完成，findAt 必然为 null（原逻辑是死代码）。
                    // 若有未加载区块的补转队列 → 等 ScaffoldChunkLoader 排空后再触发。
                    if (pendingScaffoldByChunk.isEmpty()) {
                        fireScaffoldConversionComplete()
                    }
                }
            }
        }
        scheduler.runTask(MonolithLib.instance, task)
    }

    /** 把单个 assembled 方块转换为脚手架方块（无掉落）。未加载区块 → 记录到待转队列，chunk load 时补转。 */
    internal fun convertScaffoldBlockAt(bp: Blueprint, wx: Int, wy: Int, wz: Int) {
        val world = block.world
        if (!world.isChunkLoaded(wx shr 4, wz shr 4)) {
            val key = PositionCache.getChunkKey(wx shr 4, wz shr 4)
            pendingScaffoldByChunk
                .computeIfAbsent(key) { PendingScaffold(this) }
                .positions.add(Vector3i(wx, wy, wz))
            return
        }
        val transform = cachedTransform()
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val relative = transform.toRelativePosition(Vector3i(wx, wy, wz), controllerPos, centerOffset)
        if (relative == centerOffset) return
        val be = bp.assembledShape.getBlockAt(relative)
        convertScaffoldBlockAtRelative(
            bp, relative, wx, wy, wz, facing.rotationSteps, be?.predicate is RebarPredicate
        )
    }

    /** 按相对坐标直接转换（批处理循环已算好世界坐标，跳过反变换与二次区块检查）。 */
    private fun convertScaffoldBlockAtRelative(
        bp: Blueprint,
        relative: Vector3i,
        wx: Int,
        wy: Int,
        wz: Int,
        rotationSteps: Int,
        assembledRebar: Boolean
    ) {
        val world = block.world
        val b = world.getBlockAt(wx, wy, wz)
        val scaffoldEntry = bp.scaffoldShape.getBlockAt(relative)
        if (scaffoldEntry == null) {
            // 成型独有的位置：脚手架中不存在 → 空气
            if (BlockStorage.isRebarBlock(b)) {
                try {
                    BlockStorage.breakBlock(
                        b,
                        BlockBreakContext.PluginBreak(b, normallyDrops = false, shouldSetToAir = true)
                    )
                } catch (_: Exception) {}
            }
            b.setType(Material.AIR, false)
            return
        }
        val scaffoldRebarKey = scaffoldEntry.predicate?.rebarKeyOfPredicate()
        // 仅 assembled 或 scaffold 涉及 rebar 的位置才做 PDC 检查；百万级 vanilla 位置跳过，
        // 避免每方块一次 BlockStorage.isRebarBlock（PDC 读取）拖慢整批转换
        if ((assembledRebar || scaffoldRebarKey != null) && BlockStorage.isRebarBlock(b)) {
            try {
                BlockStorage.breakBlock(
                    b,
                    BlockBreakContext.PluginBreak(b, normallyDrops = false, shouldSetToAir = true)
                )
            } catch (_: Exception) {}
        }
        // 脚手架该位置是 RebarPredicate → 还原为 rebar 方块（可再次拾取/建筑）。
        // 严格处理：不再静默兜底成"普通方块"——那会产出材质对但无 PDC 的黄块
        // （看起来与 rebar 一致，但 BlockStorage 读不到，predicate 永远判不匹配）。
        if (scaffoldRebarKey != null) {
            try {
                BlockStorage.placeBlock(b, scaffoldRebarKey)
            } catch (e1: Exception) {
                // 目标可能仍是 rebar（预检与放置检查不一致）或状态残留：先清空再重试一次
                try {
                    if (BlockStorage.isRebarBlock(b)) {
                        BlockStorage.breakBlock(
                            b,
                            BlockBreakContext.PluginBreak(b, normallyDrops = false, shouldSetToAir = true)
                        )
                    } else {
                        b.setType(Material.AIR, false)
                    }
                    BlockStorage.placeBlock(b, scaffoldRebarKey)
                } catch (e2: Exception) {
                    logger.warn { "解体转换：恢复 rebar $scaffoldRebarKey @ ($wx,$wy,$wz) 失败: ${e1.message} / ${e2.message}，保留原状（红幽灵提示玩家手动放置）" }
                    return
                }
            }
            // 放置后校验：事件可能被取消返回 null，此时不要写成普通方块
            if (BlockStorage.get(b)?.schema?.key != scaffoldRebarKey) {
                logger.warn { "解体转换：rebar $scaffoldRebarKey @ ($wx,$wy,$wz) 放置后校验失败（可能被事件取消）" }
            }
            return
        }
        val rotatedData = if (rotationSteps % 4 == 0) {
            scaffoldEntry.blockData // 无需克隆/旋转
        } else {
            BlockStateRotator.rotate(scaffoldEntry.blockData.clone(), rotationSteps)
        }
        try { b.setBlockData(rotatedData, false) } catch (_: Exception) {}
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
        // 一次性计算 assembled 世界包围盒（此后 AABB 粗筛 O(1)），成型/解体不再触碰百万级位置
        formedByWorld.computeIfAbsent(block.world.name) { java.util.concurrent.CopyOnWriteArrayList() }
            .add(FormedEntry(this, computeAssembledWorldBox(bp)))
    }

    private fun unregisterFormedComponents() {
        formedByWorld[block.world.name]?.removeAll { it.controller === this }
    }

    /** 计算 assembled 形状的世界坐标包围盒（与 Matrix3x3 旋转约定一致的内联变换）。 */
    private fun computeAssembledWorldBox(bp: Blueprint): BoundingBox {
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (be in bp.assembledShape.blocks) {
            val rx = be.position.x - centerOffset.x
            val ry = be.position.y - centerOffset.y
            val rz = be.position.z - centerOffset.z
            val wx: Int
            val wz: Int
            when (rotationSteps % 4) {
                1 -> { wx = controllerPos.x + rz; wz = controllerPos.z - rx }
                2 -> { wx = controllerPos.x - rx; wz = controllerPos.z - rz }
                3 -> { wx = controllerPos.x - rz; wz = controllerPos.z + rx }
                else -> { wx = controllerPos.x + rx; wz = controllerPos.z + rz }
            }
            val wy = controllerPos.y + ry
            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wz < minZ) minZ = wz
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
            if (wz > maxZ) maxZ = wz
        }
        return BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
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

/**
 * 区块加载时补转解体遗留的 scaffold 方块（由 MonolithLib 注册）。
 * 独立于 MNBController 实例，避免 companion 嵌套 object 的引用问题。
 */
object ScaffoldChunkLoader : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        val key = PositionCache.getChunkKey(event.chunk.x, event.chunk.z)
        val pending = MNBController.pendingScaffoldByChunk.remove(key) ?: return
        val controller = pending.controller
        val bp = controller.blueprint ?: return
        var pos = pending.positions.poll()
        while (pos != null) {
            controller.convertScaffoldBlockAt(bp, pos.x, pos.y, pos.z)
            pos = pending.positions.poll()
        }
        // 该控制器的补转队列已排空 → 触发转换完成回调（转换任务已完成且尚未触发时）
        val hasMore = MNBController.pendingScaffoldByChunk.values.any { it.controller === controller }
        if (!hasMore) {
            controller.fireScaffoldConversionComplete()
        }
    }
}
