package top.mc506lw.monolith.feature.buildsite

import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime index of Rebar-backed build-site anchors. Rebar owns persistence.
 *
 * 提供区块索引（chunkIndex）用于 O(1) 附近定位工地，避免事件路径 15³ 暴力扫描。
 */
object BuildSiteRegistry {
    private val anchors = ConcurrentHashMap<String, BuildSiteAnchorBlock>()

    /** world:chunkX:chunkZ → 覆盖该区块的工地 */
    private val chunkIndex = ConcurrentHashMap<String, MutableSet<BuildSiteAnchorBlock>>()

    fun register(anchor: BuildSiteAnchorBlock) {
        anchors[key(anchor)] = anchor
        indexChunks(anchor)
    }

    fun unregister(anchor: BuildSiteAnchorBlock) {
        anchors.remove(key(anchor))
        chunkIndex.values.forEach { it.remove(anchor) }
    }

    fun all(): List<BuildSiteAnchorBlock> = anchors.values.filter { it.blueprint != null }

    /** 精确位置查找（供解体转换完成等一次性事件使用）。 */
    fun findAt(world: String, x: Int, y: Int, z: Int): BuildSiteAnchorBlock? =
        anchors["$world:$x:$y:$z"]

    fun nearest(player: Player, range: Double = 16.0): BuildSiteAnchorBlock? {
        val rangeSq = range * range
        return all().asSequence()
            .filter { it.block.world == player.world }
            .map { it to it.block.location.distanceSquared(player.location) }
            .filter { it.second <= rangeSq }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * 找到覆盖指定方块的工地（O(1) 附近）：先查该方块所在区块索引，再查相邻 8 区块，
     * 最后用轻量 covers() 精确判断。彻底替代 15³ 暴力扫描。
     */
    fun findCovering(block: Block): BuildSiteAnchorBlock? {
        val worldName = block.world.name
        val cx = block.x shr 4
        val cz = block.z shr 4
        for (dx in -1..1) for (dz in -1..1) {
            val set = chunkIndex[chunkKey(worldName, cx + dx, cz + dz)] ?: continue
            for (anchor in set) {
                if (anchor.blueprint != null && anchor.covers(worldName, block.x, block.y, block.z)) {
                    return anchor
                }
            }
        }
        return null
    }

    fun overlaps(world: String, box: BoundingBox, ignored: BuildSiteAnchorBlock? = null): BuildSiteAnchorBlock? =
        all().firstOrNull { anchor ->
            anchor !== ignored && anchor.block.world.name == world && boxesOverlap(box, anchor.boundingBox())
        }

    fun cleanup() {
        anchors.values.forEach { it.removeAllRenderings() }
        anchors.clear()
        chunkIndex.clear()
    }

    fun refreshBlueprint(oldId: String, newId: String) {
        all().filter { it.blueprintId == oldId }.forEach { anchor ->
            if (oldId != newId) {
                anchor.initialize(newId, anchor.facing)
            } else {
                anchor.refreshBlueprint()
            }
        }
    }

    /** 把工地的世界包围盒覆盖的所有区块加入索引（基于缓存的 AABB，O(1)）。 */
    private fun indexChunks(anchor: BuildSiteAnchorBlock) {
        val box = anchor.worldBox() ?: return
        val worldName = anchor.block.world.name
        val minCX = box.minX shr 4
        val maxCX = box.maxX shr 4
        val minCZ = box.minZ shr 4
        val maxCZ = box.maxZ shr 4
        for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) {
            chunkIndex.computeIfAbsent(chunkKey(worldName, cx, cz)) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(anchor)
        }
    }

    private fun chunkKey(world: String, cx: Int, cz: Int): String = "$world:$cx:$cz"

    private fun boxesOverlap(a: BoundingBox, b: BoundingBox): Boolean =
        a.minX <= b.maxX && a.maxX >= b.minX &&
            a.minY <= b.maxY && a.maxY >= b.minY &&
            a.minZ <= b.maxZ && a.maxZ >= b.minZ

    private fun key(anchor: BuildSiteAnchorBlock): String =
        "${anchor.block.world.name}:${anchor.block.x}:${anchor.block.y}:${anchor.block.z}"
}
