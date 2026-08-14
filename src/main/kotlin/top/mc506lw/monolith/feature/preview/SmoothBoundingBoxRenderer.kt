package top.mc506lw.monolith.feature.preview

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SmoothBoundingBoxRenderer(
    private val plugin: MonolithLib,
    val world: World,
    initialColor: Color = Color.RED,
    var thickness: Float = 0.08f,
    val maxMoveRadius: Double = 10.0,
    val interpolationTicks: Int = 12,
    private val cornerSize: Float = 0.25f
) {
    var color: Color = initialColor
        set(newColor) {
            field = newColor
            edges.forEach { it.displayEntity?.glowColorOverride = newColor }
            corners.forEach { it.displayEntity?.glowColorOverride = newColor }
        }
    
    enum class AnimationState {
        IDLE,
        ANIMATING
    }
    data class EdgeSegment(
        val id: UUID = UUID.randomUUID(),
        var displayEntity: BlockDisplay? = null,
        var fromStart: Vector3f,
        var fromEnd: Vector3f,
        var toStart: Vector3f = fromStart,
        var toEnd: Vector3f = fromEnd,
        var progress: Float = 1.0f
    )

    data class CornerMarker(
        val id: UUID = UUID.randomUUID(),
        var displayEntity: BlockDisplay? = null,
        var fromPos: Vector3f,
        var toPos: Vector3f = fromPos,
        var progress: Float = 1.0f
    )

    private val edges = mutableListOf<EdgeSegment>()
    private val corners = mutableListOf<CornerMarker>()
    private var currentBox: BoundingBoxData? = null
    private var targetBox: BoundingBoxData? = null

    /** 旋转插值参数：非 0 时角点沿绕 [rotatePivot] 垂直轴的圆弧运动，而不是直线。 */
    private var rotateDegrees: Int = 0
    private var rotatePivot = Vector3f()

    private var updateTask: BukkitTask? = null
    var isActive: Boolean = false
        private set
    var animationState: AnimationState = AnimationState.IDLE
        private set

    data class BoundingBoxData(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int
    ) {
        val width get() = maxX - minX + 1
        val height get() = maxY - minY + 1
        val depth get() = maxZ - minZ + 1

        fun anchorDistance(other: BoundingBoxData): Double {
            val dx = (minX + maxX) / 2.0 - (other.minX + other.maxX) / 2.0
            val dy = (minY + maxY) / 2.0 - (other.minY + other.maxY) / 2.0
            val dz = (minZ + maxZ) / 2.0 - (other.minZ + other.maxZ) / 2.0
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }

        companion object {
            fun fromMinMax(minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) =
                BoundingBoxData(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }

    fun show(box: BoundingBoxData) {
        hide()
        currentBox = box
        targetBox = box
        buildEntities(box, box)
        isActive = true
    }

    fun moveTo(newBox: BoundingBoxData) {
        if (!isActive) {
            show(newBox)
            return
        }

        val dist = currentBox?.anchorDistance(newBox) ?: Double.MAX_VALUE

        targetBox = newBox

        if (dist > maxMoveRadius || currentBox == null) {
            rebuildForBox(newBox)
            rotateDegrees = 0
        } else {
            // 先按旧模式（可能是旋转）快照当前位置，再切回直线插值
            val currentEdgePositions = snapshotCurrentEdgePositions()
            val currentCornerPositions = snapshotCurrentCornerPositions()
            rotateDegrees = 0
            beginInterpolationFrom(newBox, currentEdgePositions, currentCornerPositions)
            animationState = AnimationState.ANIMATING
        }
    }

    fun updatePosition(offsetX: Int, offsetY: Int, offsetZ: Int) {
        val current = currentBox ?: return
        val newBox = BoundingBoxData(
            current.minX + offsetX, current.minY + offsetY, current.minZ + offsetZ,
            current.maxX + offsetX, current.maxY + offsetY, current.maxZ + offsetZ
        )
        moveTo(newBox)
    }

    /**
     * 围绕 [pivotX]/[pivotZ]（世界坐标，垂直轴）旋转 [degrees] 度到 [newBox]。
     * 角点/端点沿**圆弧**运动（物理旋转，绕轴摆动），而非直线插值。
     * 无论盒子多大都动画（旋转总是物理合理的，不受 maxMoveRadius 限制）。
     */
    fun rotateTo(newBox: BoundingBoxData, pivotX: Double, pivotZ: Double, degrees: Int) {
        if (!isActive) {
            show(newBox)
            return
        }

        targetBox = newBox

        if (currentBox == null) {
            rebuildForBox(newBox)
            rotateDegrees = 0
            return
        }

        // 关键：先按**旧模式**快照当前位置，再切换旋转参数。
        // 若先设置 rotateDegrees，静止的角点会被新角度算一遍（瞬移），
        // 动画中又用同角度再转一次（双重旋转）→ 框架散架。
        val currentEdgePositions = snapshotCurrentEdgePositions()
        val currentCornerPositions = snapshotCurrentCornerPositions()
        rotateDegrees = degrees
        rotatePivot = Vector3f(pivotX.toFloat(), 0f, pivotZ.toFloat())
        beginInterpolationFrom(newBox, currentEdgePositions, currentCornerPositions)
        animationState = AnimationState.ANIMATING
    }


    fun hide() {
        stopUpdateTask()
        edges.forEach { it.displayEntity?.remove() }
        corners.forEach { it.displayEntity?.remove() }
        edges.clear()
        corners.clear()
        currentBox = null
        targetBox = null
        rotateDegrees = 0
        rotatePivot = Vector3f()
        isActive = false
        animationState = AnimationState.IDLE
    }

    private fun buildEntities(fromBox: BoundingBoxData, toBox: BoundingBoxData) {
        edges.clear()
        corners.clear()

        val toEdgeDefs = computeEdgePositions(toBox)
        val fromEdgeDefs = computeEdgePositions(fromBox)

        for (i in toEdgeDefs.indices) {
            val (toStart, toEnd) = toEdgeDefs[i]
            val (fromStart, fromEnd) = fromEdgeDefs[i]

            createEdge(EdgeSegment(
                fromStart = fromStart,
                fromEnd = fromEnd,
                toStart = toStart,
                toEnd = toEnd,
                progress = 1.0f
            ))
        }

        val toCornerDefs = computeCornerPositions(toBox)
        val fromCornerDefs = computeCornerPositions(fromBox)

        for (i in toCornerDefs.indices) {
            createCorner(CornerMarker(
                fromPos = fromCornerDefs[i],
                toPos = toCornerDefs[i],
                progress = 1.0f
            ))
        }
    }

    /** 快照当前显示位置（按当前动画模式/进度计算，模式切换前调用）。 */
    private fun snapshotCurrentEdgePositions(): List<Pair<Vector3f, Vector3f>> =
        edges.map { edge ->
            val easedP = easeOutExpo(edge.progress)
            Pair(
                pathPos(edge.fromStart, edge.toStart, easedP),
                pathPos(edge.fromEnd, edge.toEnd, easedP)
            )
        }

    private fun snapshotCurrentCornerPositions(): List<Vector3f> =
        corners.map { corner ->
            pathPos(corner.fromPos, corner.toPos, easeOutExpo(corner.progress))
        }

    private fun beginInterpolationFrom(
        newBox: BoundingBoxData,
        currentEdgePositions: List<Pair<Vector3f, Vector3f>>,
        currentCornerPositions: List<Vector3f>
    ) {
        currentBox = newBox

        val newEdgeDefs = computeEdgePositions(newBox)
        val newCornerDefs = computeCornerPositions(newBox)

        if (edges.size == newEdgeDefs.size) {
            for (i in edges.indices) {
                val edge = edges[i]
                val (toStart, toEnd) = newEdgeDefs[i]
                edge.fromStart = currentEdgePositions[i].first
                edge.fromEnd = currentEdgePositions[i].second
                edge.toStart = toStart
                edge.toEnd = toEnd
                edge.progress = 0.0f
            }
        } else {
            edges.forEach { it.displayEntity?.remove() }
            edges.clear()
            for (i in newEdgeDefs.indices) {
                val (toStart, toEnd) = newEdgeDefs[i]
                val (fromStart, fromEnd) = currentEdgePositions.getOrNull(i)
                    ?: Pair(toStart, toEnd)
                createEdge(EdgeSegment(
                    fromStart = fromStart,
                    fromEnd = fromEnd,
                    toStart = toStart,
                    toEnd = toEnd,
                    progress = 0.0f
                ))
            }
        }

        if (corners.size == newCornerDefs.size) {
            for (i in corners.indices) {
                val corner = corners[i]
                corner.fromPos = currentCornerPositions[i]
                corner.toPos = newCornerDefs[i]
                corner.progress = 0.0f
            }
        } else {
            corners.forEach { it.displayEntity?.remove() }
            corners.clear()
            for (i in newCornerDefs.indices) {
                val fromPos = currentCornerPositions.getOrNull(i) ?: newCornerDefs[i]
                createCorner(CornerMarker(
                    fromPos = fromPos,
                    toPos = newCornerDefs[i],
                    progress = 0.0f
                ))
            }
        }

        ensureUpdateTask()
    }

    private fun computeEdgePositions(box: BoundingBoxData): List<Pair<Vector3f, Vector3f>> {
        return listOf(
            Pair(vec3iToVec3f(Vector3i(box.minX, box.minY, box.minZ)), vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.minZ))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.minZ)), vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.minZ))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.minY, box.maxZ + 1)), vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.maxZ + 1)), vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.minY, box.minZ)), vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.minZ))),
            Pair(vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.minZ)), vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.minZ))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.minY, box.maxZ + 1)), vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.maxZ + 1)), vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.minY, box.minZ)), vec3iToVec3f(Vector3i(box.minX, box.minY, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.minZ)), vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.minZ)), vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.maxZ + 1))),
            Pair(vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.minZ)), vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.maxZ + 1)))
        )
    }

    private fun computeCornerPositions(box: BoundingBoxData): List<Vector3f> {
        return listOf(
            vec3iToVec3f(Vector3i(box.minX, box.minY, box.minZ)),
            vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.minZ)),
            vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.minZ)),
            vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.minZ)),
            vec3iToVec3f(Vector3i(box.minX, box.minY, box.maxZ + 1)),
            vec3iToVec3f(Vector3i(box.maxX + 1, box.minY, box.maxZ + 1)),
            vec3iToVec3f(Vector3i(box.minX, box.maxY + 1, box.maxZ + 1)),
            vec3iToVec3f(Vector3i(box.maxX + 1, box.maxY + 1, box.maxZ + 1))
        )
    }

    private fun rebuildForBox(newBox: BoundingBoxData) {
        edges.forEach { it.displayEntity?.remove() }
        corners.forEach { it.displayEntity?.remove() }
        edges.clear()
        corners.clear()
        currentBox = newBox
        buildEntities(newBox, newBox)
    }

    private fun createEdge(edge: EdgeSegment) {
        val startX = edge.fromStart.x
        val startY = edge.fromStart.y
        val startZ = edge.fromStart.z
        val loc = Location(world, startX.toDouble(), startY.toDouble(), startZ.toDouble())
        
        val dirX = edge.fromEnd.x - startX
        val dirY = edge.fromEnd.y - startY
        val dirZ = edge.fromEnd.z - startZ
        val length = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)

        if (length < 0.01f) return

        try {
            val display = world.spawn(loc, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(Material.RED_CONCRETE)
                d.glowColorOverride = color
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                d.viewRange = 500f
                applyEdgeTransform(d, Vector3f(dirX, dirY, dirZ), length)
            }
            edge.displayEntity = display
            edges.add(edge)
        } catch (_: Exception) {}
    }

    /** 边块沿 [dir] 方向拉伸：X 轴旋转对齐到 [dir]，长度 = |dir|，粗细 = thickness。 */
    private fun applyEdgeTransform(display: BlockDisplay, dir: Vector3f, length: Float) {
        display.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            rotationAlignXTo(dir),
            Vector3f(length, thickness, thickness),
            AxisAngle4f()
        )
    }

    /** 将单位 X 轴 (1,0,0) 旋转到 [dir] 方向的四元数角轴。 */
    private fun rotationAlignXTo(dir: Vector3f): AxisAngle4f {
        val length = dir.length()
        if (length < 1e-6f) return AxisAngle4f()
        val x = dir.x / length
        val y = dir.y / length
        val z = dir.z / length
        // cross = (1,0,0) × (x,y,z) = (0, -z, y)
        val crossLen = kotlin.math.sqrt(y * y + z * z)
        if (crossLen < 1e-6f) {
            // dir ≈ ±X：不转或翻转 180°
            return if (x >= 0) AxisAngle4f() else AxisAngle4f(kotlin.math.PI.toFloat(), 0f, 1f, 0f)
        }
        val angle = kotlin.math.acos(x.coerceIn(-1f, 1f))
        return AxisAngle4f(angle, 0f, -z / crossLen, y / crossLen)
    }

    private fun createCorner(corner: CornerMarker) {
        val loc = Location(world, corner.fromPos.x.toDouble(), corner.fromPos.y.toDouble(), corner.fromPos.z.toDouble())

        try {
            val display = world.spawn(loc, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(Material.RED_CONCRETE)
                d.glowColorOverride = color
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                d.viewRange = 500f

                d.transformation = Transformation(
                    Vector3f(-cornerSize / 2f, -cornerSize / 2f, -cornerSize / 2f),
                    AxisAngle4f(),
                    Vector3f(cornerSize, cornerSize, cornerSize),
                    AxisAngle4f()
                )
            }
            corner.displayEntity = display
            corners.add(corner)
        } catch (_: Exception) {}
    }

    private fun ensureUpdateTask() {
        if (updateTask != null && !updateTask!!.isCancelled) return

        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick()
        }, 1L, 1L)
    }

    private fun stopUpdateTask() {
        updateTask?.cancel()
        updateTask = null
    }

    private fun tick() {
        val allComplete = edges.all { it.progress >= 1.0f } && corners.all { it.progress >= 1.0f }
        if (allComplete) {
            animationState = AnimationState.IDLE
            // 注意：不清零 rotateDegrees —— 后续快照（下一次 moveTo/rotateTo）需要按
            // 旧模式算出弧线终点作为起点；moveTo/rotateTo 各自在快照后重置/覆盖。
            stopUpdateTask()
            return
        }

        val step = 1.0f / interpolationTicks.toFloat()

        for (edge in edges) {
            if (edge.progress >= 1.0f) continue
            edge.progress = (edge.progress + step).coerceAtMost(1.0f)

            val easedProgress = easeOutExpo(edge.progress)
            val currentStart = pathPos(edge.fromStart, edge.toStart, easedProgress)
            val currentEnd = pathPos(edge.fromEnd, edge.toEnd, easedProgress)

            val display = edge.displayEntity ?: continue
            if (!display.isValid) continue

            display.teleport(Location(world, currentStart.x.toDouble(), currentStart.y.toDouble(), currentStart.z.toDouble()))

            val dirX = currentEnd.x - currentStart.x
            val dirY = currentEnd.y - currentStart.y
            val dirZ = currentEnd.z - currentStart.z
            val length = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
            if (length < 0.01f) continue
            // 旋转中用方向+旋转矩阵渲染：横边在摆动过程中是斜段，轴对齐拉伸无法表达
            applyEdgeTransform(display, Vector3f(dirX, dirY, dirZ), length)
        }

        for (corner in corners) {
            if (corner.progress >= 1.0f) continue
            corner.progress = (corner.progress + step).coerceAtMost(1.0f)

            val easedProgress = easeOutExpo(corner.progress)
            val currentPos = pathPos(corner.fromPos, corner.toPos, easedProgress)

            val display = corner.displayEntity ?: continue
            if (!display.isValid) continue

            display.teleport(Location(world, currentPos.x.toDouble(), currentPos.y.toDouble(), currentPos.z.toDouble()))
        }
    }

    private fun lerp(a: Vector3f, b: Vector3f, t: Float): Vector3f {
        return Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
    }

    /**
     * 插值路径：旋转模式（[rotateDegrees] != 0）下沿绕 [rotatePivot] 的圆弧运动，
     * 否则直线 [lerp]。圆弧半径 = from 到 pivot 的距离，保证"绕轴摆动"的物理感。
     */
    private fun pathPos(from: Vector3f, to: Vector3f, t: Float): Vector3f {
        if (rotateDegrees == 0) return lerp(from, to, t)
        return rotateAroundPivot(from, rotatePivot, rotateDegrees * t)
    }

    /** 绕 [pivot] 垂直轴（世界 XZ 平面）旋转 [angleDeg] 度。Y 不变。 */
    private fun rotateAroundPivot(p: Vector3f, pivot: Vector3f, angleDeg: Float): Vector3f {
        val rad = java.lang.Math.toRadians(angleDeg.toDouble())
        val cos = java.lang.Math.cos(rad).toFloat()
        val sin = java.lang.Math.sin(rad).toFloat()
        val dx = p.x - pivot.x
        val dz = p.z - pivot.z
        return Vector3f(
            pivot.x + (dx * cos - dz * sin),
            p.y,
            pivot.z + (dx * sin + dz * cos)
        )
    }

    private fun easeOutCubic(t: Float): Float {
        val t1 = 1f - t
        return 1f - t1 * t1 * t1
    }
    
    private fun easeOutQuart(t: Float): Float {
        val t1 = 1f - t
        return 1f - t1 * t1 * t1 * t1
    }
    
    private fun easeOutExpo(t: Float): Float {
        if (t >= 1.0f) return 1.0f
        return (1.0 - java.lang.Math.pow(2.0, -10.0 * t)).toFloat()
    }
    
    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            val inner = -2f * t + 2f
            1f - inner * inner * inner / 2f
        }
    }

    private fun vec3iToVec3f(v: Vector3i): Vector3f = Vector3f(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())

    companion object {
        private val concreteData: BlockData by lazy { Bukkit.createBlockData(Material.RED_CONCRETE) }
    }
}