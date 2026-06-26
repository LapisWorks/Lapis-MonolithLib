package top.mc506lw.monolith.internal.selection

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer
import top.mc506lw.monolith.MonolithLib
import java.util.UUID

data class PlayerSelection(
    var pos1: Vector3i? = null,
    var pos2: Vector3i? = null,
    var worldName: String? = null
) {
    val isComplete: Boolean get() = pos1 != null && pos2 != null && worldName != null
    
    fun getMinPos(): Vector3i? {
        if (!isComplete) return null
        return Vector3i(
            minOf(pos1!!.x, pos2!!.x),
            minOf(pos1!!.y, pos2!!.y),
            minOf(pos1!!.z, pos2!!.z)
        )
    }
    
    fun getMaxPos(): Vector3i? {
        if (!isComplete) return null
        return Vector3i(
            maxOf(pos1!!.x, pos2!!.x),
            maxOf(pos1!!.y, pos2!!.y),
            maxOf(pos1!!.z, pos2!!.z)
        )
    }
    
    fun getVolume(): Int {
        val min = getMinPos() ?: return 0
        val max = getMaxPos() ?: return 0
        return (max.x - min.x + 1) * (max.y - min.y + 1) * (max.z - min.z + 1)
    }
}

object SelectionManager {
    
    private const val RENDER_DISTANCE = 100.0
    private const val UPDATE_INTERVAL_TICKS = 10L
    
    private val selections = mutableMapOf<UUID, PlayerSelection>()
    private val selectionRenderers = mutableMapOf<UUID, SmoothBoundingBoxRenderer>()
    private var updateTask: BukkitTask? = null
    
    fun init() {
        if (updateTask == null || updateTask!!.isCancelled) {
            updateTask = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
                tick()
            }, 20L, UPDATE_INTERVAL_TICKS)
        }
    }
    
    fun shutdown() {
        updateTask?.cancel()
        updateTask = null
        selectionRenderers.values.forEach { it.hide() }
        selectionRenderers.clear()
    }
    
    fun getSelection(player: Player): PlayerSelection {
        return selections.getOrPut(player.uniqueId) { PlayerSelection() }
    }
    
    fun setPos1(player: Player, block: Block): Boolean {
        val selection = getSelection(player)
        selection.pos1 = Vector3i(block.x, block.y, block.z)
        selection.worldName = block.world.name
        
        player.sendMessage(I18n.translatable("chat.selection.pos1_set", "x" to block.x, "y" to block.y, "z" to block.z))

        if (selection.isComplete) {
            val vol = selection.getVolume()
            player.sendMessage(I18n.translatable("chat.selection.complete", "count" to vol))
        } else {
            player.sendMessage(I18n.translatable("chat.selection.hint_pos2"))
        }
        
        showSelectionParticles(player)
        updateRendererForPlayer(player)
        return true
    }
    
    fun setPos2(player: Player, block: Block): Boolean {
        val selection = getSelection(player)
        
        if (selection.pos1 == null || selection.worldName == null) {
            player.sendMessage(I18n.translatable("chat.selection.err_no_pos1"))
            return false
        }
        
        if (block.world.name != selection.worldName) {
            player.sendMessage(I18n.translatable("chat.selection.err_different_world"))
            return false
        }
        
        selection.pos2 = Vector3i(block.x, block.y, block.z)
        
        player.sendMessage(I18n.translatable("chat.selection.pos2_set", "x" to block.x, "y" to block.y, "z" to block.z))
        
        if (selection.isComplete) {
            val vol = selection.getVolume()
            player.sendMessage(I18n.translatable("chat.selection.complete", "count" to vol))
            player.sendMessage(I18n.translatable("chat.selection.hint_save"))
        }
        
        showSelectionParticles(player)
        updateRendererForPlayer(player)
        return true
    }
    
    fun clearSelection(player: Player) {
        selections.remove(player.uniqueId)
        selectionRenderers[player.uniqueId]?.hide()
        selectionRenderers.remove(player.uniqueId)
    }
    
    private fun isHoldingWand(player: Player): Boolean {
        val mainHand = player.inventory.itemInMainHand
        if (isWandItem(mainHand)) return true
        val offHand = player.inventory.itemInOffHand
        return isWandItem(offHand)
    }
    
    private fun isWandItem(item: ItemStack): Boolean {
        if (item.type != Material.BLAZE_ROD) return false
        try {
            val rebarItem = io.github.pylonmc.rebar.item.RebarItem.fromStack(item) ?: return false
            return rebarItem.schema.key == SelectionWand.KEY
        } catch (_: Exception) {
            return false
        }
    }
    
    private fun tick() {
        for ((uuid, selection) in selections.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            
            if (!selection.isComplete) {
                hideRenderer(uuid)
                continue
            }
            
            if (!isHoldingWand(player)) {
                hideRenderer(uuid)
                continue
            }
            
            val worldName = selection.worldName ?: continue
            val world = Bukkit.getWorld(worldName) ?: continue
            
            if (player.world != world) {
                hideRenderer(uuid)
                continue
            }
            
            val min = selection.getMinPos() ?: continue
            val max = selection.getMaxPos() ?: continue
            
            val centerX = (min.x + max.x) / 2.0
            val centerY = (min.y + max.y) / 2.0
            val centerZ = (min.z + max.z) / 2.0
            
            val dx = player.location.x - centerX
            val dy = player.location.y - centerY
            val dz = player.location.z - centerZ
            val distSq = dx * dx + dy * dy + dz * dz
            
            if (distSq > RENDER_DISTANCE * RENDER_DISTANCE) {
                hideRenderer(uuid)
                continue
            }
            
            showOrUpdateRenderer(player, world, min, max)
        }
    }
    
    private fun showOrUpdateRenderer(player: Player, world: org.bukkit.World, min: Vector3i, max: Vector3i) {
        val renderer = selectionRenderers.getOrPut(player.uniqueId) {
            SmoothBoundingBoxRenderer(
                plugin = MonolithLib.instance,
                world = world,
                initialColor = org.bukkit.Color.fromRGB(138, 43, 226),
                thickness = 0.06f,
                maxMoveRadius = 200.0,
                interpolationTicks = 15,
                cornerSize = 0.2f
            )
        }
        
        val boxData = SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
            min.x, min.y, min.z,
            max.x, max.y, max.z
        )
        
        if (!renderer.isActive) {
            renderer.show(boxData)
        } else {
            renderer.moveTo(boxData)
        }
    }
    
    private fun hideRenderer(uuid: UUID) {
        selectionRenderers[uuid]?.hide()
        selectionRenderers.remove(uuid)
    }
    
    private fun updateRendererForPlayer(player: Player) {
        val selection = getSelection(player)
        if (!selection.isComplete) {
            hideRenderer(player.uniqueId)
            return
        }
        
        if (!isHoldingWand(player)) return
        
        val worldName = selection.worldName ?: return
        val world = Bukkit.getWorld(worldName) ?: return
        
        val min = selection.getMinPos() ?: return
        val max = selection.getMaxPos() ?: return
        
        showOrUpdateRenderer(player, world, min, max)
    }
    
    private fun showSelectionParticles(player: Player) {
        val selection = getSelection(player)
        if (!selection.isComplete) return
        
        val world = Bukkit.getWorld(selection.worldName!!) ?: return
        val min = selection.getMinPos()!!
        val max = selection.getMaxPos()!!
        
        val particle = Particle.END_ROD
        
        for (x in min.x..max.x) {
            for (z in min.z..max.z) {
                world.spawnParticle(particle, Location(world, x.toDouble() + 0.5, min.y.toDouble(), z.toDouble() + 0.5), 1, 0.0, 0.0, 0.0, 0.0)
                world.spawnParticle(particle, Location(world, x.toDouble() + 0.5, max.y.toDouble() + 1.0, z.toDouble() + 0.5), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
        
        for (y in min.y..max.y) {
            for (x in min.x..max.x) {
                world.spawnParticle(particle, Location(world, x.toDouble() + 0.5, y.toDouble() + 0.5, min.z.toDouble()), 1, 0.0, 0.0, 0.0, 0.0)
                world.spawnParticle(particle, Location(world, x.toDouble() + 0.5, y.toDouble() + 0.5, max.z.toDouble() + 1.0), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
        
        for (y in min.y..max.y) {
            for (z in min.z..max.z) {
                world.spawnParticle(particle, Location(world, min.x.toDouble(), y.toDouble() + 0.5, z.toDouble() + 0.5), 1, 0.0, 0.0, 0.0, 0.0)
                world.spawnParticle(particle, Location(world, max.x.toDouble() + 1.0, y.toDouble() + 0.5, z.toDouble() + 0.5), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }
}