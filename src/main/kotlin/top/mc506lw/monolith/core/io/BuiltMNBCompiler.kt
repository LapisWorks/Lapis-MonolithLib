package top.mc506lw.monolith.core.io

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Stairs
import org.joml.Vector3f
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.BlockEntry
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.BuildStage
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.DisplayType
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.validation.predicate.*
import java.io.File

object BuiltMNBCompiler {

    private val log = MonolithLogger.getLogger("Compiler")

    fun compile(rawFile: File, configFile: File): Blueprint? {
        val rawBlueprint = try {
            top.mc506lw.monolith.core.io.formats.BinaryFormat.load(rawFile)
        } catch (_: Exception) {
            return null
        } ?: return null

        val bpConfig = if (configFile.exists()) {
            BlueprintConfigLoader.load(configFile)
        } else {
            return rawBlueprint
        } ?: return rawBlueprint

        return compile(rawBlueprint, bpConfig)
    }

    fun compile(rawBlueprint: Blueprint, config: BlueprintConfig): Blueprint {
        val scaffoldMaterialMap = config.scaffoldMaterials
        val overrides = config.overrides
        val hasDualStage = rawBlueprint.scaffoldShape !== rawBlueprint.assembledShape &&
            rawBlueprint.scaffoldShape.blocks != rawBlueprint.assembledShape.blocks

        val scaffoldBlocks = mutableListOf<BlockEntry>()
        val assembledBlocks = mutableListOf<BlockEntry>()

        if (hasDualStage) {
            val rawScaffold = rawBlueprint.scaffoldShape
            val rawAssembled = rawBlueprint.assembledShape

            for (blockEntry in rawScaffold.blocks) {
                val pos = blockEntry.position
                val overrideEntry = overrides[pos]
                if (overrideEntry != null) {
                    scaffoldBlocks.add(applyOverrideToScaffold(blockEntry, overrideEntry, scaffoldMaterialMap))
                } else {
                    scaffoldBlocks.add(applyScaffoldMaterial(blockEntry, scaffoldMaterialMap))
                }
            }

            for (blockEntry in rawAssembled.blocks) {
                val pos = blockEntry.position
                val overrideEntry = overrides[pos]
                if (overrideEntry != null) {
                    assembledBlocks.add(applyOverrideToAssembled(blockEntry, overrideEntry))
                } else {
                    assembledBlocks.add(blockEntry)
                }
            }
        } else {
            for (blockEntry in rawBlueprint.assembledShape.blocks) {
                val pos = blockEntry.position
                val overrideEntry = overrides[pos]

                if (overrideEntry != null) {
                    scaffoldBlocks.add(applyOverrideToScaffold(blockEntry, overrideEntry, scaffoldMaterialMap))
                    assembledBlocks.add(applyOverrideToAssembled(blockEntry, overrideEntry))
                } else {
                    scaffoldBlocks.add(applyScaffoldMaterial(blockEntry, scaffoldMaterialMap))
                    assembledBlocks.add(blockEntry)
                }
            }
        }

        var scaffoldShape = Shape(scaffoldBlocks)
        var finalAssembledShape = if (assembledBlocks.toList() != rawBlueprint.assembledShape.blocks.toList()) {
            Shape(assembledBlocks)
        } else {
            rawBlueprint.assembledShape
        }

        if (config.scaffoldRotation != 0 || config.assembledRotation != 0) {
            val center = config.rotationCenter ?: calculateGeometryCenter(scaffoldShape, finalAssembledShape)

            if (config.scaffoldRotation != 0) {
                val steps = config.scaffoldRotation / 90
                log.debug("compile", "应用脚手架旋转", "degrees" to config.scaffoldRotation, "steps" to steps, "center" to center)
                log.trace("compile", "旋转前脚手架", "blockCount" to scaffoldShape.blocks.size)

                scaffoldShape = rotateShape(scaffoldShape, center, steps)

                log.trace("compile", "旋转后脚手架", "blockCount" to scaffoldShape.blocks.size)
            }

            if (config.assembledRotation != 0) {
                val steps = config.assembledRotation / 90
                log.debug("compile", "应用成型旋转", "degrees" to config.assembledRotation, "steps" to steps, "center" to center)
                log.trace("compile", "旋转前成型", "blockCount" to finalAssembledShape.blocks.size)

                finalAssembledShape = rotateShape(finalAssembledShape, center, steps)

                log.trace("compile", "旋转后成型", "blockCount" to finalAssembledShape.blocks.size)
            }
        }

        var displayEntitiesToCorrect = rawBlueprint.displayEntities
        if (config.assembledRotation != 0) {
            val center = config.rotationCenter ?: calculateGeometryCenter(scaffoldShape, finalAssembledShape)
            val steps = config.assembledRotation / 90
            
            if (displayEntitiesToCorrect.isNotEmpty()) {
                log.debug("compile", "修正展示实体blockData", "entityCount" to displayEntitiesToCorrect.size, "steps" to steps)
                displayEntitiesToCorrect = rotateDisplayEntities(displayEntitiesToCorrect, center, steps)
            }
        }
        
        val correctedDisplayEntities = correctDisplayEntities(
            displayEntitiesToCorrect,
            scaffoldShape,
            finalAssembledShape
        )

        // 应用展示实体覆写（允许 yml 修调）
        val finalDisplayEntities = if (config.displayOverrides.isNotEmpty()) {
            applyDisplayOverrides(correctedDisplayEntities, config.displayOverrides)
        } else correctedDisplayEntities

        val controllerRebarKey = config.controllerRebarKey ?: rawBlueprint.controllerRebarKey
        var controllerOffset = config.controllerPosition ?: rawBlueprint.meta.controllerOffset

        if ((config.scaffoldRotation != 0 || config.assembledRotation != 0) && config.controllerPosition == null) {
            val center = config.rotationCenter ?: calculateGeometryCenter(scaffoldShape, finalAssembledShape)
            val scaffoldSteps = config.scaffoldRotation / 90
            val assembledSteps = config.assembledRotation / 90
            if (scaffoldSteps != 0) controllerOffset = rotateCoordinate(controllerOffset, center, scaffoldSteps)
                log.trace("compile", "旋转控制器偏移", "offset" to controllerOffset)
        }

        var finalDisplayOffset = config.displayOffset ?: rawBlueprint.meta.displayOffset

        if (config.assembledRotation != 0) {
            val steps = config.assembledRotation / 90
            finalDisplayOffset = when (steps % 4) {
                1 -> Vector3f(finalDisplayOffset.z, finalDisplayOffset.y, -finalDisplayOffset.x)
                2 -> Vector3f(-finalDisplayOffset.x, finalDisplayOffset.y, -finalDisplayOffset.z)
                3 -> Vector3f(-finalDisplayOffset.z, finalDisplayOffset.y, finalDisplayOffset.x)
                else -> finalDisplayOffset
            }
            log.debug("compile", "旋转展示偏移", "from" to (config.displayOffset ?: rawBlueprint.meta.displayOffset), "to" to finalDisplayOffset)
        }

        log.trace("compile", "展示偏移汇总", "ymlConfig" to config.displayOffset, "blueprintDefault" to rawBlueprint.meta.displayOffset, "final" to finalDisplayOffset)

        val meta = rawBlueprint.meta.copy(
            displayName = if (config.metaName.isNotEmpty()) config.metaName else rawBlueprint.meta.displayName,
            description = if (config.metaDescription.isNotEmpty()) config.metaDescription else rawBlueprint.meta.description,
            author = if (config.metaAuthor.isNotEmpty()) config.metaAuthor else rawBlueprint.meta.author,
            controllerOffset = controllerOffset,
            displayOffset = finalDisplayOffset
        )

        val slots = if (config.slots.isNotEmpty()) {
            if (config.scaffoldRotation != 0 || config.assembledRotation != 0) {
                val center = config.rotationCenter ?: calculateGeometryCenter(scaffoldShape, finalAssembledShape)
                val scaffoldSteps = config.scaffoldRotation / 90
                config.slots.mapValues { (_, pos) -> rotateCoordinate(pos, center, scaffoldSteps) }
            } else {
                config.slots
            }
        } else {
            rawBlueprint.slots
        }

        val customData = if (config.customData.isNotEmpty()) config.customData else rawBlueprint.customData

        // 解析 formStrategy
        val formStrategy = when (config.formStrategy.lowercase()) {
            "full_display", "fulldisplay" -> top.mc506lw.monolith.core.model.FormStrategy.FullDisplay()
            "hybrid" -> {
                val hidden = finalDisplayEntities.map { it.position }.toSet()
                if (hidden.isEmpty()) top.mc506lw.monolith.core.model.FormStrategy.BlockOnly
                else top.mc506lw.monolith.core.model.FormStrategy.Hybrid(hidden)
            }
            else -> top.mc506lw.monolith.core.model.FormStrategy.BlockOnly
        }

        return Blueprint(
            id = config.id.ifEmpty { rawBlueprint.id },
            stages = mapOf(
                BuildStage.SCAFFOLD to scaffoldShape,
                BuildStage.ASSEMBLED to finalAssembledShape
            ),
            meta = meta,
            displayEntities = finalDisplayEntities,
            slots = slots,
            customData = customData,
            controllerRebarKey = controllerRebarKey,
            controllerMaterial = config.generatedControllerMaterial ?: rawBlueprint.controllerMaterial,
            formStrategy = formStrategy
        )
    }

    private fun calculateGeometryCenter(vararg shapes: Shape): Vector3i {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE

        for (shape in shapes) {
            for (block in shape.blocks) {
                minX = minOf(minX, block.position.x)
                minY = minOf(minY, block.position.y)
                minZ = minOf(minZ, block.position.z)
                maxX = maxOf(maxX, block.position.x)
                maxY = maxOf(maxY, block.position.y)
                maxZ = maxOf(maxZ, block.position.z)
            }
        }

        return Vector3i(
            (minX + maxX) / 2,
            (minY + maxY) / 2,
            (minZ + maxZ) / 2
        )
    }

    private fun rotateCoordinate(pos: Vector3i, center: Vector3i, steps: Int): Vector3i {
        if (steps == 0) return pos

        val dx = pos.x - center.x
        val dz = pos.z - center.z

        return when (steps % 4) {
            1 -> Vector3i(center.x + dz, pos.y, center.z - dx)
            2 -> Vector3i(center.x - dx, pos.y, center.z - dz)
            3 -> Vector3i(center.x - dz, pos.y, center.z + dx)
            else -> pos
        }
    }

    private fun rotateBlockData(blockData: BlockData, steps: Int): BlockData {
        if (steps == 0) return blockData

        val rotated = blockData.clone()

        when (rotated) {
            is Stairs -> {
                rotated.facing = rotateFacing(rotated.facing, steps)
                rotated.shape = rotateStairShape(rotated.shape, steps)
            }
            is Directional -> {
                rotated.facing = rotateFacing(rotated.facing, steps)
            }
        }

        return rotated
    }

    private fun rotateFacing(facing: org.bukkit.block.BlockFace, steps: Int): org.bukkit.block.BlockFace {
        val horizontalFaces = listOf(
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.WEST
        )
        val currentIndex = horizontalFaces.indexOf(facing)
        if (currentIndex == -1) return facing

        val newIndex = (currentIndex + steps) % 4
        return horizontalFaces[newIndex]
    }

    private fun rotateStairShape(shape: Stairs.Shape, steps: Int): Stairs.Shape {
        return when (shape) {
            Stairs.Shape.STRAIGHT -> Stairs.Shape.STRAIGHT
            Stairs.Shape.INNER_LEFT, Stairs.Shape.INNER_RIGHT -> {
                if (steps % 2 == 1) {
                    if (shape == Stairs.Shape.INNER_LEFT) Stairs.Shape.INNER_RIGHT else Stairs.Shape.INNER_LEFT
                } else {
                    shape
                }
            }
            Stairs.Shape.OUTER_LEFT, Stairs.Shape.OUTER_RIGHT -> {
                if (steps % 2 == 1) {
                    if (shape == Stairs.Shape.OUTER_LEFT) Stairs.Shape.OUTER_RIGHT else Stairs.Shape.OUTER_LEFT
                } else {
                    shape
                }
            }
        }
    }

    private fun rotateShape(shape: Shape, center: Vector3i, steps: Int): Shape {
        if (steps == 0) return shape

        val rotatedBlocks = shape.blocks.map { blockEntry ->
            BlockEntry(
                position = rotateCoordinate(blockEntry.position, center, steps),
                blockData = rotateBlockData(blockEntry.blockData, steps)
            )
        }

        return Shape(rotatedBlocks)
    }

    private fun rotateDisplayEntities(
        displayEntities: List<DisplayEntityData>,
        center: Vector3i,
        steps: Int
    ): List<DisplayEntityData> {
        if (steps == 0 || displayEntities.isEmpty()) return displayEntities

        log.debug("compile", "旋转展示实体", "steps" to steps, "entityCount" to displayEntities.size)

        return displayEntities.mapIndexed { index, entity ->
            val rp = rotateCoordinate(entity.position, center, steps)
            val rb = entity.blockData?.let { rotateBlockData(it.clone(), steps) }

            if (index < 3) {
                log.trace("compile", "实体旋转详情", "index" to index, "total" to displayEntities.size, "posFrom" to entity.position, "posTo" to rp)
            }

            entity.copy(
                position = rp,
                translation = rotateVector(entity.translation, steps),
                blockData = rb
            )
        }
    }

    private fun rotateVector(value: org.joml.Vector3f, steps: Int): org.joml.Vector3f = when (steps % 4) {
        1 -> org.joml.Vector3f(value.z, value.y, -value.x)
        2 -> org.joml.Vector3f(-value.x, value.y, -value.z)
        3 -> org.joml.Vector3f(-value.z, value.y, value.x)
        else -> org.joml.Vector3f(value)
    }

    private fun correctDisplayEntities(
        displayEntities: List<DisplayEntityData>,
        scaffoldShape: Shape,
        assembledShape: Shape
    ): List<DisplayEntityData> {
        if (displayEntities.isEmpty()) return displayEntities

        val scaffoldBlockMap = scaffoldShape.blocks.associateBy { it.position }

        return displayEntities.map { entity ->
            if (entity.entityType == DisplayType.BLOCK && entity.blockData != null) {
                val mat = entity.blockData.material
                if (mat == Material.STRUCTURE_VOID || mat == Material.BARRIER) {
                    val scaffoldEntry = scaffoldBlockMap[entity.position]
                    if (scaffoldEntry != null) {
                        entity.copy(blockData = scaffoldEntry.blockData.clone())
                    } else {
                        entity
                    }
                } else {
                    entity
                }
            } else {
                entity
            }
        }
    }

    /** 根据 yml 中的 display_entities 覆写覆盖录制时录入的展示实体字段。 */
    private fun applyDisplayOverrides(
        entities: List<DisplayEntityData>,
        overrides: Map<Vector3i, top.mc506lw.monolith.core.io.BlueprintConfig.DisplayOverride>
    ): List<DisplayEntityData> {
        if (overrides.isEmpty()) return entities
        return entities.map { entity ->
            val ov = overrides[entity.position] ?: return@map entity
            var newType = entity.entityType
            ov.type?.let { newType = if (it.lowercase() == "item") DisplayType.ITEM else DisplayType.BLOCK }

            var newBlockData = entity.blockData
            if (newType == DisplayType.BLOCK && ov.block != null) {
                try {
                    newBlockData = Bukkit.createBlockData(ov.block)
                } catch (e: Exception) {
                    log.warn("compile", "展示实体 block 覆写解析失败", "pos" to entity.position, "block" to ov.block, "err" to e.message)
                }
            }
            var newItem = entity.itemStack
            if (newType == DisplayType.ITEM && ov.item != null) {
                try {
                    newItem = org.bukkit.inventory.ItemStack(Material.valueOf(ov.item.uppercase()))
                } catch (e: Exception) {
                    log.warn("compile", "展示实体 item 覆写解析失败", "pos" to entity.position, "item" to ov.item, "err" to e.message)
                }
            }
            entity.copy(
                entityType = newType,
                blockData = newBlockData,
                itemStack = newItem,
                translation = ov.translation ?: entity.translation,
                rotation = ov.rotation ?: entity.rotation,
                scale = ov.scale ?: entity.scale,
                group = ov.group ?: entity.group
            )
        }
    }

    private fun applyScaffoldMaterial(
        blockEntry: BlockEntry,
        scaffoldMaterialMap: Map<Material, Material>
    ): BlockEntry {
        if (scaffoldMaterialMap.isEmpty()) return blockEntry

        val material = blockEntry.blockData.material
        val scaffoldMaterial = scaffoldMaterialMap[material] ?: return blockEntry

        val newBlockData = try {
            Bukkit.createBlockData(scaffoldMaterial)
        } catch (_: Exception) {
            return blockEntry
        }

        return BlockEntry(
            position = blockEntry.position,
            blockData = newBlockData
        )
    }

    private fun applyOverrideToScaffold(
        blockEntry: BlockEntry,
        override: BlueprintConfig.OverrideEntry,
        scaffoldMaterialMap: Map<Material, Material>
    ): BlockEntry {
        val scaffoldEntry = applyScaffoldMaterial(blockEntry, scaffoldMaterialMap)

        return when (override.type.lowercase()) {
            "strict", "material" -> {
                val mat = override.material ?: return scaffoldEntry
                val newBlockData = try {
                    Bukkit.createBlockData(mat)
                } catch (_: Exception) {
                    return scaffoldEntry
                }
                BlockEntry(position = scaffoldEntry.position, blockData = newBlockData)
            }
            "loose" -> {
                scaffoldEntry
            }
            "rebar" -> {
                scaffoldEntry
            }
            else -> scaffoldEntry
        }
    }

    private fun applyOverrideToAssembled(
        blockEntry: BlockEntry,
        override: BlueprintConfig.OverrideEntry
    ): BlockEntry {
        return when (override.type.lowercase()) {
            "strict", "material" -> {
                val mat = override.material ?: return blockEntry
                val newBlockData = try {
                    Bukkit.createBlockData(mat)
                } catch (_: Exception) {
                    return blockEntry
                }
                BlockEntry(position = blockEntry.position, blockData = newBlockData)
            }
            "loose" -> {
                blockEntry
            }
            "rebar" -> {
                blockEntry
            }
            else -> blockEntry
        }
    }
}
