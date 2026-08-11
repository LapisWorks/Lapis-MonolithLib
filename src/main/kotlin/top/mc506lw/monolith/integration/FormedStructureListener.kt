package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.BlockStorage
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import top.mc506lw.monolith.common.I18n

/** Any broken assembled component returns its controller to scaffold, without item drops. */
object FormedStructureListener : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val controller = (BlockStorage.get(event.block) as? MNBController)
            ?: MNBController.findControllerForComponent(event.block)
            ?: return

        event.isCancelled = true
        if (StructureDisassembly.toScaffold(controller, event.player)) {
            event.player.sendMessage(I18n.Message.Wrench.disassembleSuccess)
        }
    }
}
