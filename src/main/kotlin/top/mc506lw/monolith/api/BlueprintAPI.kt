package top.mc506lw.monolith.api

import org.bukkit.Location
import org.bukkit.entity.Player
import top.mc506lw.monolith.core.io.IOModule
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.integration.ProjectControllerRegistry
import java.util.logging.Logger

class BlueprintAPI : MonolithAPI {

    private val delegate: MonolithAPIImpl = MonolithAPIImpl(MonolithLib.instance.dataFolder)

    override val registry: BlueprintRegistry get() = delegate.registry
    override val io: IOFacade get() = delegate.io
    override val preview: PreviewFacade get() = delegate.preview

    override fun reloadStructures() {
        delegate.registry.clear()

        val ioModule = IOModule(MonolithLib.instance.dataFolder)
        val blueprints = ioModule.loadAllBlueprints()

        blueprints.forEach { blueprint ->
            ProjectControllerRegistry.ensureRegistered(blueprint)
            registry.register(blueprint)
        }

        Logger.getLogger("MonolithLib").info("Reloaded ${blueprints.size} blueprints")

    }

    internal fun getLegacyBlueprint(id: String): Blueprint? = registry.get(id)
}
