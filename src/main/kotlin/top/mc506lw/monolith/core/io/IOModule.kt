package top.mc506lw.monolith.core.io

import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.io.formats.BinaryFormat
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.BlueprintMeta
import top.mc506lw.monolith.core.model.BuildStage
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.Shape
import java.io.File

/** Implements the on-disk workflow defined by the Monolith vision. */
class IOModule(dataFolder: File) {

    private val log = MonolithLogger.getLogger("IO")
    private val temFolder = File(dataFolder, "Tem")
    private val worksFolder = File(dataFolder, "Works")
    private val projectsFolder = File(dataFolder, "Projects")

    init {
        temFolder.mkdirs()
        worksFolder.mkdirs()
        projectsFolder.mkdirs()
    }

    val temDirectory: File get() = temFolder
    val worksDirectory: File get() = worksFolder
    val projectsDirectory: File get() = projectsFolder

    fun saveStage(
        id: String,
        stage: BuildStage,
        shape: Shape,
        displayEntities: List<DisplayEntityData>,
        controllerOffset: Vector3i
    ): File? {
        val suffix = if (stage == BuildStage.SCAFFOLD) "scaffold" else "assembled"
        val file = File(temFolder, "$id.mnb.$suffix")
        val blueprint = Blueprint(
            id = id,
            stages = mapOf(stage to shape),
            meta = BlueprintMeta(displayName = id, controllerOffset = controllerOffset),
            displayEntities = displayEntities
        )
        return if (save(blueprint, file)) file else null
    }

    fun mergeStages(id: String): Result<Blueprint> = runCatching {
        val scaffoldFile = File(temFolder, "$id.mnb.scaffold")
        val assembledFile = File(temFolder, "$id.mnb.assembled")
        require(scaffoldFile.isFile) { "缺少 ${scaffoldFile.name}" }
        require(assembledFile.isFile) { "缺少 ${assembledFile.name}" }

        val scaffoldSource = load(scaffoldFile) ?: error("无法读取 ${scaffoldFile.name}")
        val assembledSource = load(assembledFile) ?: error("无法读取 ${assembledFile.name}")
        val scaffold = scaffoldSource.scaffoldShape
        val assembled = assembledSource.assembledShape
        val scaffoldBox = scaffold.boundingBox
        val assembledBox = assembled.boundingBox
        require(
            scaffoldBox.sizeX == assembledBox.sizeX &&
                scaffoldBox.sizeY == assembledBox.sizeY &&
                scaffoldBox.sizeZ == assembledBox.sizeZ
        ) {
            "脚手架和成型阶段占据的空间不一致 " +
                "(脚手架 ${scaffoldBox.sizeX}x${scaffoldBox.sizeY}x${scaffoldBox.sizeZ} vs " +
                "成型 ${assembledBox.sizeX}x${assembledBox.sizeY}x${assembledBox.sizeZ})"
        }

        val blueprint = Blueprint(
            id = id,
            stages = mapOf(BuildStage.SCAFFOLD to scaffold, BuildStage.ASSEMBLED to assembled),
            meta = assembledSource.meta,
            displayEntities = assembledSource.displayEntities
        )
        val workDir = File(worksFolder, id).apply { mkdirs() }
        val workMnb = File(workDir, "$id.mnb")
        check(save(blueprint, workMnb)) { "写入 ${workMnb.name} 失败" }

        val setting = File(workDir, "Setting.yml")
        if (!setting.exists()) BlueprintConfigLoader.generateDefault(id, blueprint, setting)
        blueprint
    }

    fun rebuildProject(id: String): Blueprint? {
        val workDir = File(worksFolder, id)
        val source = File(workDir, "$id.mnb")
        if (!source.isFile) return null
        val raw = load(source) ?: return null
        val setting = File(workDir, "Setting.yml")
        val built = BlueprintConfigLoader.load(setting)?.let { BuiltMNBCompiler.compile(raw, it) } ?: raw
        val project = File(projectsFolder, "$id.mnb")
        return if (save(built, project)) built else null
    }

    fun loadAllBlueprints(): List<Blueprint> {
        worksFolder.listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach { rebuildProject(it.name) }

        return projectsFolder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("mnb", true) }
            ?.mapNotNull(::load)
            ?.toList()
            ?: emptyList()
    }

    /** Programmatic definitions enter Works and are compiled through the same path. */
    internal fun saveBaseBlueprint(blueprint: Blueprint, id: String): File? {
        val workDir = File(worksFolder, id).apply { mkdirs() }
        val source = File(workDir, "$id.mnb")
        if (!save(blueprint, source)) return null
        val setting = File(workDir, "Setting.yml")
        if (!setting.exists()) BlueprintConfigLoader.generateDefault(id, blueprint, setting)
        return rebuildProject(id)?.let { source }
    }

    private fun load(file: File): Blueprint? = BinaryFormat.load(file)

    private fun save(blueprint: Blueprint, file: File): Boolean = try {
        BinaryFormat.save(blueprint, file, formatVersion = 5, configHash = "")
        true
    } catch (e: Exception) {
        log.error { "保存 MNB 失败: ${file.absolutePath} - ${e.message}" }
        false
    }
}
