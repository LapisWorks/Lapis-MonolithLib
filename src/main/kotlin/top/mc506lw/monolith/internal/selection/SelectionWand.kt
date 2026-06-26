package top.mc506lw.monolith.internal.selection

import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.interfaces.BlockInteractRebarItemHandler
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.MonolithLib

class SelectionWand(stack: org.bukkit.inventory.ItemStack) : RebarItem(stack), BlockInteractRebarItemHandler {

    override fun onInteractWithBlock(event: PlayerInteractEvent, priority: EventPriority) {
        if (event.action == Action.PHYSICAL) return
        
        val player = event.player
        val block = event.clickedBlock ?: return
        
        event.isCancelled = true
        
        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> {
                SelectionManager.setPos1(player, block)
            }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                SelectionManager.setPos2(player, block)
            }
            else -> {}
        }
    }
    
    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "selection_wand")
        
        val STACK: org.bukkit.inventory.ItemStack by lazy {
            ItemStackBuilder.rebar(Material.BLAZE_ROD, KEY)
                .name(I18n.translatable("item.selection_wand.name"))
                .lore(listOf(
                    I18n.translatable("item.selection_wand.lore.0"),
                    I18n.translatable("item.selection_wand.lore.1"),
                    I18n.translatable("item.selection_wand.lore.2")
                ))
                .build()
        }
    }
}
