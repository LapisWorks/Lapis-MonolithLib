package top.mc506lw.monolith.core.model

import org.bukkit.NamespacedKey
import org.joml.Vector3f
import top.mc506lw.monolith.core.math.Vector3i

data class BlueprintMeta(
    val displayName: String = "",
    val description: String = "",
    val controllerOffset: Vector3i = Vector3i.ZERO,
    val displayOffset: Vector3f = Vector3f(),
    val author: String = ""
)

class Blueprint(
    val id: String,
    val stages: Map<BuildStage, Shape>,
    val meta: BlueprintMeta = BlueprintMeta(),
    val displayEntities: List<DisplayEntityData> = emptyList(),
    val slots: Map<String, Vector3i> = emptyMap(),
    val customData: Map<String, Any> = emptyMap(),
    val controllerRebarKey: NamespacedKey? = null,
    val controllerMaterial: org.bukkit.Material = org.bukkit.Material.STRUCTURE_BLOCK,
    val formStrategy: FormStrategy = FormStrategy.BlockOnly
) {
    val scaffoldShape: Shape get() = stages[BuildStage.SCAFFOLD] ?: stages.values.firstOrNull() ?: Shape.EMPTY
    val assembledShape: Shape get() = stages[BuildStage.ASSEMBLED] ?: stages.values.firstOrNull() ?: Shape.EMPTY

    val sizeX: Int get() = assembledShape.boundingBox.width
    val sizeY: Int get() = assembledShape.boundingBox.height
    val sizeZ: Int get() = assembledShape.boundingBox.depth
    val blockCount: Int get() = assembledShape.blocks.size

    @Deprecated("Use scaffoldShape or assembledShape", ReplaceWith("assembledShape"))
    val shape: Shape get() = assembledShape

    fun getSlotPosition(slotId: String): Vector3i? = slots[slotId]

    /** Returns the base slot and all numbered ports, for example input, input_1, input_2. */
    fun getSlotPositions(slotType: String): Map<String, Vector3i> = slots
        .filterKeys { it == slotType || it.startsWith("${slotType}_") }
        .toSortedMap()

    fun getCustomData(key: String): Any? = customData[key]
    fun getCustomString(key: String): String? = customData[key] as? String
    fun getCustomInt(key: String): Int? = (customData[key] as? Number)?.toInt()
    fun getCustomDouble(key: String): Double? = (customData[key] as? Number)?.toDouble()
    fun getCustomBoolean(key: String): Boolean? = customData[key] as? Boolean

    companion object {
        fun builder(id: String): BlueprintBuilder = BlueprintBuilder(id)

        fun fromSingleShape(id: String, shape: Shape, meta: BlueprintMeta = BlueprintMeta()): Blueprint {
            return Blueprint(
                id = id,
                stages = mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape),
                meta = meta
            )
        }

        fun fromSingleShape(
            id: String, shape: Shape, meta: BlueprintMeta,
            displayEntities: List<DisplayEntityData>?
        ): Blueprint {
            return Blueprint(
                id = id,
                stages = mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape),
                meta = meta,
                displayEntities = displayEntities ?: emptyList()
            )
        }

        fun fromSingleShape(
            id: String, shape: Shape, meta: BlueprintMeta,
            displayEntities: List<DisplayEntityData>?,
            formStrategy: FormStrategy = FormStrategy.BlockOnly,
            controllerRebarKey: NamespacedKey? = null,
            controllerMaterial: org.bukkit.Material = org.bukkit.Material.STRUCTURE_BLOCK
        ): Blueprint {
            return Blueprint(
                id = id,
                stages = mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape),
                meta = meta,
                displayEntities = displayEntities ?: emptyList(),
                formStrategy = formStrategy,
                controllerRebarKey = controllerRebarKey,
                controllerMaterial = controllerMaterial
            )
        }
    }
}

class BlueprintBuilder(private val id: String) {
    private var scaffoldShape: Shape? = null
    private var assembledShape: Shape? = null
    private var singleShape: Shape? = null
    private var meta: BlueprintMeta = BlueprintMeta()
    private val displayEntities = mutableListOf<DisplayEntityData>()
    private val slots = mutableMapOf<String, Vector3i>()
    private val customData = mutableMapOf<String, Any>()
    private var controllerRebarKey: NamespacedKey? = null
    private var controllerMaterial: org.bukkit.Material = org.bukkit.Material.STRUCTURE_BLOCK
    private var formStrategy: FormStrategy = FormStrategy.BlockOnly

    fun shape(shape: Shape): BlueprintBuilder {
        this.singleShape = shape
        return this
    }

    fun shape(blocks: List<BlockEntry>): BlueprintBuilder {
        this.singleShape = Shape(blocks)
        return this
    }

    fun scaffoldShape(shape: Shape): BlueprintBuilder {
        this.scaffoldShape = shape
        return this
    }

    fun assembledShape(shape: Shape): BlueprintBuilder {
        this.assembledShape = shape
        return this
    }

    fun displayName(name: String): BlueprintBuilder {
        this.meta = meta.copy(displayName = name)
        return this
    }

    fun description(desc: String): BlueprintBuilder {
        this.meta = meta.copy(description = desc)
        return this
    }

    fun controllerOffset(x: Int, y: Int, z: Int): BlueprintBuilder {
        this.meta = meta.copy(controllerOffset = Vector3i(x, y, z))
        return this
    }

    fun controllerOffset(offset: Vector3i): BlueprintBuilder {
        this.meta = meta.copy(controllerOffset = offset)
        return this
    }

    fun displayOffset(x: Int, y: Int, z: Int): BlueprintBuilder {
        return displayOffset(x.toFloat(), y.toFloat(), z.toFloat())
    }

    fun displayOffset(x: Float, y: Float, z: Float): BlueprintBuilder {
        this.meta = meta.copy(displayOffset = Vector3f(x, y, z))
        return this
    }

    fun displayOffset(offset: Vector3f): BlueprintBuilder {
        this.meta = meta.copy(displayOffset = Vector3f(offset))
        return this
    }

    fun displayEntity(data: DisplayEntityData): BlueprintBuilder {
        displayEntities.add(data)
        return this
    }

    fun displayEntities(entities: List<DisplayEntityData>): BlueprintBuilder {
        displayEntities.addAll(entities)
        return this
    }

    fun slot(slotId: String, x: Int, y: Int, z: Int): BlueprintBuilder {
        slots[slotId] = Vector3i(x, y, z)
        return this
    }

    fun slot(slotId: String, position: Vector3i): BlueprintBuilder {
        slots[slotId] = position
        return this
    }

    fun customData(key: String, value: Any): BlueprintBuilder {
        customData[key] = value
        return this
    }

    fun controllerRebar(key: NamespacedKey?): BlueprintBuilder {
        this.controllerRebarKey = key
        return this
    }

    fun controllerMaterial(material: org.bukkit.Material): BlueprintBuilder {
        this.controllerMaterial = material
        return this
    }

    fun formStrategy(strategy: FormStrategy): BlueprintBuilder {
        this.formStrategy = strategy
        return this
    }

    fun build(): Blueprint {
        val stages = if (scaffoldShape != null || assembledShape != null) {
            val scaffold = scaffoldShape ?: assembledShape
                ?: singleShape
                ?: throw IllegalStateException("Blueprint must have a shape")
            val assembled = assembledShape ?: scaffoldShape
                ?: singleShape
                ?: throw IllegalStateException("Blueprint must have a shape")
            mapOf(BuildStage.SCAFFOLD to scaffold, BuildStage.ASSEMBLED to assembled)
        } else {
            val shape = singleShape ?: throw IllegalStateException("Blueprint must have a shape")
            mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape)
        }

        return Blueprint(
            id = id,
            stages = stages,
            meta = meta,
            displayEntities = displayEntities.toList(),
            slots = slots.toMap(),
            customData = customData.toMap(),
            controllerRebarKey = controllerRebarKey,
            controllerMaterial = controllerMaterial,
            formStrategy = formStrategy
        )
    }
}
