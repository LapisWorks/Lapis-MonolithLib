package top.mc506lw.monolith.feature.preview

import top.mc506lw.monolith.MonolithLib

class PreviewModule(private val plugin: MonolithLib) {
    private val ghostRenderer = GhostRenderer(plugin)
    
    fun getGhostRenderer(): GhostRenderer = ghostRenderer
    
    fun onDisable() {
        ghostRenderer.cleanup()
    }
}
