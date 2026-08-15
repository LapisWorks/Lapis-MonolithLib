package top.mc506lw.monolith.validation

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.BlockPlaceEvent
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.common.MonolithLogger
import java.util.concurrent.CompletableFuture

object RebarTrigger {
    
    private val logger = MonolithLogger.getLogger("RebarTrigger")
    
    fun triggerFormation(controllerLocation: Location): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        
        Bukkit.getScheduler().runTask(MonolithLib.instance, Runnable {
            val result = triggerFormationSync(controllerLocation)
            future.complete(result)
        })
        
        return future
    }
    
    fun triggerFormationSync(controllerLocation: Location): Boolean {
        val block = controllerLocation.block
        
        val rebarBlock = BlockStorage.get(block)
        if (rebarBlock == null) {
            logger.warn("trigger", "未找到 Rebar 方块", "pos" to "${block.location.blockX},${block.location.blockY},${block.location.blockZ}")
            return false
        }
        
        logger.debug("trigger", "尝试触发多方块形成", "pos" to "${block.location.blockX},${block.location.blockY},${block.location.blockZ}", "type" to block.type.name, "rebarKey" to rebarBlock.schema.key.toString())
        
        if (rebarBlock !is RebarMultiblock) {
            logger.warn("trigger", "Rebar 方块不是 RebarMultiblock 类型", "pos" to "${block.location.blockX},${block.location.blockY},${block.location.blockZ}")
            return false
        }
        
        try {
            val multiblock = rebarBlock as RebarMultiblock
            
            val isFormed = multiblock.checkFormed()
            logger.debug("trigger", "checkFormed 结果", "pos" to "${block.location.blockX},${block.location.blockY},${block.location.blockZ}", "formed" to isFormed)
            
            if (isFormed) {
                if (!multiblock.isFormedAndFullyLoaded()) {
                    logger.debug("trigger", "调用 onMultiblockFormed", "pos" to "${block.location.blockX},${block.location.blockY},${block.location.blockZ}")
                    multiblock.onMultiblockFormed()
                }
                return true
            }
            
            return false
        } catch (e: Exception) {
            logger.error(e, { "触发多方块形成异常" })
            return false
        }
    }
    
    fun triggerFormationByBlockModification(controllerLocation: Location): Boolean {
        val block = controllerLocation.block
        val world = block.world
        
        val type = block.type
        val blockData = block.blockData
        
        block.type = Material.AIR
        
        Bukkit.getScheduler().runTaskLater(MonolithLib.instance, Runnable {
            block.type = type
            block.blockData = blockData
        }, 1L)
        
        return true
    }
    
    fun isRebarMultiblockController(location: Location): Boolean {
        val block = location.block
        val rebarBlock = BlockStorage.get(block) ?: return false
        return rebarBlock is RebarMultiblock
    }
    
    fun getRebarMultiblock(location: Location): RebarMultiblock? {
        val block = location.block
        val rebarBlock = BlockStorage.get(block) ?: return null
        return rebarBlock as? RebarMultiblock
    }
}
