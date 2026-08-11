package top.mc506lw.monolith.api

import org.bukkit.Location
import org.bukkit.entity.Player
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.feature.preview.PreviewSession
import org.bukkit.NamespacedKey
import java.io.File

interface BlueprintRegistry {
    fun register(blueprint: Blueprint)
    fun get(id: String): Blueprint?
    fun getAll(): Map<String, Blueprint>
    fun getByControllerKey(key: NamespacedKey): List<Blueprint>
    fun contains(id: String): Boolean
    fun remove(id: String): Blueprint?
    fun clear()
    val size: Int
}

interface IOFacade {
    fun loadShape(file: File, format: String? = null): Shape?
    fun loadRawMNB(file: File): Blueprint?
    fun loadBuiltMNB(file: File): Blueprint?
    fun compileRawToBuilt(rawFile: File, configFile: File): Blueprint?
    fun getSupportedFormats(): List<String>
    fun getSupportedExtensions(): Set<String>
    /** 保存录制完成的 base blueprint；成功返回 true。 */
    fun saveBaseBlueprint(blueprint: Blueprint, id: String): Boolean
}

interface PreviewFacade {
    fun start(player: Player, blueprint: Blueprint, location: Location, facing: Facing = Facing.NORTH)
    fun start(player: Player, blueprintId: String, location: Location, facing: Facing = Facing.NORTH): PreviewSession?
    fun stop(player: Player)
    fun getPlayerSessions(player: Player): List<PreviewSession>
    fun nextLayer(player: Player): Boolean
    fun prevLayer(player: Player): Boolean
    fun setLayer(player: Player, layer: Int): Boolean
}

interface MonolithAPI {

    val registry: BlueprintRegistry

    val io: IOFacade

    val preview: PreviewFacade

    fun reloadStructures()

    companion object {
        @Volatile
        private var instance: MonolithAPI? = null

        fun getInstance(): MonolithAPI {
            return instance ?: throw IllegalStateException("MonolithAPI not initialized")
        }

        internal fun setInstance(api: MonolithAPI) {
            instance = api
        }
    }
}
