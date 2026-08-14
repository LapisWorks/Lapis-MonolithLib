package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 预览"实体方块"数据：单个世界坐标 + 该位置的真实方块数据。 */
class PreviewBlockData(val pos: Vector3i, val blockData: BlockData)

/**
 * 预览幽灵数据：按 XZ 区块列分片（chunkKey = (cx shl 32) or (cz and 0xFFFFFFFF)），
 * 渲染时只取玩家周边若干列的片，不遍历全量（百万级结构也安全）。
 */
class PreviewGhostData(val cells: Map<Long, List<PreviewBlockData>>, val total: Int)

object BuildSitePreviewManager {

    private val logger = MonolithLogger.getLogger("Preview")
    
    private val activePreviews = ConcurrentHashMap<UUID, BuildSitePreview>()
    private val playerPreviews = ConcurrentHashMap<UUID, UUID>()
    private val previewTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val renderTasks = ConcurrentHashMap<UUID, BukkitTask>()

    /** 预览实体方块的渲染半径（格）。只渲染玩家周边这一圈，客户端零压力。 */
    private const val PREVIEW_RENDER_RADIUS = 16
    /** 预览实体方块上限，超过不再生成。 */
    private const val MAX_PREVIEW_DISPLAYS = 300
    
    fun startPreview(
        player: Player,
        blueprint: Blueprint,
        anchorLocation: Location,
        facing: Facing,
        validationResult: ValidationResult
    ): BuildSitePreview? {
        stopPreview(player)
        
        val world = anchorLocation.world ?: return null
        
        val preview = BuildSitePreview(
            id = UUID.randomUUID(),
            playerId = player.uniqueId,
            blueprintId = blueprint.id,
            world = world,
            boundingBox = validationResult.boundingBox,
            validationResult = validationResult,
            facing = facing,
            anchorLocation = anchorLocation.clone()
        )
        
        activePreviews[preview.id] = preview
        playerPreviews[player.uniqueId] = preview.id
        
        preview.show()
        
        scheduleAutoCancel(player)
        startRenderTask(player, preview)
        
        return preview
    }
    
    fun movePreviewTo(player: Player, newAnchorLocation: Location, newFacing: Facing): Boolean {
        val preview = getPreview(player) ?: return false
        if (!preview.isActive) return false

        val newAnchorX = newAnchorLocation.blockX
        val newAnchorY = newAnchorLocation.blockY
        val newAnchorZ = newAnchorLocation.blockZ

        val offsetX = newAnchorX - preview.anchorLocation.blockX
        val offsetY = newAnchorY - preview.anchorLocation.blockY
        val offsetZ = newAnchorZ - preview.anchorLocation.blockZ

        if (offsetX == 0 && offsetY == 0 && offsetZ == 0 && preview.facing == newFacing) return true

        // 位置或朝向任一变化都会使"实体方块"预览数据失效，重建（异步）
        preview.invalidateGhostData()

        preview.anchorLocation.x = newAnchorX.toDouble()
        preview.anchorLocation.y = newAnchorY.toDouble()
        preview.anchorLocation.z = newAnchorZ.toDouble()

        if (preview.facing != newFacing) {
            logger.debug("preview=${preview.id}", "预览朝向变更", "player" to player.name, "oldFacing" to preview.facing, "newFacing" to newFacing)

            val blueprint = top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.get(preview.blueprintId)
            if (blueprint != null) {
                // 旋转走 O(1) 轻量校验（AABB/高度/站点冲突），不遍历方块
                val newValidationResult = BuildSiteValidator.validateLight(blueprint, newAnchorLocation, newFacing)

                logger.debug("preview=${preview.id}", "预览框已旋转", "facing" to newFacing, "bounds" to MonolithLogger.ModuleLogger.formatCoordRange(
                    newValidationResult.boundingBox.minX, newValidationResult.boundingBox.minY, newValidationResult.boundingBox.minZ,
                    newValidationResult.boundingBox.maxX, newValidationResult.boundingBox.maxY, newValidationResult.boundingBox.maxZ
                ))

                val newBoxData = SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
                    newValidationResult.boundingBox.minX, newValidationResult.boundingBox.minY, newValidationResult.boundingBox.minZ,
                    newValidationResult.boundingBox.maxX, newValidationResult.boundingBox.maxY, newValidationResult.boundingBox.maxZ
                )

                // 外框绕锚点垂直轴物理旋转（圆弧插值），而非直线滑动
                val oldFacing = preview.facing
                val delta = ((newFacing.ordinal - oldFacing.ordinal) % 4 + 4) % 4
                val degrees = when (delta) {
                    1 -> -90
                    3 -> 90
                    2 -> 180
                    else -> 0
                }
                val pivotX = preview.anchorLocation.blockX + 0.5
                val pivotZ = preview.anchorLocation.blockZ + 0.5
                preview.boxRenderer?.rotateTo(newBoxData, pivotX, pivotZ, degrees)

                preview.facing = newFacing
                preview.boundingBox = newValidationResult.boundingBox
                preview.validationResult = newValidationResult
                // 朝向变了：旧错误标记位置失效，按新校验结果重建（旧实现只做偏移平移，标记会停留在错误位置）
                preview.rebuildErrorMarkers()
            } else {
                preview.boxRenderer?.updatePosition(offsetX, offsetY, offsetZ)
                teleportMarkersByOffset(preview, offsetX, offsetY, offsetZ)
            }
        } else {
            preview.boxRenderer?.updatePosition(offsetX, offsetY, offsetZ)
            teleportMarkersByOffset(preview, offsetX, offsetY, offsetZ)
        }

        resetAutoCancel(player)

        return true
    }

    /**
     * 蹲下 + 滚轮旋转预览朝向（外框随滚动转动）。位置不变，只换 facing。
     */
    fun rotatePreview(player: Player, clockwise: Boolean): Boolean {
        val preview = getPreview(player) ?: return false
        if (!preview.isActive) return false
        val newFacing = if (clockwise) preview.facing.rotateClockwise() else preview.facing.rotateCounterClockwise()
        return movePreviewTo(player, preview.anchorLocation, newFacing)
    }

    private fun teleportMarkersByOffset(preview: BuildSitePreview, offsetX: Int, offsetY: Int, offsetZ: Int) {
        preview.errorMarkerDisplays.forEach { marker ->
            if (marker.isValid) {
                marker.teleport(Location(preview.world,
                    marker.location.x + offsetX,
                    marker.location.y + offsetY,
                    marker.location.z + offsetZ
                ))
            }
        }
    }
    
    private fun scheduleAutoCancel(player: Player) {
        var countdown = 30
        val task = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
            countdown--
            if (countdown <= 0) {
                if (hasActivePreview(player)) {
                    player.sendMessage(I18n.Message.BuildSite.previewExpired)
                    stopPreview(player)
                }
                previewTasks.remove(player.uniqueId)?.cancel()
            } else if (countdown <= 3 && hasActivePreview(player)) {
                player.sendActionBar(I18n.Message.BuildSite.previewCountdown(countdown))
            }
        }, 20L, 20L)
        
        previewTasks[player.uniqueId] = task
    }
    
    private fun resetAutoCancel(player: Player) {
        val existingTask = previewTasks.remove(player.uniqueId)
        existingTask?.cancel()
        
        if (hasActivePreview(player)) {
            scheduleAutoCancel(player)
        }
    }
    
    fun stopPreview(player: Player) {
        val task = previewTasks.remove(player.uniqueId)
        task?.cancel()
        renderTasks.remove(player.uniqueId)?.cancel()
        
        val previewId = playerPreviews.remove(player.uniqueId) ?: return
        val preview = activePreviews.remove(previewId) ?: return
        preview.hide()
        preview.clearBlockDisplays()
    }
    
    fun stopAllPreviews() {
        previewTasks.values.forEach { it.cancel() }
        previewTasks.clear()
        renderTasks.values.forEach { it.cancel() }
        renderTasks.clear()
        
        activePreviews.values.forEach { it.hide() }
        activePreviews.values.forEach { it.clearBlockDisplays() }
        activePreviews.clear()
        playerPreviews.clear()
    }
    
    fun getPreview(player: Player): BuildSitePreview? {
        val previewId = playerPreviews[player.uniqueId] ?: return null
        return activePreviews[previewId]
    }
    
    fun hasActivePreview(player: Player): Boolean {
        return playerPreviews.containsKey(player.uniqueId)
    }
    
    fun confirmPreview(player: Player): BuildSitePreview? {
        val preview = getPreview(player) ?: return null
        return preview
    }
    
    fun cancelPreview(player: Player) {
        stopPreview(player)
    }

    // ---------- 预览"实体方块"渲染（玩家周边局部，真实材质无颜色） ----------

    /** 每 5 tick：数据脏则触发异步重建；数据就绪则增量刷新玩家周边实体。 */
    private fun startRenderTask(player: Player, preview: BuildSitePreview) {
        val task = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
            if (!preview.isActive) {
                renderTasks.remove(player.uniqueId)?.cancel()
                return@Runnable
            }
            val p = Bukkit.getPlayer(player.uniqueId) ?: return@Runnable

            if (preview.ghostDataDirty && !preview.ghostDataBuilding) {
                preview.ghostDataDirty = false
                preview.ghostDataBuilding = true
                kickAsyncBuild(preview)
            }
            updateBlockPreview(p, preview)
        }, 5L, 5L)
        renderTasks[player.uniqueId] = task
    }

    /** 异步构建预览数据（纯计算：1M 级也不阻塞主线程）。 */
    private fun kickAsyncBuild(preview: BuildSitePreview) {
        val gen = preview.ghostDataGen
        val blueprint = top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.get(preview.blueprintId)
        if (blueprint == null) {
            // 蓝图缺失：放弃本轮构建，避免每 tick 空转
            preview.ghostDataBuilding = false
            preview.ghostDataDirty = false
            return
        }
        val anchorLocation = preview.anchorLocation.clone()
        val facing = preview.facing
        val key = "sitepreview:${preview.id}:$gen"

        BuildSiteAsync.enqueue(key, {
            buildPreviewGhostData(blueprint, anchorLocation, facing)
        }, { data ->
            val p = Bukkit.getPlayer(preview.playerId)
            if (p == null || !preview.isActive) return@enqueue
            if (preview.ghostDataGen != gen) return@enqueue // 过期构建：新构建已接管
            preview.ghostData = data
            preview.ghostDataBuilding = false
            updateBlockPreview(p, preview)
        })
    }

    /** 纯计算：scaffold 全部方块按 facing 变换到世界坐标，按区块列分片。控制器位置跳过（那是展位）。 */
    private fun buildPreviewGhostData(blueprint: Blueprint, anchorLocation: Location, facing: Facing): PreviewGhostData {
        val transform = CoordinateTransform(facing)
        val centerOffset = blueprint.meta.controllerOffset
        val controllerPos = Vector3i(
            anchorLocation.blockX,
            anchorLocation.blockY,
            anchorLocation.blockZ
        )
        val cells = HashMap<Long, MutableList<PreviewBlockData>>()
        var total = 0
        for (entry in blueprint.scaffoldShape.blocks) {
            if (entry.position == centerOffset) continue // 控制器位置不渲染
            val worldPos = transform.toWorldPosition(controllerPos, entry.position, centerOffset)
            val key = ((worldPos.x shr 4).toLong() shl 32) or ((worldPos.z shr 4).toLong() and 0xFFFFFFFFL)
            cells.getOrPut(key) { ArrayList() }.add(PreviewBlockData(worldPos, entry.blockData.clone()))
            total++
        }
        return PreviewGhostData(cells, total)
    }

    /** 增量刷新：只生成玩家周边 [PREVIEW_RENDER_RADIUS] 内的实体，超出范围的移除。 */
    private fun updateBlockPreview(player: Player, preview: BuildSitePreview) {
        val data = preview.ghostData ?: return
        val px = player.location.blockX
        val py = player.location.blockY
        val pz = player.location.blockZ
        val radius = PREVIEW_RENDER_RADIUS
        val radiusSq = radius * radius

        val minCX = (px - radius) shr 4
        val maxCX = (px + radius) shr 4
        val minCZ = (pz - radius) shr 4
        val maxCZ = (pz + radius) shr 4

        val keep = HashSet<Vector3i>()
        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                val cellKey = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
                val cell = data.cells[cellKey] ?: continue
                for (b in cell) {
                    val dx = b.pos.x - px
                    val dy = b.pos.y - py
                    val dz = b.pos.z - pz
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue
                    keep.add(b.pos)
                    ensureDisplay(preview, b.pos, b.blockData)
                }
            }
        }

        // 移除离开范围或已失效的实体
        val iter = preview.blockDisplays.entries.iterator()
        while (iter.hasNext()) {
            val (pos, disp) = iter.next()
            if (pos !in keep || !disp.isValid) {
                iter.remove()
                disp.remove()
            }
        }
    }

    private fun ensureDisplay(preview: BuildSitePreview, pos: Vector3i, data: BlockData): Boolean {
        val existing = preview.blockDisplays[pos]
        if (existing != null && existing.isValid && existing.block == data) return true
        if (existing == null && preview.blockDisplays.size >= MAX_PREVIEW_DISPLAYS) return false

        existing?.remove()
        preview.blockDisplays.remove(pos)

        val display = try {
            // BlockDisplay 实体位置 = 方块渲染的最小角（与 PreviewSession/锚点幽灵一致），
            // 若用中心 +0.5 会整体偏移半个方块
            preview.world.spawn(Location(preview.world, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), BlockDisplay::class.java) { d ->
                d.block = data
                d.isPersistent = false
                d.transformation = Transformation(
                    Vector3f(),
                    AxisAngle4f(),
                    Vector3f(1.001f, 1.001f, 1.001f),
                    AxisAngle4f()
                )
            }
        } catch (_: Exception) {
            null
        } ?: return false

        preview.blockDisplays[pos] = display
        return true
    }
}

class BuildSitePreview(
    val id: UUID,
    val playerId: UUID,
    val blueprintId: String,
    val world: World,
    var boundingBox: BoundingBox,
    var validationResult: ValidationResult,
    var facing: Facing,
    var anchorLocation: Location
) {
    
    var boxRenderer: SmoothBoundingBoxRenderer? = null
    internal var errorMarkerDisplays = mutableListOf<BlockDisplay>()
    var isActive: Boolean = false
        private set

    // ---- "实体方块"预览数据与实体池（真实材质、全尺寸、无颜色） ----
    internal val blockDisplays = ConcurrentHashMap<Vector3i, BlockDisplay>()
    var ghostData: PreviewGhostData? = null
        internal set
    var ghostDataGen: Int = 0
        private set
    var ghostDataDirty: Boolean = true
        internal set
    var ghostDataBuilding: Boolean = false
        internal set

    /** 数据失效（位置/朝向变化）：丢弃旧数据、自增代数、清空已生成实体，等待异步重建。 */
    fun invalidateGhostData() {
        ghostData = null
        ghostDataGen++
        ghostDataDirty = true
        clearBlockDisplays()
    }

    fun clearBlockDisplays() {
        blockDisplays.values.forEach {
            if (it.isValid) it.remove()
        }
        blockDisplays.clear()
    }

    fun show() {
        if (isActive) return
        isActive = true
        
        val color = if (validationResult.isValid) Color.RED else Color.fromRGB(255, 85, 85)
        
        boxRenderer = SmoothBoundingBoxRenderer(
            plugin = MonolithLib.instance,
            world = world,
            initialColor = color,
            thickness = 0.08f,
            maxMoveRadius = 10.0,
            interpolationTicks = 8,
            cornerSize = 0.25f
        )
        
        val boxData = SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
            boundingBox.minX, boundingBox.minY, boundingBox.minZ,
            boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ
        )
        boxRenderer!!.show(boxData)

        createErrorMarkers()
    }
    
    fun hide() {
        if (!isActive) return
        isActive = false
        
        boxRenderer?.hide()
        boxRenderer = null
        
        errorMarkerDisplays.forEach { 
            if (it.isValid) it.remove() 
        }
        errorMarkerDisplays.clear()
    }
    
    /** 清除并重建错误/警告标记（朝向变化后旧标记位置失效时调用）。 */
    fun rebuildErrorMarkers() {
        errorMarkerDisplays.forEach {
            if (it.isValid) it.remove()
        }
        errorMarkerDisplays.clear()
        createErrorMarkers()
    }
    
    private fun createErrorMarkers() {
        val errorColor = Color.fromRGB(255, 0, 0)
        
        for (error in validationResult.errors) {
            for (pos in error.positions) {
                createErrorMarker(pos, errorColor)
            }
        }
        
        val warningColor = Color.fromRGB(255, 255, 0)
        for (warning in validationResult.warnings) {
            for (pos in warning.positions) {
                createErrorMarker(pos, warningColor)
            }
        }
    }
    
    private fun createErrorMarker(pos: Vector3i, color: Color) {
        val location = Location(world, pos.x.toDouble() + 0.5, pos.y.toDouble() + 0.5, pos.z.toDouble() + 0.5)
        
        try {
            val display = world.spawn(location, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(if (color == Color.fromRGB(255, 0, 0)) Material.REDSTONE_BLOCK else Material.GOLD_BLOCK)
                d.glowColorOverride = color
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                
                d.transformation = Transformation(
                    Vector3f(-0.3f, -0.3f, -0.3f),
                    AxisAngle4f(),
                    Vector3f(0.6f, 0.6f, 0.6f),
                    AxisAngle4f()
                )
            }
            
            errorMarkerDisplays.add(display)
        } catch (_: Exception) {}
    }
    
    fun getSummaryMessage(): List<Component> {
        val messages = mutableListOf<Component>()
        
        messages.add(I18n.Message.BuildSite.previewHeader)
        messages.add(I18n.Message.BuildSite.previewBlueprint(blueprintId))
        messages.add(I18n.Message.BuildSite.previewFacing(facing.name))
        messages.add(I18n.Message.BuildSite.previewSize(boundingBox.width, boundingBox.height, boundingBox.depth))
        messages.add(I18n.Message.BuildSite.previewPosition(
            boundingBox.minX, boundingBox.minY, boundingBox.minZ,
            boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ
        ))
        
        if (validationResult.isValid) {
            messages.add(I18n.Message.BuildSite.previewValid)
        } else {
            messages.add(I18n.Message.BuildSite.previewErrors(validationResult.errors.size))
            for (error in validationResult.errors) {
                messages.add(LegacyComponentSerializer.legacySection().deserialize("  §c- ${error.message}"))
            }
        }
        
        if (validationResult.warnings.isNotEmpty()) {
            messages.add(I18n.Message.BuildSite.previewWarnings)
            for (warning in validationResult.warnings) {
                messages.add(LegacyComponentSerializer.legacySection().deserialize("  §e- ${warning.message}"))
            }
        }
        
        messages.add(I18n.Message.BuildSite.previewInstructions)
        messages.add(I18n.Message.BuildSite.previewAutoCancel(30))
        messages.add(I18n.Message.BuildSite.previewFooter)
        
        return messages
    }
}
