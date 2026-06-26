package top.mc506lw.monolith.feature.virtual

import org.bukkit.Material
import org.bukkit.NamespacedKey
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.MonolithLib
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder

object VirtualDisplayAnchorRegistry {

    private val logger = MonolithLogger.getLogger("Virtual")

    fun register() {
        RebarBlock.register(VirtualDisplayAnchor.KEY, VirtualDisplayAnchor.MATERIAL, VirtualDisplayAnchor::class.java)

        val itemStack = ItemStackBuilder.rebar(VirtualDisplayAnchor.MATERIAL, VirtualDisplayAnchor.KEY).build()
        RebarItem.register(RebarItem::class.java, itemStack, VirtualDisplayAnchor.KEY)

        logger.debug { "Registered VirtualDisplayAnchor (key=${VirtualDisplayAnchor.KEY}, material=${VirtualDisplayAnchor.MATERIAL})" }
    }
}
