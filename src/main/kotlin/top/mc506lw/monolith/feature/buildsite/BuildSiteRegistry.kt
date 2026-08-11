package top.mc506lw.monolith.feature.buildsite

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/** Runtime index of Rebar-backed build-site anchors. Rebar owns persistence. */
object BuildSiteRegistry {
    private val anchors = ConcurrentHashMap<String, BuildSiteAnchorBlock>()

    fun register(anchor: BuildSiteAnchorBlock) {
        anchors[key(anchor)] = anchor
    }

    fun unregister(anchor: BuildSiteAnchorBlock) {
        anchors.remove(key(anchor))
    }

    fun all(): List<BuildSiteAnchorBlock> = anchors.values.filter { it.blueprint != null }

    fun nearest(player: Player, range: Double = 16.0): BuildSiteAnchorBlock? {
        val rangeSq = range * range
        return all().asSequence()
            .filter { it.block.world == player.world }
            .map { it to it.block.location.distanceSquared(player.location) }
            .filter { it.second <= rangeSq }
            .minByOrNull { it.second }
            ?.first
    }

    fun overlaps(world: String, box: BoundingBox, ignored: BuildSiteAnchorBlock? = null): BuildSiteAnchorBlock? =
        all().firstOrNull { anchor ->
            anchor !== ignored && anchor.block.world.name == world && boxesOverlap(box, anchor.boundingBox())
        }

    fun cleanup() {
        anchors.values.forEach { it.removeAllRenderings() }
        anchors.clear()
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

    private fun boxesOverlap(a: BoundingBox, b: BoundingBox): Boolean =
        a.minX <= b.maxX && a.maxX >= b.minX &&
            a.minY <= b.maxY && a.maxY >= b.minY &&
            a.minZ <= b.maxZ && a.maxZ >= b.minZ

    private fun key(anchor: BuildSiteAnchorBlock): String =
        "${anchor.block.world.name}:${anchor.block.x}:${anchor.block.y}:${anchor.block.z}"
}
