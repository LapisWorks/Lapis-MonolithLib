package top.mc506lw.monolith.api

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import top.mc506lw.monolith.core.io.IOModule
import top.mc506lw.monolith.core.io.ShapeIO
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.feature.preview.PreviewSession
import top.mc506lw.monolith.feature.preview.StructurePreviewManager
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.integration.ProjectControllerRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BlueprintRegistryImpl : BlueprintRegistry {

    private val blueprints = ConcurrentHashMap<String, Blueprint>()
    private val blueprintIdsByControllerKey = ConcurrentHashMap<NamespacedKey, String>()

    override fun register(blueprint: Blueprint) {
        blueprints[blueprint.id] = blueprint

        val controllerKey = blueprint.controllerRebarKey
        if (controllerKey != null) {
            blueprintIdsByControllerKey[controllerKey] = blueprint.id
        }
    }

    /**
     * 根据控制器 key 查找对应的蓝图 ID。
     * 用于 [top.mc506lw.monolith.integration.MNBController] 在无 PDC 的情况下查找。
     */
    fun getBlueprintIdByKey(key: NamespacedKey): String? = blueprintIdsByControllerKey[key]

    override fun get(id: String): Blueprint? = blueprints[id]

    override fun getAll(): Map<String, Blueprint> = blueprints.toMap()

    override fun getByControllerKey(key: NamespacedKey): List<Blueprint> {
        return blueprints.values.filter { it.controllerRebarKey == key }
    }

    override fun contains(id: String): Boolean = blueprints.containsKey(id)

    override fun remove(id: String): Blueprint? = blueprints.remove(id)

    override fun clear() = blueprints.clear()

    override val size: Int get() = blueprints.size
}

class IOFacadeImpl(private val dataFolder: File) : IOFacade {

    private val shapeIO: ShapeIO = ShapeIO.create(dataFolder)
    private val ioModule: IOModule = IOModule(dataFolder)

    override fun loadShape(file: File, format: String?): Shape? {
        return shapeIO.loadShape(file, format)
    }

    override fun loadRawMNB(file: File): Blueprint? {
        return try {
            top.mc506lw.monolith.core.io.formats.BinaryFormat.load(file)
        } catch (_: Exception) {
            null
        }
    }

    override fun loadBuiltMNB(file: File): Blueprint? {
        return loadRawMNB(file)
    }

    override fun loadBuiltMNB(input: java.io.InputStream): Blueprint? {
        return try {
            top.mc506lw.monolith.core.io.formats.BinaryFormat.deserialize(input)
        } catch (_: Exception) {
            null
        }
    }

    override fun compileRawToBuilt(rawFile: File, configFile: File): Blueprint? {
        val rawBlueprint = loadRawMNB(rawFile) ?: return null
        val config = top.mc506lw.monolith.core.io.BlueprintConfigLoader.load(configFile) ?: return rawBlueprint
        return top.mc506lw.monolith.core.io.ProductBuilder.build(rawBlueprint, config)
    }

    override fun getSupportedFormats(): List<String> = shapeIO.getSupportedFormats()

    override fun getSupportedExtensions(): Set<String> = shapeIO.getSupportedExtensions()

    override fun saveBaseBlueprint(blueprint: Blueprint, id: String): Boolean {
        return ioModule.saveBaseBlueprint(blueprint, id) != null
    }
}

class PreviewFacadeImpl(private val registry: BlueprintRegistry) : PreviewFacade {

    override fun start(player: Player, blueprint: Blueprint, location: Location, facing: Facing) {
        StructurePreviewManager.startPreview(
            player = player,
            blueprint = blueprint,
            controllerLocation = location,
            facing = facing
        )
    }

    override fun start(player: Player, blueprintId: String, location: Location, facing: Facing): PreviewSession? {
        val blueprint = registry.get(blueprintId) ?: return null
        return StructurePreviewManager.startPreview(
            player = player,
            blueprint = blueprint,
            controllerLocation = location,
            facing = facing
        )
    }

    override fun stop(player: Player) {
        StructurePreviewManager.cancelPreview(player)
    }

    override fun getPlayerSessions(player: Player): List<PreviewSession> {
        val session = StructurePreviewManager.getPreview(player) ?: return emptyList()
        return listOf(session)
    }

    override fun nextLayer(player: Player): Boolean {
        val session = StructurePreviewManager.getPreview(player) ?: return false
        return session.nextLayer()
    }

    override fun prevLayer(player: Player): Boolean {
        val session = StructurePreviewManager.getPreview(player) ?: return false
        return session.prevLayer()
    }

    override fun setLayer(player: Player, layer: Int): Boolean {
        val session = StructurePreviewManager.getPreview(player) ?: return false
        return session.setLayer(layer)
    }
}

class MonolithAPIImpl(
    private val dataFolder: File
) : MonolithAPI {

    override val registry: BlueprintRegistry = BlueprintRegistryImpl()
    override val io: IOFacade = IOFacadeImpl(dataFolder)
    override val preview: PreviewFacade = PreviewFacadeImpl(registry)

    override fun reloadStructures() {
        registry.clear()
        val ioModule = IOModule(dataFolder)
        val blueprints = ioModule.loadAllBlueprints()
        blueprints.forEach {
            ProjectControllerRegistry.ensureRegistered(it)
            registry.register(it)
        }
    }
}
