package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.datatypes.RebarSerializers
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.integration.ProjectControllerRegistry
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.validation.predicate.rebarKeyOfPredicate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 工地展位方块 — 多方块的"定位桩"。
 *
 * ## 渲染方式
 * - ghost block 仅在玩家 RENDER_RADIUS 内显示（缩小 0.5 + 发光颜色指示）
 * - 通过 [renderForPlayer] 由 BuildSiteListener.onPlayerMove 驱动
 * - 展示实体仅对其半径内的玩家可见，并在无人观看时立即回收
 */
class BuildSiteAnchorBlock(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context), BlockBreakRebarBlockHandler {

    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block)) {
        _blueprintId = pdc.get(BLUEPRINT_ID_KEY, PersistentDataType.STRING)
        _facingName = pdc.get(FACING_KEY, PersistentDataType.STRING)
    }

    private val logger = MonolithLogger.getLogger("Anchor")

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "build_site_anchor")
        val MATERIAL = Material.LODESTONE

        private val BLUEPRINT_ID_KEY = NamespacedKey(MonolithLib.instance, "blueprint_id")
        private val FACING_KEY = NamespacedKey(MonolithLib.instance, "blueprint_facing")

        const val RENDER_RADIUS = 7
        const val RENDER_RADIUS_SQ = RENDER_RADIUS * RENDER_RADIUS

        /** 远距轮廓可见距离：玩家在 (RENDER_RADIUS, FAR_RENDER_DISTANCE] 内看到结构外框。 */
        const val FAR_RENDER_DISTANCE = 64.0
        const val FAR_RENDER_DISTANCE_SQ = FAR_RENDER_DISTANCE * FAR_RENDER_DISTANCE

        /** 单个工地最多同时渲染的幽灵实体数（超出的按距离优先级跳过，防止实体数爆炸）。 */
        const val MAX_VISIBLE_GHOSTS = 300
    }

    // ========== 持久化数据 ==========

    private var _blueprintId: String? = null
    private var _facingName: String? = null

    override fun write(pdc: PersistentDataContainer) {
        _blueprintId?.let { pdc.set(BLUEPRINT_ID_KEY, PersistentDataType.STRING, it) }
        _facingName?.let { pdc.set(FACING_KEY, PersistentDataType.STRING, it) }
    }

    val blueprintId: String? get() = _blueprintId

    val blueprint: Blueprint?
        get() {
            val id = _blueprintId ?: return null
            return try { MonolithAPI.getInstance().registry.get(id) } catch (_: Exception) { null }
        }

    val facing: Facing
        get() {
            val name = _facingName
            return if (name != null) try { Facing.valueOf(name) } catch (_: Exception) { Facing.NORTH } else Facing.NORTH
        }

    fun initialize(blueprintId: String, f: Facing) {
        _blueprintId = blueprintId
        _facingName = f.name
        invalidateWorldBox()
        resetGhostDataState()
        // ghost 数据异步构建（百万级 ~100ms 不再阻塞主线程）；就绪后由回调补注册 + 补渲染
        ensureGhostData()
    }

    /** Rebuild cached scaffold positions after a project hot reload. */
    fun refreshBlueprint() {
        removeAllRenderings()
        invalidateWorldBox()
        resetGhostDataState()
        ensureGhostData()
    }

    /** 重置 ghost 数据状态：使在途构建失效、清空数据与完成度计数器。 */
    private fun resetGhostDataState() {
        ghostDataBuildGen++
        ghostDataBuildInFlight = false
        ghostData = ArrayList()
        ghostByPos = HashMap()
        ghostSections = LinkedHashMap()
        matchedCount.set(0)
        completionCalibrated = false
        disposed = false
        registryRegistered = false
    }

    fun boundingBox(): BoundingBox {
        val box = worldBox() ?: return BoundingBox(0, 0, 0, 0, 0, 0)
        // 包含 anchor 自身位置（与旧实现一致）
        return BoundingBox(
            minOf(box.minX, block.x), minOf(box.minY, block.y), minOf(box.minZ, block.z),
            maxOf(box.maxX, block.x), maxOf(box.maxY, block.y), maxOf(box.maxZ, block.z)
        )
    }

    // ========== 世界包围盒缓存 ==========

    /** 由异步 ghost 构建写入；蓝图/朝向/位置变化时经 [invalidateWorldBox] 置空。 */
    @Volatile
    private var cachedWorldBox: BoundingBox? = null

    /**
     * 结构的世界坐标包围盒（scaffold 全量一次性计算已并入异步 ghost 构建，构建完成写入缓存；
     * 之后所有 covers/coversChunk/intersectsPlayerRange/boundingBox 均为 O(1)）。
     * 数据未就绪（异步构建中）时返回 null——调用方均按"未覆盖/跳过"处理，
     * 绝不在此同步遍历百万级方块（那正是解体卡顿的来源之一）。
     */
    fun worldBox(): BoundingBox? = cachedWorldBox

    private fun invalidateWorldBox() {
        cachedWorldBox = null
    }

    /**
     * 轻量覆盖判断：该位置是否属于本工地的世界包围盒（O(1)，基于缓存 AABB）。
     */
    fun covers(worldName: String, x: Int, y: Int, z: Int): Boolean {
        if (block.world.name != worldName) return false
        val box = worldBox() ?: return false
        return x in box.minX..box.maxX && y in box.minY..box.maxY && z in box.minZ..box.maxZ
    }

    /** 该工地是否覆盖指定区块（O(1)，供 chunk load/unload 事件使用）。 */
    fun coversChunk(cx: Int, cz: Int): Boolean {
        val box = worldBox() ?: return false
        return cx in (box.minX shr 4)..(box.maxX shr 4) && cz in (box.minZ shr 4)..(box.maxZ shr 4)
    }

    /**
     * 玩家位置是否与"幽灵渲染范围"相交（O(1)）：即玩家距工地包围盒任一面不超过 [radius]。
     * 用于 onPlayerMove 的粗筛（精确判断仍由 renderForPlayer 的逐 ghost 距离完成）。
     */
    fun intersectsPlayerRange(px: Int, py: Int, pz: Int, radius: Int): Boolean {
        val box = worldBox() ?: return false
        return px in (box.minX - radius)..(box.maxX + radius) &&
            py in (box.minY - radius)..(box.maxY + radius) &&
            pz in (box.minZ - radius)..(box.maxZ + radius)
    }

    // ========== Ghost 数据 ==========

    private var ghostData: MutableList<GhostEntry> = ArrayList()
    private data class SectionKey(val x: Int, val y: Int, val z: Int)
    private var ghostSections: MutableMap<SectionKey, MutableList<GhostEntry>> = LinkedHashMap()
    private var ghostByPos: MutableMap<Vector3i, GhostEntry> = HashMap()
    private val ghostDisplayEntities = ConcurrentHashMap<Vector3i, BlockDisplay>()
    private val viewersByGhost = ConcurrentHashMap<Vector3i, MutableSet<UUID>>()
    private val visibleGhostsByPlayer = ConcurrentHashMap<UUID, Set<Vector3i>>()

    // ---- 异步 ghost 构建状态：百万级构建移到 BuildSiteAsync，主线程不再卡 1M 循环 ----
    /** 是否已在 [BuildSiteRegistry] 注册（构建完成后补注册一次）。 */
    @Volatile private var registryRegistered = false

    /** 锚点是否已被移除（异步回调需要检查，避免给已移除的锚点补注册/渲染）。 */
    @Volatile private var disposed = false

    @Volatile private var ghostDataBuildInFlight = false
    @Volatile private var ghostDataBuildGen = 0

    /** 远距轮廓渲染器：玩家在幽灵半径外时显示结构外框。 */
    private var farOutline: top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer? = null
    private val outlineViewers = ConcurrentHashMap<UUID, Boolean>()

    private class GhostEntry(
        val worldPos: Vector3i,
        val relativePos: Vector3i,
        val previewBlockData: org.bukkit.block.data.BlockData,
        val predicate: top.mc506lw.monolith.validation.predicate.Predicate
    ) {
        /** 最近一次评估的匹配状态，配合 [BuildSiteAnchorBlock.matchedCount] 增量维护完成度。 */
        @Volatile
        var matched: Boolean = false
    }

    /** 供 EasyBuild / Printer 使用的 ghost 列表 */
    data class PublicGhostEntry(
        val worldPos: Vector3i,
        val previewBlockData: org.bukkit.block.data.BlockData,
        /** 若该位置要求 Rebar 方块，则为其 key（放置时应 placeBlock 而非 setBlockData） */
        val rebarKey: org.bukkit.NamespacedKey? = null
    )

    /** 获取所有 ghost 的公开副本（供 EasyBuild/Printer 使用）。自由位置无物可建，跳过。 */
    fun getGhostEntries(): List<PublicGhostEntry> {
        ensureGhostData()
        return ghostData
            .filter { it.predicate !is top.mc506lw.monolith.validation.predicate.FreeSpacePredicate }
            .map { PublicGhostEntry(it.worldPos, it.previewBlockData, it.predicate.rebarKeyOfPredicate()) }
    }

    /**
     * 确保 ghost 数据已构建：数据为空且未在途时异步构建（纯计算，可安全离线执行），
     * 完成后回投主线程应用并补注册/补渲染。百万级构建不再阻塞主线程。
     */
    private fun ensureGhostData() {
        if (!ghostData.isEmpty() || ghostDataBuildInFlight) return
        ghostDataBuildInFlight = true
        val gen = ghostDataBuildGen
        val bp = blueprint
        if (bp == null) {
            ghostData = ArrayList()
            ghostByPos = HashMap()
            ghostSections = LinkedHashMap()
            ghostDataBuildInFlight = false
            return
        }
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val rotationSteps = facing.rotationSteps
        val key = "ghostdata:${block.world.name}:${block.x}:${block.y}:${block.z}"
        BuildSiteAsync.enqueue(key, {
            buildGhostData(bp, controllerPos, rotationSteps)
        }, { result ->
            ghostDataBuildInFlight = false
            BuildSiteAsync.complete(key)
            // 已失效（重新 initialize/refreshBlueprint）或锚点已移除 → 丢弃结果
            if (gen != ghostDataBuildGen || disposed) return@enqueue
            if (result == null) return@enqueue
            ghostData = result.data
            ghostByPos = result.byPos
            ghostSections = result.sections
            result.worldBox?.let { cachedWorldBox = it }
            if (!registryRegistered) {
                registryRegistered = true
                BuildSiteRegistry.register(this)
            }
            // 数据就绪后补渲染附近玩家（构建窗口期内的渲染请求被跳过）
            Bukkit.getOnlinePlayers()
                .filter { it.world == block.world && it.location.distanceSquared(block.location) <= FAR_RENDER_DISTANCE_SQ }
                .forEach(::renderForPlayer)
        })
    }

    private class GhostDataResult(
        val data: MutableList<GhostEntry>,
        val byPos: MutableMap<Vector3i, GhostEntry>,
        val sections: MutableMap<SectionKey, MutableList<GhostEntry>>,
        val worldBox: BoundingBox?
    )

    /** 纯计算：由异步线程执行，不接触任何 Bukkit 对象（bp/controllerPos/rotationSteps 已捕获）。 */
    private fun buildGhostData(
        bp: Blueprint,
        controllerPos: Vector3i,
        rotationSteps: Int
    ): GhostDataResult? {
        val centerOffset = bp.meta.controllerOffset
        val blocks = bp.scaffoldShape.blocks

        // 预分配容量，避免 1M 级插入触发反复 resize
        val newData = ArrayList<GhostEntry>(blocks.size)
        val newByPos = HashMap<Vector3i, GhostEntry>(blocks.size)
        val newSections = LinkedHashMap<SectionKey, MutableList<GhostEntry>>(blocks.size / 256 + 1)

        // 与 worldBox() 同一份数据：顺带累加世界包围盒，省一次百万级遍历
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE

        for (blockEntry in blocks) {
            val rx = blockEntry.position.x - centerOffset.x
            val ry = blockEntry.position.y - centerOffset.y
            val rz = blockEntry.position.z - centerOffset.z
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
            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wz < minZ) minZ = wz
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
            if (wz > maxZ) maxZ = wz

            // 跳过控制器自身位置——它由 anchor/控制器占据，不参与铺设
            if (blockEntry.position == centerOffset) continue
            val rotatedData = if (rotationSteps % 4 == 0) {
                blockEntry.blockData // 无需克隆/旋转
            } else {
                BlockStateRotator.rotate(blockEntry.blockData.clone(), rotationSteps)
            }
            val worldPos = Vector3i(wx, wy, wz)
            // 自由位置（脚手架空气 + 成型同为空气）：任意方块都算匹配，用于融入地形。
            // 覆盖原 AirPredicate，updateGhostDisplay / requestCompletionCheck / 破坏判定自动生效。
            val basePredicate = blockEntry.predicate
                ?: top.mc506lw.monolith.validation.predicate.MaterialPredicate.of(rotatedData.material)
            val predicate = if (
                basePredicate is top.mc506lw.monolith.validation.predicate.AirPredicate &&
                bp.assembledShape.getBlockAt(blockEntry.position) == null
            ) {
                top.mc506lw.monolith.validation.predicate.FreeSpacePredicate
            } else {
                basePredicate
            }
            val entry = GhostEntry(
                worldPos = worldPos,
                relativePos = blockEntry.position,
                previewBlockData = rotatedData,
                // anchor 只用 testMaterialOnly / rebarKeyOfPredicate（RotatedPredicate 委托结果相同），
                // 直接存原始 predicate（同材质共享实例），省去百万次 RotatedPredicate 包装构造（曾占 13%+ CPU）
                predicate = predicate
            )
            newData.add(entry)
            newByPos[worldPos] = entry
            newSections.computeIfAbsent(sectionKey(worldPos)) { ArrayList() }.add(entry)
        }
        return GhostDataResult(
            newData, newByPos, newSections,
            if (blocks.isNotEmpty()) BoundingBox(minX, minY, minZ, maxX, maxY, maxZ) else null
        )
    }

    /**
     * 解体转换全部完成时由控制器回调：scaffold 即世界状态（转换本身按蓝图 predicate 生成），
     * 直接把完成度置为 100%，避免"解体后再定型"显示 0% 而需要敲一块方块触发校准。
     */
    fun onScaffoldConversionComplete() {
        ensureGhostData()
        val total = ghostData.size
        if (total == 0) return
        for (e in ghostData) e.matched = true
        matchedCount.set(total)
        completionCalibrated = true
        cachedCompletionRate = 1.0
        // 世界已还原为脚手架状态：清掉陈旧幽灵显示（转换窗口期内渲染的错误颜色，
        // 如"材质对但 PDC 没对上"的黄块），下次渲染按真实状态重建，消除计数器与显示脱节。
        removeAllRenderings()
    }

    /** Direct world-position lookup used by EasyBuild and Printer without a per-site global ghost index. */
    fun getGhostAt(worldPos: Vector3i): PublicGhostEntry? {
        val bp = blueprint ?: return null
        val relative = CoordinateTransform(facing).toRelativePosition(
            worldPos, Vector3i(block.x, block.y, block.z), bp.meta.controllerOffset
        )
        if (relative == bp.meta.controllerOffset) return null
        val entry = bp.scaffoldShape.getBlockAt(relative) ?: return null
        // 自由位置（脚手架空气 + 成型同为空气）：无物可建，跳过（否则 EasyBuild/Printer 会写入空气清掉地形）
        if (bp.isFreeSpacePosition(relative)) return null
        val rotated = BlockStateRotator.rotate(entry.blockData.clone(), facing.rotationSteps)
        return PublicGhostEntry(worldPos, rotated, entry.predicate?.rebarKeyOfPredicate())
    }

    // ========== 渲染 ==========

    /**
     * 由 [BuildSiteListener.onPlayerMove] 调用。
     * 渲染以"玩家位置"为中心（而非锚点）：
     * - 玩家周围 RENDER_RADIUS 内有幽灵 → 渲染逐方块 ghost（大型建筑远侧也能正常显示）
     * - 无近距离幽灵但距锚点 FAR_RENDER_DISTANCE 内 → 显示结构外框
     * - 否则清理该玩家的所有渲染
     */
    fun renderForPlayer(player: Player) {
        val world = block.world
        if (player.world != world) return

        ensureGhostData()
        if (ghostData.isEmpty()) {
            hideFromPlayer(player)
            return
        }

        val px = player.location.blockX
        val py = player.location.blockY
        val pz = player.location.blockZ

        val nearby = mutableMapOf<Vector3i, GhostEntry>()
        val sectionMinX = Math.floorDiv(px - RENDER_RADIUS, 16)
        val sectionMaxX = Math.floorDiv(px + RENDER_RADIUS, 16)
        val sectionMinY = Math.floorDiv(py - RENDER_RADIUS, 16)
        val sectionMaxY = Math.floorDiv(py + RENDER_RADIUS, 16)
        val sectionMinZ = Math.floorDiv(pz - RENDER_RADIUS, 16)
        val sectionMaxZ = Math.floorDiv(pz + RENDER_RADIUS, 16)
        for (sx in sectionMinX..sectionMaxX) for (sy in sectionMinY..sectionMaxY) for (sz in sectionMinZ..sectionMaxZ) {
            for (entry in ghostSections[SectionKey(sx, sy, sz)].orEmpty()) {
                if (distSqTo(entry.worldPos, px, py, pz) <= RENDER_RADIUS_SQ.toLong()) {
                    nearby[entry.worldPos] = entry
                }
            }
        }

        if (nearby.isNotEmpty()) {
            // 有近距离幽灵：渲染逐方块 ghost（并隐藏外框）
            hideFarOutlineFor(player)
            val previous = visibleGhostsByPlayer[player.uniqueId].orEmpty()
            (previous - nearby.keys).forEach { removeViewer(player, it) }
            val visible = mutableSetOf<Vector3i>()
            // 实体上限保护：最多渲染 MAX_VISIBLE_GHOSTS 个幽灵，超出按距离优先级
            val capped = nearby.values
                .map { it to distSqTo(it.worldPos, px, py, pz) }
                .sortedBy { it.second }
                .take(MAX_VISIBLE_GHOSTS)
                .map { it.first }
            for (entry in capped) {
                if (updateGhostDisplay(world, player, entry)) visible.add(entry.worldPos)
            }
            if (visible.isEmpty()) visibleGhostsByPlayer.remove(player.uniqueId) else visibleGhostsByPlayer[player.uniqueId] = visible
            return
        }

        // 无近距离幽灵：先清理残留的逐方块 ghost（玩家已离开幽灵渲染半径），
        // 距锚点较近 → 远距外框；否则全部清理
        visibleGhostsByPlayer.remove(player.uniqueId).orEmpty().forEach { removeViewer(player, it) }
        val distSqToAnchor = block.location.distanceSquared(player.location)
        if (distSqToAnchor <= FAR_RENDER_DISTANCE_SQ) {
            renderFarOutline(player)
        } else {
            hideFromPlayer(player)
        }
    }

    /** 清理该玩家的全部渲染（逐方块 ghost + 外框）。 */
    fun hideFromPlayer(player: Player) {
        visibleGhostsByPlayer.remove(player.uniqueId).orEmpty().forEach { removeViewer(player, it) }
        hideFarOutlineFor(player)
    }

    /** 区块卸载时只回收该区块内的幽灵实体，保留其他区块的渲染（避免整站闪没）。 */
    fun removeRenderingsInChunk(cx: Int, cz: Int) {
        if (ghostDisplayEntities.isEmpty()) return
        val toRemove = ghostDisplayEntities.keys.filter { it.x shr 4 == cx && it.z shr 4 == cz }
        toRemove.forEach { removeGhost(it) }
    }

    /** 远距外框渲染：为每位附近的玩家显示结构外框（红=未完成，绿=已完成）。 */
    private fun renderFarOutline(player: Player) {
        val bp = blueprint ?: return
        val outline = farOutline ?: run {
            val box = computeWorldBox(bp) ?: return
            val renderer = top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer(
                plugin = MonolithLib.instance,
                world = block.world,
                initialColor = if (isCompleteFast()) Color.GREEN else Color.RED,
                thickness = 0.1f
            )
            farOutline = renderer
            renderer.show(box)
            renderer
        }
        if (outlineViewers.putIfAbsent(player.uniqueId, true) == null) {
            outline.color = if (isCompleteFast()) Color.GREEN else Color.RED
        }
    }

    private fun hideFarOutlineFor(player: Player) {
        if (outlineViewers.remove(player.uniqueId) != null && outlineViewers.isEmpty()) {
            farOutline?.hide()
            farOutline = null
        }
    }

    /** 轻量完成判断：使用异步缓存，避免远距渲染触发全量扫描。 */
    private fun isCompleteFast(): Boolean = cachedCompletionRate >= 1.0

    private fun computeWorldBox(bp: Blueprint): top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer.BoundingBoxData? {
        val box = worldBox() ?: return null
        return top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ
        )
    }

    private fun distSqTo(pos: Vector3i, px: Int, py: Int, pz: Int): Long {
        val dx = pos.x - px
        val dy = pos.y - py
        val dz = pos.z - pz
        return dx.toLong() * dx + dy.toLong() * dy + dz.toLong() * dz
    }

    fun removeAllRenderings() {
        ghostDisplayEntities.values.forEach { it.remove() }
        ghostDisplayEntities.clear()
        viewersByGhost.clear()
        visibleGhostsByPlayer.clear()
        outlineViewers.clear()
        farOutline?.hide()
        farOutline = null
    }

    /**
     * 单位置增量渲染（放置/破坏方块后调用）。只处理该位置对应的 ghost，
     * 并对"正在观看该位置"以及"位于该位置幽灵半径内"的所有玩家即时刷新，
     * 保证放对后幽灵立刻消失、放错/挖掉后幽灵立刻出现，多人视角一致。
     */
    fun renderPositionForPlayer(block: Block) {
        val world = block.world
        ensureGhostData()
        val pos = Vector3i(block.x, block.y, block.z)
        val entry = ghostByPos[pos] ?: return

        for (p in Bukkit.getOnlinePlayers()) {
            if (p.world != world) continue
            val viewing = visibleGhostsByPlayer[p.uniqueId]?.contains(pos) == true
            val near = distSqTo(pos, p.location.blockX, p.location.blockY, p.location.blockZ) <= RENDER_RADIUS_SQ.toLong()
            if (!viewing && !near) continue
            if (updateGhostDisplay(world, p, entry)) {
                visibleGhostsByPlayer.compute(p.uniqueId) { _, old ->
                    (old ?: emptySet()) + pos
                }
            } else {
                visibleGhostsByPlayer.compute(p.uniqueId) { _, old ->
                    val remaining = (old ?: emptySet()) - pos
                    if (remaining.isEmpty()) null else remaining
                }
            }
        }
    }

    fun removePlayerRenderings(player: Player) {
        visibleGhostsByPlayer.remove(player.uniqueId).orEmpty().forEach { removeViewer(player, it) }
        hideFarOutlineFor(player)
    }

    private fun updateGhostDisplay(world: org.bukkit.World, player: Player, entry: GhostEntry): Boolean {
        // 区块未加载时跳过：避免主线程同步加载区块造成卡顿；区块加载后由 onChunkLoad 重新渲染
        if (!world.isChunkLoaded(entry.worldPos.x shr 4, entry.worldPos.z shr 4)) return false
        val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
        val expectedMat = entry.previewBlockData.material

        val context = top.mc506lw.monolith.validation.predicate.Predicate.PredicateContext(
            position = entry.relativePos,
            block = block
        )
        // 增量维护完成度计数器：每次评估都与上次状态比对，只调整差值
        val matched = entry.predicate.testMaterialOnly(block.blockData, context)
        if (entry.matched != matched) {
            entry.matched = matched
            if (matched) matchedCount.incrementAndGet() else matchedCount.decrementAndGet()
        }
        // 用 predicate 判定（Rebar 条目会正确匹配已放置的 Rebar 方块，而非跳过）
        if (matched) {
            removeGhost(entry.worldPos)
            return false
        }

        // 颜色：材质命中但状态不匹配 → 黄；完全不对 → 红
        val glowColor = if (block.type == expectedMat) Color.YELLOW else Color.RED

        val existing = ghostDisplayEntities[entry.worldPos]
        if (existing != null && existing.isValid) {
            if (existing.glowColorOverride != glowColor) {
                existing.glowColorOverride = glowColor
            }
            addViewer(player, entry.worldPos, existing)
            return true
        }

        existing?.remove()

        val location = Location(world,
            entry.worldPos.x.toDouble(),
            entry.worldPos.y.toDouble(),
            entry.worldPos.z.toDouble())

        try {
            val display = world.spawn(location, BlockDisplay::class.java) { d ->
                d.block = entry.previewBlockData
                d.isGlowing = true
                d.glowColorOverride = glowColor
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                d.setVisibleByDefault(false)

                val scale = 0.5f
                d.transformation = Transformation(
                    Vector3f(0.25f, 0.25f, 0.25f),
                    AxisAngle4f(),
                    Vector3f(scale, scale, scale),
                    AxisAngle4f()
                )
            }
            ghostDisplayEntities[entry.worldPos] = display
            addViewer(player, entry.worldPos, display)
            return true
        } catch (_: Exception) { return false }
    }

    private fun addViewer(player: Player, position: Vector3i, display: BlockDisplay) {
        viewersByGhost.computeIfAbsent(position) { ConcurrentHashMap.newKeySet() }.add(player.uniqueId)
        player.showEntity(MonolithLib.instance, display)
    }

    private fun removeViewer(player: Player, position: Vector3i) {
        player.hideEntity(MonolithLib.instance, ghostDisplayEntities[position] ?: return)
        val viewers = viewersByGhost[position] ?: return
        viewers.remove(player.uniqueId)
        if (viewers.isEmpty()) removeGhost(position)
    }

    private fun removeGhost(position: Vector3i) {
        ghostDisplayEntities.remove(position)?.remove()
        viewersByGhost.remove(position)
        visibleGhostsByPlayer.forEach { (viewerId, positions) ->
            val remaining = positions - position
            if (remaining.isEmpty()) visibleGhostsByPlayer.remove(viewerId, positions)
            else visibleGhostsByPlayer.replace(viewerId, positions, remaining)
        }
    }

    private fun sectionKey(position: Vector3i) = SectionKey(
        Math.floorDiv(position.x, 16), Math.floorDiv(position.y, 16), Math.floorDiv(position.z, 16)
    )

    // ========== 完成率 ==========

    /** 增量维护的已匹配 ghost 数：由 [updateGhostDisplay] 每次评估时同步，O(1) 读取。 */
    private val matchedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 是否已完成一次全量校准（把逐位置计数器同步到世界真实状态）。 */
    @Volatile
    private var completionCalibrated = false

    /** 供定型等路径判断：计数器是否已与真实世界同步（未校准时先触发校准再判定）。 */
    fun isCompletionCalibrated(): Boolean = completionCalibrated

    fun getCompletionRate(): Double {
        val total = getGhostCount()
        return if (total == 0) 0.0 else (matchedCount.get().toDouble() / total).coerceIn(0.0, 1.0)
    }

    fun getMatchedCount(): Int = matchedCount.get()

    /** 脚手架 ghost 条目总数（未匹配基数，等于 getIssues().size + getMatchedCount()）。 */
    fun getGhostCount(): Int {
        ensureGhostData()
        return ghostData.size
    }

    fun isComplete(): Boolean = getCompletionRate() >= 1.0

    @Volatile
    private var asyncCompletionReported = false

    /** 异步缓存完成率，供远距外框颜色使用（避免全量扫描）。 */
    @Volatile
    private var cachedCompletionRate: Double = 0.0

    /**
     * 异步请求完成度：
     * - 首次调用做一次后台全量校准（把逐位置计数器同步到世界真实状态，回投主线程应用）；
     * - 之后每次放置/破坏只走 O(1) 计数器路径，不再每放一块方块都全量扫描百万级 ghost。
     *
     * 完成提示：全量校准完成且 100% 匹配，或计数器达到总数时提示一次。
     */
    fun requestCompletionCheck(player: Player?) {
        if (!completionCalibrated) {
            if (completionScanQueued) return
            ensureGhostData()
            if (ghostData.isEmpty()) return // 数据构建中：后续事件/移动会再次请求
            completionScanQueued = true
            val key = "completion:${block.world.name}:${block.x}:${block.y}:${block.z}"
            BuildSiteAsync.enqueue(key, {
                val world = block.world
                val total = ghostData.size
                if (total == 0) return@enqueue CompletionResult(0, 0, 0, null)
                var matched = 0
                var scanned = 0
                val flags = BooleanArray(total)
                var i = 0
                for (entry in ghostData) {
                    if (!world.isChunkLoaded(entry.worldPos.x shr 4, entry.worldPos.z shr 4)) {
                        i++
                        continue
                    }
                    scanned++
                    val b = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
                    val context = top.mc506lw.monolith.validation.predicate.Predicate.PredicateContext(
                        position = entry.relativePos, block = b
                    )
                    if (entry.predicate.testMaterialOnly(b.blockData, context)) {
                        flags[i] = true
                        matched++
                    }
                    i++
                }
                CompletionResult(matched, scanned, total, flags)
            }, { result ->
                completionScanQueued = false
                BuildSiteAsync.complete(key)
                completionCalibrated = true
                val flags = result.flags
                if (flags != null) {
                    // 主线程应用：按扫描结果统一同步 matched 标志与计数器
                    val entries = ghostData
                    var count = 0
                    for (idx in flags.indices) {
                        val e = entries[idx]
                        if (e.matched != flags[idx]) {
                            e.matched = flags[idx]
                        }
                        if (flags[idx]) count++
                    }
                    matchedCount.set(count)
                    // 全量校准确认 100% 匹配：清掉可能残留的陈旧幽灵显示（计数器与显示脱节）。
                    // 例：解体后转换窗口期内渲染的错误颜色幽灵从未被重新评估，校准前一直挂着。
                    if (result.scanned == result.total && result.matched == result.total) {
                        removeAllRenderings()
                    }
                }
                val total = result.total
                val rate = if (total == 0) 0.0 else result.matched.toDouble() / total
                cachedCompletionRate = rate
                // 全量扫描（所有区块已加载）且全部匹配才提示完成
                if (total > 0 && result.scanned == total && result.matched == total && !asyncCompletionReported) {
                    asyncCompletionReported = true
                    player?.sendMessage(I18n.Message.BuildSite.completedHint)
                } else if (rate < 1.0) {
                    asyncCompletionReported = false
                }
            })
            return
        }

        // 已校准：O(1) 计数器路径
        val total = getGhostCount()
        val matched = matchedCount.get()
        cachedCompletionRate = if (total == 0) 0.0 else matched.toDouble() / total
        if (total > 0 && matched >= total && !asyncCompletionReported) {
            asyncCompletionReported = true
            player?.sendMessage(I18n.Message.BuildSite.completedHint)
        } else if (matched < total) {
            asyncCompletionReported = false
        }
    }

    private class CompletionResult(val matched: Int, val scanned: Int, val total: Int, val flags: BooleanArray?)

    @Volatile
    private var completionScanQueued = false

    fun resetCompletionReport() {
        asyncCompletionReported = false
    }

    /** 逐位置检查，返回不匹配的位置与原因，供定型失败时给玩家反馈。 */
    data class SiteIssue(val worldPos: Vector3i, val hint: String, val isRebar: Boolean, val preview_hint: String)

    fun getIssues(): List<SiteIssue> {
        val world = block.world
        ensureGhostData()
        if (ghostData.isEmpty()) return emptyList()
        val issues = mutableListOf<SiteIssue>()
        for (entry in ghostData) {
            if (!world.isChunkLoaded(entry.worldPos.x shr 4, entry.worldPos.z shr 4)) continue
            val b = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
            val context = top.mc506lw.monolith.validation.predicate.Predicate.PredicateContext(
                position = entry.relativePos,
                block = b
            )
            val isRebar = entry.predicate.rebarKeyOfPredicate() != null
            val ok = entry.predicate.testMaterialOnly(b.blockData, context)
            if (!ok) {
                // 严格修复（不是"放啥都行"）：仅当磁盘记录（区块 PDC 序列化列表）证明该位置
                // 本来就是 expected key 的 rebar、而内存注册表把它丢了时才补注册。
                val repaired = isRebar &&
                    b.type == entry.previewBlockData.material &&
                    Bukkit.isPrimaryThread() &&
                    repairDroppedRebar(entry, b)
                if (repaired) continue
                // 附带"世界方块实际状态"，直接回答"为什么黄"：可能是无 PDC 的普通同材质方块，
                // 或不同类型的 rebar（key 对不上）
                val actualKey = top.mc506lw.monolith.feature.rebar.RebarAdapter.getRebarBlockKey(b)
                val actualDesc = when {
                    actualKey != null -> "Rebar $actualKey"
                    b.type.isAir -> "空气"
                    else -> "普通 ${b.type.name}"
                }
                val hint = "${entry.predicate.hint ?: entry.previewBlockData.material.name}（实际: $actualDesc）"
                issues.add(SiteIssue(
                    entry.worldPos,
                    hint,
                    isRebar,
                    entry.previewBlockData.material.name
                ))
            }
        }
        return issues
    }

    /**
     * 严格补注册：内存注册表丢失、但区块 PDC 磁盘记录证明该位置本来就是 [expected] rebar 的方块，
     * 重新注册回 Rebar 内存注册表。普通方块（磁盘无记录）不处理。
     * 每一条退出路径都打日志，便于定位失败环节。
     */
    private fun repairDroppedRebar(entry: GhostEntry, b: Block): Boolean {
        val rebarKey = entry.predicate.rebarKeyOfPredicate() ?: return false
        if (BlockStorage.isRebarBlock(b)) {
            logger.warn("注册表恢复", "跳过：方块已在注册表", "pos" to "${b.x},${b.y},${b.z}", "key" to rebarKey.toString())
            return false
        }
        val chunkKey = readChunkStoredRebarKey(b)
        if (chunkKey != rebarKey.toString()) {
            logger.warn(
                "注册表恢复", "跳过：磁盘记录与预期不符", "pos" to "${b.x},${b.y},${b.z}",
                "disk" to chunkKey, "expected" to rebarKey.toString()
            )
            return false
        }
        return try {
            val placed = BlockStorage.placeBlock(b, rebarKey)
            if (placed != null && BlockStorage.get(b)?.schema?.key == rebarKey) {
                logger.warn(
                    "注册表恢复",
                    "磁盘证明的 rebar 已补注册回内存注册表",
                    "pos" to "${b.x},${b.y},${b.z}",
                    "key" to rebarKey.toString()
                )
                true
            } else {
                logger.warn(
                    "注册表恢复", "placeBlock 返回 null（很可能被 PreRebarBlockPlaceEvent 取消）",
                    "pos" to "${b.x},${b.y},${b.z}", "key" to rebarKey.toString()
                )
                false
            }
        } catch (e: Exception) {
            logger.warn(
                "注册表恢复", "placeBlock 抛异常", "pos" to "${b.x},${b.y},${b.z}",
                "key" to rebarKey.toString(), "err" to (e.message ?: "未知")
            )
            false
        }
    }

    /** 读区块 PDC 的 rebar 序列化列表中该位置存的 key（磁盘层记录），无则 null。 */
    private fun readChunkStoredRebarKey(b: Block): String? {
        return try {
            val list = b.chunk.persistentDataContainer
                .get(BlockStorage.rebarBlocksKey, BlockStorage.rebarBlocksType) ?: return null
            val target = io.github.pylonmc.rebar.util.position.BlockPosition.asLong(b.x, b.y, b.z)
            for (element in list) {
                val pos = element.get(RebarBlock.rebarBlockPositionKey, RebarSerializers.LONG) ?: continue
                if (pos == target) {
                    return element.get(RebarBlock.rebarBlockKeyKey, RebarSerializers.NAMESPACED_KEY)?.toString()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ========== 定型 ==========

    fun finalizeWithWrench(player: Player?): Boolean {
        val bp = blueprint ?: return false
        val controllerKey = bp.controllerRebarKey ?: return false
        if (!ProjectControllerRegistry.isRegistered(bp)) {
            player?.sendMessage(I18n.Message.Wrench.errControllerNotRegistered(controllerKey.toString()))
            return false
        }
        if (!isComplete()) return false

        removeAllRenderings()

        val world = block.world
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps

        // 1. 把 scaffold 方块替换为 assembled 方块（非控制器位置）
        //    按"该位置 assembled 阶段定义了什么"决定：普通方块 → 无条件替换（含玩家摆的 rebar）；rebar → 保留
        for (be in bp.assembledShape.blocks) {
            if (be.position == centerOffset) continue // 控制器位置由下面单独处理
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
            val b = world.getBlockAt(wx, wy, wz)
            val rotatedData = if (rotationSteps % 4 == 0) {
                be.blockData
            } else {
                BlockStateRotator.rotate(be.blockData.clone(), rotationSteps)
            }
            try {
                // 用原始 predicate 判断（避免百万次 effectivePredicate 兜底构造）；BlockEntry 的
                // predicate 不会被 RotatedPredicate 包裹，is RebarPredicate 语义与 effectivePredicate 一致
                if (be.predicate is top.mc506lw.monolith.validation.predicate.RebarPredicate) {
                    // assembled 该位置要求 Rebar 方块：保留玩家已放置的 rebar
                    continue
                }
                // assembled 是普通方块：无论当前是 scaffold 普通块还是玩家摆的 rebar，都替换。
                // isComplete() 已保证材质匹配——材质一致时几乎不可能是 rebar，跳过 PDC 读取
                if (b.type != rotatedData.material && BlockStorage.isRebarBlock(b)) {
                    BlockStorage.breakBlock(
                        b,
                        BlockBreakContext.PluginBreak(b, normallyDrops = false, shouldSetToAir = true)
                    )
                }
                b.setBlockData(rotatedData, false)
            } catch (e: Exception) {
                logger.warn { "finalize: 替换方块失败 @ ($wx, $wy, $wz): ${e.message}" }
            }
        }

        // 2. 移除 anchor，放置真实控制器
        //    breakBlock(PluginBreak(normallyDrops=false, shouldSetToAir=false)) 会正确从 BlockStorage 注销 anchor，
        //    不掉落物品，且不主动改方块类型；之后我们手动 setType(AIR) 再 placeBlock 控制器。
        try {
            BlockStorage.breakBlock(
                block,
                BlockBreakContext.PluginBreak(block, normallyDrops = false, shouldSetToAir = false)
            )
        } catch (e: Exception) {
            logger.warn { "finalize: breakBlock(anchor) 失败: ${e.message}" }
        }
        block.setType(Material.AIR, false)
        val placedRebar = BlockStorage.placeBlock(block, controllerKey)
        if (placedRebar == null) {
            logger.warn { "Failed to place controller block for ${bp.id}" }
            return false
        }

        val blueprintId = _blueprintId
        if (blueprintId != null && placedRebar is top.mc506lw.monolith.integration.MNBController) {
            placedRebar.initialize(blueprintId, facing)
        }

        // 3. 触发成型（此时世界已是 assembled 方块，checker 应通过）
        try {
            val rebarMultiblock = placedRebar as? io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
            if (rebarMultiblock != null) {
                val formed = rebarMultiblock.checkFormed()
                logger.info { "Formation check for ${bp.id}: $formed (${placedRebar::class.java.simpleName})" }
                if (formed) rebarMultiblock.onMultiblockFormed()
            }
        } catch (_: Exception) { }

        player?.sendMessage(I18n.Message.BuildSite.finalized)
        logger.info { "Finalized ${bp.id} at (${block.x}, ${block.y}, ${block.z})" }
        return true
    }

    // ========== 生命周期 ==========

    override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext) {
        drops.clear()
        disposed = true
        removeAllRenderings()
        // 无论何种原因删除 anchor，都清理 EasyBuild/Printer 索引中本 anchor 的条目，
        // 避免 ghost 残留在已不存在的工地上。
        BuildSiteRegistry.unregister(this)

        // 程序化无掉落删除（如 finalize 定型调用 breakBlock(normallyDrops=false)）：不要掉落展位
        if (context is BlockBreakContext.PluginBreak && !context.normallyDrops) {
            return
        }

        // 掉落带 blueprint_id 的展位物品（让玩家可以重新放置）
        val bpId = _blueprintId
        val drop = if (bpId != null) {
            BuildSiteAnchorItem.createItem(bpId)
        } else {
            io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                .rebar(MATERIAL, KEY)
                .name(top.mc506lw.monolith.common.I18n.translatable("item.build_site_anchor.name"))
                .build()
        }
        drops.add(drop)
    }

    override fun postLoad() {
        invalidateWorldBox()
        resetGhostDataState()
        ensureGhostData()
    }
}
