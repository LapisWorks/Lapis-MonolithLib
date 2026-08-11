package top.mc506lw.monolith.feature.buildsite

import org.bukkit.entity.Player
import top.mc506lw.monolith.feature.buildmode.PrinterTask
import java.util.UUID

object PrinterManager {
    fun isEnabled(player: Player): Boolean = LitematicaModeManager.isPrinterEnabled(player)
    fun toggle(player: Player): Boolean? = LitematicaModeManager.togglePrinter(player)
    fun onPlayerQuit(playerId: UUID) = Unit
    fun cleanup() = PrinterTask.stopAll()
}
