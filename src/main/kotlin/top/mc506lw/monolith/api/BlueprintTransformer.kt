package top.mc506lw.monolith.api

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.joml.Quaternionf
import org.joml.Vector3f
import top.mc506lw.monolith.core.io.BlueprintConfig
import top.mc506lw.monolith.core.io.BuiltMNBCompiler
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.FormStrategy

/**
 * 基于已加载的 [Blueprint]（如 .mnb）做代码化变换，返回编译后的新 Blueprint。
 *
 * 这是 Setting.yml 的代码等价物：所有能力（overrides / scaffold_materials /
 * rotation / display_entities / controller key）都通过复用 [BuiltMNBCompiler]
 * 应用，行为与 YAML 完全一致。附属插件用它定制结构而无需任何配置文件。
 *
 * 用法：
 * ```kotlin
 * val mine = MonolithAPI.io.loadBuiltMNB(file)!!.transform {
 *     controllerRebarKey(MY_CONTROLLER_KEY)
 *     controllerMaterial(Material.STRUCTURE_BLOCK)
 *     scaffoldMaterials(mapOf(Material.IRON_BLOCK to Material.CONCRETE))
 *     overrideAt("1, 0, 0", "rebar", rebarKey = MY_PORT_KEY, preview = Material.HOPPER)
 * }
 * MonolithAPI.registry.register(mine)
 * ```
 */
class BlueprintTransformer private constructor(private val source: Blueprint) {

    private var config = BlueprintConfig(
        id = source.id,
        controllerRebarKey = source.controllerRebarKey,
        controllerPosition = source.meta.controllerOffset,
        generatedControllerMaterial = source.controllerMaterial,
        formStrategy = source.formStrategy.toConfigString(),
        slots = source.slots,
        customData = source.customData,
        metaName = source.meta.displayName,
        metaDescription = source.meta.description,
        metaAuthor = source.meta.author,
        displayOffset = source.meta.displayOffset
    )

    // ========== 元数据 ==========

    fun meta(name: String, description: String = config.metaDescription, author: String = config.metaAuthor) {
        config = config.copy(metaName = name, metaDescription = description, metaAuthor = author)
    }

    fun id(newId: String) {
        config = config.copy(id = newId)
    }

    // ========== 控制器 ==========

    /** 设定 Rebar 控制器 key（必须与附属注册的控制器 key 一致）。 */
    fun controllerRebarKey(key: NamespacedKey?) {
        config = config.copy(controllerRebarKey = key)
    }

    /** 仅当 MonolithLib 需要自行注册该 key 时使用的方块材质。 */
    fun controllerMaterial(material: Material?) {
        config = config.copy(generatedControllerMaterial = material)
    }

    /** 控制器在结构中的相对坐标。 */
    fun controllerPosition(pos: Vector3i) {
        config = config.copy(controllerPosition = pos)
    }

    // ========== 槽位 ==========

    fun slot(slotId: String, pos: Vector3i) {
        config = config.copy(slots = config.slots + (slotId to pos))
    }

    fun slots(newSlots: Map<String, Vector3i>) {
        config = config.copy(slots = newSlots)
    }

    // ========== 自定义数据 ==========

    fun customData(key: String, value: Any) {
        config = config.copy(customData = config.customData + (key to value))
    }

    // ========== 位置覆盖 (overrides) ==========

    /** 单位置 strict 覆盖：只接受指定方块。 */
    fun overrideStrict(pos: Vector3i, material: Material) {
        overrideAt(pos, "strict", material = material)
    }

    /** 单位置 loose 覆盖：只检查材质，忽略方块状态。 */
    fun overrideLoose(pos: Vector3i, material: Material, preview: Material? = null, ignoreStates: Set<String> = emptySet()) {
        overrideAt(pos, "loose", material = material, preview = preview, ignoreStates = ignoreStates)
    }

    /** 单位置 rebar 覆盖：该位置接受指定的 Rebar 方块 key。 */
    fun overrideRebar(pos: Vector3i, rebarKey: NamespacedKey, preview: Material = Material.STONE) {
        overrideAt(pos, "rebar", rebarKey = rebarKey, preview = preview)
    }

    /** 通用覆盖入口，行为与 Setting.yml overrides 完全一致。 */
    fun overrideAt(
        pos: Vector3i,
        type: String,
        material: Material? = null,
        rebarKey: NamespacedKey? = null,
        preview: Material? = null,
        ignoreStates: Set<String> = emptySet()
    ) {
        config = config.copy(
            overrides = config.overrides + (pos to BlueprintConfig.OverrideEntry(
                type = type,
                material = material,
                rebarKey = rebarKey,
                previewMaterial = preview,
                ignoreStates = ignoreStates
            ))
        )
    }

    // ========== 脚手架材料映射 ==========

    /** 批量替换脚手架阶段中匹配材料的方块，不影响 assembled。 */
    fun scaffoldMaterials(mapping: Map<Material, Material>) {
        config = config.copy(scaffoldMaterials = mapping)
    }

    // ========== 旋转 ==========

    fun rotate(scaffoldDegrees: Int = 0, assembledDegrees: Int = 0, center: Vector3i? = null) {
        config = config.copy(
            scaffoldRotation = scaffoldDegrees,
            assembledRotation = assembledDegrees,
            rotationCenter = center
        )
    }

    // ========== 展示实体 ==========

    fun displayOffset(offset: Vector3f) {
        config = config.copy(displayOffset = offset)
    }

    /** 按位置覆写录制时录入的展示实体，行为与 Setting.yml display_entities 一致。 */
    fun displayOverride(
        pos: Vector3i,
        type: String? = null,
        block: String? = null,
        item: String? = null,
        translation: Vector3f? = null,
        rotation: Quaternionf? = null,
        scale: Vector3f? = null,
        group: String? = null
    ) {
        config = config.copy(
            displayOverrides = config.displayOverrides + (pos to BlueprintConfig.DisplayOverride(
                type = type, block = block, item = item,
                translation = translation, rotation = rotation, scale = scale, group = group
            ))
        )
    }

    // ========== 成型策略 ==========

    fun formStrategy(strategy: String) {
        config = config.copy(formStrategy = strategy)
    }

    /** 应用所有变换，返回编译后的新 Blueprint。 */
    fun build(): Blueprint = BuiltMNBCompiler.compile(source, config)

    companion object {
        fun of(blueprint: Blueprint): BlueprintTransformer = BlueprintTransformer(blueprint)

        private fun FormStrategy.toConfigString(): String = when (this) {
            is FormStrategy.BlockOnly -> "block_only"
            is FormStrategy.FullDisplay -> "full_display"
            is FormStrategy.Hybrid -> "hybrid"
        }
    }
}

/** 便捷入口：基于已有 Blueprint 做代码化变换。 */
fun Blueprint.transform(init: BlueprintTransformer.() -> Unit): Blueprint =
    BlueprintTransformer.of(this).apply(init).build()
