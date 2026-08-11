package top.mc506lw.monolith.internal.listener

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import top.mc506lw.monolith.validation.EventValidator

class MonolithBlockListener : Listener {
    private val eventValidators = mutableListOf<EventValidator>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        handleBlockChange(event.block.location)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        handleBlockChange(event.block.location)
    }

    fun onBlockChangeNative(x: Int, y: Int, z: Int, worldName: String) {
        val world = Bukkit.getWorld(worldName) ?: return

        handleBlockChange(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))
    }

    private fun handleBlockChange(location: Location) {
        for (validator in eventValidators) {
            validator.validateBlockChange(location)
        }
    }

    fun registerEventValidator(validator: EventValidator) {
        eventValidators.add(validator)
    }

    fun unregisterEventValidator(validator: EventValidator) {
        eventValidators.remove(validator)
    }

    companion object {
        @Volatile
        private var instance: MonolithBlockListener? = null

        fun getInstance(): MonolithBlockListener {
            return instance ?: synchronized(this) {
                instance ?: MonolithBlockListener().also { instance = it }
            }
        }
    }
}
