package top.mc506lw.monolith.lifecycle

import org.bukkit.Bukkit
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.validation.AsyncValidator
import top.mc506lw.monolith.validation.ValidationEngine
import top.mc506lw.monolith.validation.ValidationResult

class BlueprintLifecycle(
    val stateManager: StateManager,
    private val blueprint: Blueprint,
    private val transform: CoordinateTransform,
    private val validationEngine: ValidationEngine,
    private val asyncValidator: AsyncValidator,
    private val onFormed: (StateManager) -> Unit = {},
    private val onBroken: (StateManager) -> Unit = {},
    private val onIncomplete: (StateManager) -> Unit = {}
) {
    fun startMonitoring() {
        if (stateManager.isUnformed()) {
            asyncValidator.startValidation(stateManager.controllerLocation.toString()) {
                performAsyncValidation()
            }
        }
        
        stateManager.updateState(BuildState.FORMING)
    }
    
    fun stopMonitoring() {
        asyncValidator.stopValidation(stateManager.controllerLocation.toString())
    }
    
    fun handleChunkUnload() {
        stateManager.markChunkUnloaded()
        stopMonitoring()
    }
    
    fun handleChunkLoad() {
        if (stateManager.state == BuildState.CHUNK_UNLOADED) {
            stateManager.resetToUnformed()
            startMonitoring()
        }
    }
    
    private fun performAsyncValidation() {
        val result = validationEngine.validateAsync(stateManager.controllerLocation)
        
        when (result) {
            is ValidationResult.Success -> {
                submitMainThread {
                    stateManager.updateState(BuildState.FORMED)
                    buildPositionCache()
                    onFormed(stateManager)
                }
                stopMonitoring()
            }
            is ValidationResult.Failure -> {
                if (stateManager.state == BuildState.FORMED) {
                    submitMainThread {
                        stateManager.updateState(BuildState.INCOMPLETE)
                        stateManager.positionCache.clear()
                        onBroken(stateManager)
                    }
                }
            }
            ValidationResult.Pending -> {}
        }
    }
    
    private fun buildPositionCache() {
        val shape = blueprint.assembledShape
        val centerOffset = blueprint.meta.controllerOffset
        
        for (blockEntry in shape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(
                    stateManager.controllerLocation.blockX,
                    stateManager.controllerLocation.blockY,
                    stateManager.controllerLocation.blockZ
                ),
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )
            
            val chunkKey = PositionCache.getChunkKey(worldPos.x, worldPos.z)
            stateManager.positionCache.addPosition(chunkKey, blockEntry.position)
        }
    }
    
    private fun submitMainThread(action: () -> Unit) {
        Bukkit.getScheduler().runTask(top.mc506lw.monolith.MonolithLib.instance, action)
    }
}
