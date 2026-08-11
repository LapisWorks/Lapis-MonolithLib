package top.mc506lw.monolith.integration

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.registry.RebarRegistry
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.model.Blueprint

/** Registers only controllers declared by disk-backed Projects. */
object ProjectControllerRegistry {
    private val log = MonolithLogger.getLogger("ProjectController")

    fun ensureRegistered(blueprint: Blueprint): Boolean {
        val key = blueprint.controllerRebarKey ?: return false
        if (RebarRegistry.BLOCKS.contains(key)) return true
        return runCatching {
            RebarBlock.register(key, blueprint.controllerMaterial, MNBController::class.java)
            log.info { "Registered generated project controller $key for ${blueprint.id} with ${blueprint.controllerMaterial}" }
            true
        }.onFailure {
            log.warn { "Could not register project controller $key for ${blueprint.id}: ${it.message}" }
        }.getOrDefault(false)
    }

    fun isRegistered(blueprint: Blueprint): Boolean =
        blueprint.controllerRebarKey?.let { RebarRegistry.BLOCKS.contains(it) } == true
}
