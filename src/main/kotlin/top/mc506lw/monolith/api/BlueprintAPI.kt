package top.mc506lw.monolith.api

import org.bukkit.Location
import org.bukkit.entity.Player
import top.mc506lw.monolith.core.io.IOModule
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.feature.buildsite.BuildSite
import top.mc506lw.monolith.feature.buildsite.BuildSiteManager
import top.mc506lw.monolith.MonolithLib
import java.util.logging.Logger

class BlueprintAPI : MonolithAPI {

    private val delegate: MonolithAPIImpl = MonolithAPIImpl(MonolithLib.instance.dataFolder)

    override val registry: BlueprintRegistry get() = delegate.registry
    override val io: IOFacade get() = delegate.io
    override val preview: PreviewFacade get() = delegate.preview
    override val buildSite: BuildSiteFacade get() = delegate.buildSite

    override fun reloadStructures() {
        delegate.registry.clear()

        val ioModule = IOModule(MonolithLib.instance.dataFolder)
        val blueprints = ioModule.loadAllBlueprints()

        blueprints.forEach { blueprint ->
            registry.register(blueprint)
        }

        Logger.getLogger("MonolithLib").info("Reloaded ${blueprints.size} blueprints")

        for (site in buildSite.getAllActiveSites()) {
            val updated = registry.get(site.blueprintId)
            if (updated != null) {
                site.blueprint = updated
                Logger.getLogger("MonolithLib").info("[BlueprintAPI] 已更新 BuildSite ${site.id} 的蓝图引用: ${site.blueprintId}")
            }
        }
    }

    internal fun getLegacyBlueprint(id: String): Blueprint? = registry.get(id)
}
