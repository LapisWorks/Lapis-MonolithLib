package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
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
        initGhostData()
        BuildSiteRegistry.register(this)
    }

    /** Rebuild cached scaffold positions after a project hot reload. */
    fun refreshBlueprint() {
        removeAllRenderings()
        ghostData.clear()
        initGhostData()
        Bukkit.getOnlinePlayers()
            .filter { it.world == block.world && it.location.distanceSquared(block.location) <= 20.0 * 20.0 }
            .forEach(::renderForPlayer)
    }

    fun boundingBox(): BoundingBox {
        if (ghostData.isEmpty()) initGhostData()
        val positions = ghostData.map { it.worldPos } + Vector3i(block.x, block.y, block.z)
        return BoundingBox(
            positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z },
            positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z }
        )
    }

    // ========== Ghost 数据 ==========

    private val ghostData = mutableListOf<GhostEntry>()
    private data class SectionKey(val x: Int, val y: Int, val z: Int)
    private val ghostSections = mutableMapOf<SectionKey, MutableList<GhostEntry>>()
    private val ghostDisplayEntities = ConcurrentHashMap<Vector3i, BlockDisplay>()
    private val viewersByGhost = ConcurrentHashMap<Vector3i, MutableSet<UUID>>()
    private val visibleGhostsByPlayer = ConcurrentHashMap<UUID, Set<Vector3i>>()

    private class GhostEntry(
        val worldPos: Vector3i,
        val relativePos: Vector3i,
        val previewBlockData: org.bukkit.block.data.BlockData,
        val predicate: top.mc506lw.monolith.validation.predicate.Predicate
    )

    /** 供 EasyBuild / Printer 使用的 ghost 列表 */
    data class PublicGhostEntry(
        val worldPos: Vector3i,
        val previewBlockData: org.bukkit.block.data.BlockData
    )

    /** 获取所有 ghost 的公开副本（供 EasyBuild/Printer 使用） */
    fun getGhostEntries(): List<PublicGhostEntry> {
        if (ghostData.isEmpty()) initGhostData()
        return ghostData.map { PublicGhostEntry(it.worldPos, it.previewBlockData) }
    }

    private fun initGhostData() {
        ghostData.clear()
        ghostSections.clear()
        val bp = blueprint ?: return
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps

        for (blockEntry in bp.scaffoldShape.blocks) {
            // 跳过控制器自身位置——它由 anchor/控制器占据，不参与铺设
            if (blockEntry.position == centerOffset) continue
            val worldPos = transform.toWorldPosition(controllerPos, blockEntry.position, centerOffset)
            val rotatedData = BlockStateRotator.rotate(blockEntry.blockData.clone(), rotationSteps)
            val entry = GhostEntry(
                worldPos = worldPos,
                relativePos = blockEntry.position,
                previewBlockData = rotatedData,
                predicate = top.mc506lw.monolith.validation.predicate.RotatedPredicate(
                    blockEntry.effectivePredicate,
                    rotationSteps
                )
            )
            ghostData.add(entry)
            ghostSections.computeIfAbsent(sectionKey(worldPos)) { mutableListOf() }.add(entry)
        }
    }

    /** Direct world-position lookup used by EasyBuild and Printer without a per-site global ghost index. */
    fun getGhostAt(worldPos: Vector3i): PublicGhostEntry? {
        val bp = blueprint ?: return null
        val relative = CoordinateTransform(facing).toRelativePosition(
            worldPos, Vector3i(block.x, block.y, block.z), bp.meta.controllerOffset
        )
        if (relative == bp.meta.controllerOffset) return null
        val entry = bp.scaffoldShape.getBlockAt(relative.x, relative.y, relative.z) ?: return null
        val rotated = BlockStateRotator.rotate(entry.blockData.clone(), facing.rotationSteps)
        return PublicGhostEntry(worldPos, rotated)
    }

    // ========== 渲染 ==========

    /**
     * 由 [BuildSiteListener.onPlayerMove] 调用。
     * 1. 找出当前玩家附近应该显示的 ghost
     * 2. 不在视野内的 ghost 会进入 cleanup 候选
     * 3. cleanup 基于所有在线玩家的可见性并集，没人看的才真正移除
     */
    fun renderForPlayer(player: Player) {
        val world = block.world
        if (player.world != world) return
        if (ghostData.isEmpty()) initGhostData()
        if (ghostData.isEmpty()) return

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
                val dx = entry.worldPos.x - px
                val dy = entry.worldPos.y - py
                val dz = entry.worldPos.z - pz
                val distSq = dx.toLong() * dx + dy.toLong() * dy + dz.toLong() * dz

                if (distSq <= RENDER_RADIUS_SQ.toLong()) {
                    nearby[entry.worldPos] = entry
                }
            }
        }
        val previous = visibleGhostsByPlayer[player.uniqueId].orEmpty()
        (previous - nearby.keys).forEach { removeViewer(player, it) }
        val visible = mutableSetOf<Vector3i>()
        nearby.values.forEach { entry -> if (updateGhostDisplay(world, player, entry)) visible.add(entry.worldPos) }
        if (visible.isEmpty()) visibleGhostsByPlayer.remove(player.uniqueId) else visibleGhostsByPlayer[player.uniqueId] = visible
    }

    fun removeAllRenderings() {
        ghostDisplayEntities.values.forEach { it.remove() }
        ghostDisplayEntities.clear()
        viewersByGhost.clear()
        visibleGhostsByPlayer.clear()
    }

    fun removePlayerRenderings(player: Player) {
        visibleGhostsByPlayer.remove(player.uniqueId).orEmpty().forEach { removeViewer(player, it) }
    }

    private fun updateGhostDisplay(world: org.bukkit.World, player: Player, entry: GhostEntry): Boolean {
        val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
        val expectedMat = entry.previewBlockData.material

        val context = top.mc506lw.monolith.validation.predicate.Predicate.PredicateContext(
            position = entry.relativePos,
            block = block
        )
        val isCorrect = entry.predicate.test(block.blockData, context) && !BlockStorage.isRebarBlock(block)
        if (isCorrect) {
            removeGhost(entry.worldPos)
            return false
        }

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

    fun getCompletionRate(): Double {
        val world = block.world
        if (ghostData.isEmpty()) initGhostData()
        if (ghostData.isEmpty()) return 1.0

        var matched = 0
        for (entry in ghostData) {
            val b = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
            if (b.type == entry.previewBlockData.material && !BlockStorage.isRebarBlock(b)) {
                matched++
            }
        }
        val total = ghostData.size
        return if (total == 0) 1.0 else matched.toDouble() / total
    }

    fun isComplete(): Boolean = getCompletionRate() >= 1.0

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
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(block.x, block.y, block.z)
        val centerOffset = bp.meta.controllerOffset
        val rotationSteps = facing.rotationSteps

        // 1. 把 scaffold 方块替换为 assembled 方块（非控制器位置）
        for (be in bp.assembledShape.blocks) {
            if (be.position == centerOffset) continue // 控制器位置由下面单独处理
            val worldPos = transform.toWorldPosition(controllerPos, be.position, centerOffset)
            val b = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            val rotatedData = BlockStateRotator.rotate(be.blockData.clone(), rotationSteps)
            try {
                if (!BlockStorage.isRebarBlock(b)) {
                    b.setBlockData(rotatedData, false)
                }
            } catch (e: Exception) {
                logger.warn { "finalize: 替换方块失败 @ ${worldPos}: ${e.message}" }
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
        initGhostData()
        BuildSiteRegistry.register(this)
    }
}
