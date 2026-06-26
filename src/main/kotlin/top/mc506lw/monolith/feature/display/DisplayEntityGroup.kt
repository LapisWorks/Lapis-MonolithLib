package top.mc506lw.monolith.feature.display

import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.entity.display.BlockDisplayBuilder
import io.github.pylonmc.rebar.entity.display.transform.TransformBuilder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchor
import top.mc506lw.monolith.common.MonolithLogger

class DisplayEntityGroup(
    private val anchor: EntityHolderRebarBlock,
    centerLocation: Location,
    private val displayOffset: Vector3f = Vector3f(),
    initialYaw: Float = 0f
) {
    private val transformSystem = DisplayTransformSystem()
    private var rootEntity: Entity? = null
    private val childEntities = mutableListOf<Entity>()
    private var centerLoc: Location = centerLocation
    private val logger = MonolithLogger.getLogger("DEG")

    init {
        transformSystem.setPlacementYaw(initialYaw)
        createRootEntity(centerLocation)
        logger.debug("group", "展示实体组初始化", "initialYaw" to String.format("%.1f", initialYaw))
    }

    val location: Location get() = rootEntity?.location ?: centerLoc

    private fun createRootEntity(centerLocation: Location) {
        val world = centerLocation.world!!
        val rootLoc = Location(
            world,
            centerLocation.x + displayOffset.x(),
            centerLocation.y + displayOffset.y(),
            centerLocation.z + displayOffset.z()
        )

        rootEntity = BlockDisplayBuilder()
            .material(Material.BARRIER)
            .transformation(TransformBuilder()
                .scale(0.001, 0.001, 0.001)
                .build()
            )
            .build(rootLoc)

        updateRootEntityTransformation()

        anchor.addEntity(VirtualDisplayAnchor.DISPLAY_GROUP_KEY, rootEntity!!)
    }

    fun addBlockDisplay(
        name: String,
        blockData: BlockData,
        translation: Vector3f,
        scale: Vector3f,
        rotation: Quaternionf? = null
    ): Entity {
        val root = rootEntity ?: throw IllegalStateException("Root entity not initialized")

        val blueprintTransform = DisplayEntityTransform(
            translation = Vector3f(translation),
            rotation = rotation ?: Quaternionf(),
            scale = Vector3f(scale)
        )

        val entityIndex = transformSystem.addEntity(blueprintTransform)

        val display = BlockDisplayBuilder()
            .material(blockData.material)
            .build(root.location)

        (display as? BlockDisplay)?.let { entity ->
            try {
                entity.block = blockData
            } catch (_: Exception) {}
        }

        childEntities.add(display)
        anchor.addEntity("${VirtualDisplayAnchor.ENTITY_PREFIX}$name", display)

        applyTransformationToEntity(entityIndex, display)

        if (entityIndex == 0) {
            logger.trace("group", "添加展示实体", "name" to name, "index" to entityIndex, "trans" to blueprintTransform.translation, "rot" to blueprintTransform.rotation)
        }

        return display
    }

    fun setYaw(newYaw: Float) {
        val oldYaw = transformSystem.placementYaw
        transformSystem.setPlacementYaw(newYaw)

        logger.debug("group", "朝向变更", "oldYaw" to String.format("%.1f", oldYaw), "newYaw" to String.format("%.1f", newYaw))

        updateRootEntityTransformation()
        applyAllTransformations()
    }

    fun rotate(deltaYaw: Float) {
        setYaw(transformSystem.placementYaw + deltaYaw)
    }

    fun moveTo(newLocation: Location) {
        centerLoc = newLocation
        rootEntity?.teleport(Location(
            newLocation.world,
            newLocation.x + displayOffset.x(),
            newLocation.y + displayOffset.y(),
            newLocation.z + displayOffset.z()
        ))
    }

    fun remove() {
        childEntities.forEach { it.remove() }
        childEntities.clear()
        transformSystem.clear()
        rootEntity?.remove()
        rootEntity = null
    }

    fun getRootEntity(): Entity? = rootEntity

    fun getChildEntities(): List<Entity> = childEntities.toList()

    fun getSize(): Int = childEntities.size

    fun getCurrentYaw(): Float = transformSystem.placementYaw

    private fun updateRootEntityTransformation() {
        val groupRotation = DisplayTransformSystem.yawToQuaternion(transformSystem.placementYaw)

        (rootEntity as? BlockDisplay)?.let { entity ->
            val currentTransform = entity.transformation
            val blockData = entity.block
            entity.transformation = Transformation(
                currentTransform.translation,
                groupRotation,
                currentTransform.scale,
                currentTransform.rightRotation
            )
            try {
                entity.block = blockData
            } catch (_: Exception) {}
        }
    }

    private fun applyAllTransformations() {
        childEntities.forEachIndexed { index, entity ->
            if (index < transformSystem.size()) {
                applyTransformationToEntity(index, entity)
            }
        }
    }

    private fun applyTransformationToEntity(entityIndex: Int, entity: Entity) {
        try {
            val finalTransform = transformSystem.getFinalTransform(entityIndex)

            (entity as? BlockDisplay)?.let { display ->
                val blockData = display.block
                display.transformation = finalTransform.toTransformation()
                try {
                    display.block = blockData
                } catch (_: Exception) {}
            }

            if (entityIndex == 0) {
                logger.trace("group", "实体变换应用", "index" to entityIndex, "trans" to finalTransform.translation, "rot" to finalTransform.rotation)
            }
        } catch (e: Exception) {
            logger.warn("group", "变换应用失败", "index" to entityIndex, "error" to e.message)
        }
    }

    companion object {
        const val DISPLAY_GROUP_KEY = "display_group"
        const val ENTITY_PREFIX = "vde_"
    }
}
