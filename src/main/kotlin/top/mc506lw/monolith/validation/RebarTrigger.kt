package top.mc506lw.monolith.validation

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.interfaces.RebarMultiblock
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.BlockPlaceEvent
import top.mc506lw.monolith.MonolithLib
import java.util.concurrent.CompletableFuture

object RebarTrigger {
    
    private val logger = MonolithLib.instance.logger
    
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
        
        logger.info("[RebarTrigger] 尝试触发多方块形成 at ${block.location}")
        logger.info("[RebarTrigger] 方块类型: ${block.type}, BlockData: ${block.blockData.asString}")
        
        val rebarBlock = BlockStorage.get(block)
        if (rebarBlock == null) {
            logger.warning("[RebarTrigger] 未找到 Rebar 方块 at ${block.location}")
            return false
        }
        
        logger.info("[RebarTrigger] 找到 Rebar 方块: ${rebarBlock.schema.key}")
        
        if (rebarBlock !is RebarMultiblock) {
            logger.warning("[RebarTrigger] Rebar 方块不是 RebarMultiblock 类型")
            return false
        }
        
        try {
            val multiblock = rebarBlock as RebarMultiblock
            
            val isFormed = multiblock.checkFormed()
            logger.info("[RebarTrigger] checkFormed() = $isFormed")
            
            if (isFormed) {
                if (!multiblock.isFormedAndFullyLoaded()) {
                    logger.info("[RebarTrigger] 调用 onMultiblockFormed()")
                    multiblock.onMultiblockFormed()
                }
                return true
            }
            
            return false
        } catch (e: Exception) {
            logger.severe("[RebarTrigger] 触发多方块形成异常: ${e.message}")
            e.printStackTrace()
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
