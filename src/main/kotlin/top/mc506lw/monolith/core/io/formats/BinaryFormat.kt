package top.mc506lw.monolith.core.io.formats

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.core.io.StructureSerializer
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.BlockEntry
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.BlueprintMeta
import top.mc506lw.monolith.core.model.BuildStage
import top.mc506lw.monolith.core.model.DisplayEntityData
import top.mc506lw.monolith.core.model.DisplayType
import top.mc506lw.monolith.core.model.BoundingBox
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.validation.predicate.*
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.*
import java.util.logging.Level

object BinaryFormat : StructureSerializer {

    private const val MAGIC: Int = 0x4D4E4231
    private const val CURRENT_VERSION = 6

    private val log = MonolithLogger.getLogger("BinaryFmt")
    
    override val formatName: String = "Monolith Binary"
    override val fileExtension: String = ".mnb"
    
    fun save(blueprint: Blueprint, file: File, formatVersion: Int, configHash: String) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            serialize(blueprint, output, formatVersion, configHash)
        }
    }
    
    fun load(file: File): Blueprint? {
        return try {
            file.inputStream().use { input ->
                deserialize(input)
            }
        } catch (e: Exception) {
            Bukkit.getLogger().log(Level.WARNING, "[MonolithLib] BinaryFormat 加载失败: ${file.absolutePath}", e)
            null
        }
    }
    
    fun serialize(blueprint: Blueprint, output: OutputStream, formatVersion: Int = CURRENT_VERSION, configHash: String = "") {
        DataOutputStream(BufferedOutputStream(output)).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(CURRENT_VERSION)
            dos.writeInt(formatVersion)
            
            writeString(dos, configHash)
            
            writeString(dos, blueprint.id)
            
            val scaffoldShape = blueprint.scaffoldShape
            val assembledShape = blueprint.assembledShape
            val hasDualStage = scaffoldShape !== assembledShape && 
                scaffoldShape.blocks != assembledShape.blocks
            
            dos.writeBoolean(hasDualStage)
            
            writeShape(dos, assembledShape)
            
            if (hasDualStage) {
                writeShape(dos, scaffoldShape)
            }
            
            val centerOffset = blueprint.meta.controllerOffset
            dos.writeShort(centerOffset.x)
            dos.writeShort(centerOffset.y)
            dos.writeShort(centerOffset.z)
            
            val controllerKey = blueprint.controllerRebarKey
            dos.writeBoolean(controllerKey != null)
            if (controllerKey != null) {
                writeString(dos, controllerKey.toString())
            }
            
            writeString(dos, blueprint.meta.displayName)
            writeString(dos, blueprint.meta.description)
            writeString(dos, blueprint.meta.author)

            val displayOffset = blueprint.meta.displayOffset
            dos.writeFloat(displayOffset.x)
            dos.writeFloat(displayOffset.y)
            dos.writeFloat(displayOffset.z)
            
            dos.writeInt(blueprint.slots.size)
            for ((slotId, pos) in blueprint.slots) {
                writeString(dos, slotId)
                dos.writeShort(pos.x)
                dos.writeShort(pos.y)
                dos.writeShort(pos.z)
            }
            
            dos.writeInt(blueprint.customData.size)
            for ((key, value) in blueprint.customData) {
                writeString(dos, key)
                writeValue(dos, value)
            }
            
            writeDisplayEntities(dos, blueprint.displayEntities)
        }
    }
    
    override fun serialize(blueprint: Blueprint, output: OutputStream) {
        serialize(blueprint, output, CURRENT_VERSION, "")
    }
    
    override fun deserialize(input: InputStream): Blueprint? {
        return try {
            DataInputStream(BufferedInputStream(input)).use { dis ->
                deserializeFromStream(dis)
            }
        } catch (e: Exception) {
            Bukkit.getLogger().log(Level.WARNING, "[MonolithLib] BinaryFormat 反序列化失败", e)
            null
        }
    }
    
    private fun deserializeFromStream(dis: DataInputStream): Blueprint {
        val magic = dis.readInt()
        require(magic == MAGIC) { "无效的 Monolith Binary 文件" }
        
        val version = dis.readInt()
        require(version <= CURRENT_VERSION) { "不支持的版本: $version" }
        
        return if (version >= 4) {
            deserializeV4(dis, version)
        } else {
            deserializeLegacy(dis, version)
        }
    }
    
    private fun deserializeV4(dis: DataInputStream, version: Int): Blueprint {
        val formatVersion = dis.readInt()
        val configHash = readString(dis)
        
        val id = readString(dis)
        
        val hasDualStage = dis.readBoolean()
        
        val assembledShape = readShape(dis)
        
        val scaffoldShape = if (hasDualStage) {
            readShape(dis)
        } else {
            assembledShape
        }
        
        val centerX = dis.readShort().toInt()
        val centerY = dis.readShort().toInt()
        val centerZ = dis.readShort().toInt()
        
        val controllerRebarKey = if (dis.readBoolean()) {
            parseNamespacedKey(readString(dis))
        } else null
        
        val displayName = readString(dis)
        val description = readString(dis)
        val author = if (version >= 5) readString(dis) else ""

        val displayOffset = if (version >= 6) {
            Vector3f(dis.readFloat(), dis.readFloat(), dis.readFloat())
        } else {
            Vector3f(dis.readShort().toFloat(), dis.readShort().toFloat(), dis.readShort().toFloat())
        }

        val meta = BlueprintMeta(
            displayName = displayName,
            description = description,
            author = author,
            controllerOffset = Vector3i(centerX, centerY, centerZ),
            displayOffset = displayOffset
        )
        
        val slots = mutableMapOf<String, Vector3i>()
        val slotCount = dis.readInt()
        repeat(slotCount) {
            val slotId = readString(dis)
            val x = dis.readShort().toInt()
            val y = dis.readShort().toInt()
            val z = dis.readShort().toInt()
            slots[slotId] = Vector3i(x, y, z)
        }
        
        val customData = mutableMapOf<String, Any>()
        val dataCount = dis.readInt()
        repeat(dataCount) {
            val key = readString(dis)
            val value = readValue(dis)
            customData[key] = value
        }
        
        val displayEntities = readDisplayEntities(dis, version)
        
        val stages = if (hasDualStage) {
            mapOf(BuildStage.SCAFFOLD to scaffoldShape, BuildStage.ASSEMBLED to assembledShape)
        } else {
            mapOf(BuildStage.SCAFFOLD to assembledShape, BuildStage.ASSEMBLED to assembledShape)
        }
        
        return Blueprint(
            id = id,
            stages = stages,
            meta = meta,
            displayEntities = displayEntities,
            slots = slots.toMap(),
            customData = customData.toMap(),
            controllerRebarKey = controllerRebarKey
        )
    }
    
    @Suppress("DEPRECATION")
    private fun deserializeLegacy(dis: DataInputStream, version: Int): Blueprint {
        val formatVersion = if (version >= 3) dis.readInt() else 1
        val configHash = if (version >= 3) readString(dis) else ""
        
        val id = readString(dis)
        val sizeX = dis.readUnsignedShort()
        val sizeY = dis.readUnsignedShort()
        val sizeZ = dis.readUnsignedShort()
        
        val centerX = dis.readShort().toInt()
        val centerY = dis.readShort().toInt()
        val centerZ = dis.readShort().toInt()
        
        val controllerRebarKey = if (version >= 3 && dis.readBoolean()) {
            parseNamespacedKey(readString(dis))
        } else null
        
        val meta = if (version >= 3) {
            BlueprintMeta(
                displayName = readString(dis),
                description = readString(dis),
                controllerOffset = Vector3i(centerX, centerY, centerZ)
            ).also {
                @Suppress("UNUSED_EXPRESSION")
                if (version >= 3) { readString(dis) }
                if (version >= 3) { readString(dis) }
            }
        } else BlueprintMeta(controllerOffset = Vector3i(centerX, centerY, centerZ))
        
        val slots = mutableMapOf<String, Vector3i>()
        if (version >= 3) {
            val slotCount = dis.readInt()
            repeat(slotCount) {
                val slotId = readString(dis)
                val x = dis.readShort().toInt()
                val y = dis.readShort().toInt()
                val z = dis.readShort().toInt()
                slots[slotId] = Vector3i(x, y, z)
            }
        }
        
        val customData = mutableMapOf<String, Any>()
        if (version >= 3) {
            val dataCount = dis.readInt()
            repeat(dataCount) {
                val key = readString(dis)
                val value = readValue(dis)
                customData[key] = value
            }
        }
        
        val blockCount = dis.readInt()
        log.debug("load", "BinaryFormat诊断(legacy)", "version" to version, "blockCount" to blockCount, "id" to id)
        val blocks = readBlockEntries(dis, blockCount, version)
        
        val shape = Shape(blocks)
        
        return Blueprint(
            id = id,
            stages = mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape),
            meta = meta,
            slots = slots.toMap(),
            customData = customData.toMap(),
            controllerRebarKey = controllerRebarKey
        )
    }
    
    private fun readBlockEntries(dis: DataInputStream, blockCount: Int, version: Int): List<BlockEntry> {
        val blocks = mutableListOf<BlockEntry>()
        var skippedBlocks = 0
        
        repeat(blockCount) { blockIndex ->
            try {
                val x = dis.readShort().toInt()
                val y = dis.readShort().toInt()
                val z = dis.readShort().toInt()
                
                val predicate = if (version >= 3) {
                    readPredicate(dis)
                } else {
                    val blockDataStr = readString(dis)
                    if (blockDataStr.isNotEmpty()) {
                        try { 
                            val bd = Bukkit.createBlockData(blockDataStr)
                            StrictPredicate(bd)
                        } catch (_: Exception) { null }
                    } else null
                }
                
                val blockDataStr = readString(dis)
                val blockData = if (blockDataStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(blockDataStr) } catch (_: Exception) { null }
                } else predicate?.previewBlockData
                
                if (blockData != null) {
                    val effectivePredicate = if (predicate is RebarPredicate) predicate else null
                    blocks.add(BlockEntry(
                        position = Vector3i(x, y, z),
                        blockData = blockData,
                        predicate = effectivePredicate
                    ))
                } else {
                    skippedBlocks++
                }
            } catch (_: EOFException) {
                skippedBlocks++
            } catch (_: Exception) {
                skippedBlocks++
            }
        }
        
        if (skippedBlocks > 0) {
            log.warn("load", "跳过损坏的方块数据", "count" to skippedBlocks, "version" to version)
        }
        
        return blocks
    }
    
    private fun writeShape(dos: DataOutputStream, shape: Shape) {
        dos.writeShort(shape.boundingBox.width)
        dos.writeShort(shape.boundingBox.height)
        dos.writeShort(shape.boundingBox.depth)
        
        dos.writeInt(shape.blocks.size)
        
        for (blockEntry in shape.blocks) {
            dos.writeShort(blockEntry.position.x)
            dos.writeShort(blockEntry.position.y)
            dos.writeShort(blockEntry.position.z)
            
            writePredicate(dos, blockEntry.effectivePredicate)
            
            writeString(dos, blockEntry.blockData.asString)
        }
    }
    
    private fun readShape(dis: DataInputStream): Shape {
        val sizeX = dis.readUnsignedShort()
        val sizeY = dis.readUnsignedShort()
        val sizeZ = dis.readUnsignedShort()
        
        val blockCount = dis.readInt()
        val blocks = readBlockEntries(dis, blockCount, Int.MAX_VALUE)
        
        // 保留文件记录的选区完整体积（含空气），而非按非空气方块重新计算
        return Shape(
            blocks,
            BoundingBox(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1)
        )
    }
    
    private fun writeDisplayEntities(dos: DataOutputStream, entities: List<DisplayEntityData>) {
        dos.writeInt(entities.size)
        for (entity in entities) {
            dos.writeShort(entity.position.x)
            dos.writeShort(entity.position.y)
            dos.writeShort(entity.position.z)
            
            dos.writeByte(when (entity.entityType) {
                DisplayType.BLOCK -> 0
                DisplayType.ITEM -> 1
            })
            
            writeQuaternionf(dos, entity.rotation)
            writeVector3f(dos, entity.scale)
            writeVector3f(dos, entity.translation)
            writeString(dos, entity.group)
            
            val hasBlockData = entity.blockData != null
            dos.writeBoolean(hasBlockData)
            if (hasBlockData) {
                writeString(dos, entity.blockData.asString)
            }
            
            val hasItemStack = entity.itemStack != null
            dos.writeBoolean(hasItemStack)
            if (hasItemStack) {
                writeItemStack(dos, entity.itemStack)
            }
        }
    }
    
    private fun readDisplayEntities(dis: DataInputStream, version: Int): List<DisplayEntityData> {
        val count = dis.readInt()
        val entities = mutableListOf<DisplayEntityData>()
        
        repeat(count) {
            try {
                val x = dis.readShort().toInt()
                val y = dis.readShort().toInt()
                val z = dis.readShort().toInt()
                
                val typeByte = dis.readUnsignedByte()
                val entityType = when (typeByte) {
                    0 -> DisplayType.BLOCK
                    else -> DisplayType.ITEM
                }
                
                val rotation = readQuaternionf(dis)
                val scale = readVector3f(dis)
                val translation = readVector3f(dis)
                
                val group = if (version >= 5) readString(dis) else "default"

                val blockData = if (dis.readBoolean()) {
                    val str = readString(dis)
                    try { Bukkit.createBlockData(str) } catch (_: Exception) { null }
                } else null
                
                val itemStack = if (dis.readBoolean()) {
                    readItemStack(dis)
                } else null
                
                entities.add(DisplayEntityData(
                    position = Vector3i(x, y, z),
                    entityType = entityType,
                    rotation = rotation,
                    scale = scale,
                    translation = translation,
                    blockData = blockData,
                    itemStack = itemStack,
                    group = group
                ))
            } catch (_: Exception) {
                log.warn("load", "读取显示实体失败，跳过")
            }
        }
        
        return entities
    }
    
    private fun writeQuaternionf(dos: DataOutputStream, q: Quaternionf) {
        dos.writeFloat(q.x)
        dos.writeFloat(q.y)
        dos.writeFloat(q.z)
        dos.writeFloat(q.w)
    }
    
    private fun readQuaternionf(dis: DataInputStream): Quaternionf {
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        val w = dis.readFloat()
        return Quaternionf(x, y, z, w)
    }
    
    private fun writeVector3f(dos: DataOutputStream, v: Vector3f) {
        dos.writeFloat(v.x)
        dos.writeFloat(v.y)
        dos.writeFloat(v.z)
    }
    
    private fun readVector3f(dis: DataInputStream): Vector3f {
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        return Vector3f(x, y, z)
    }
    
    private fun writeItemStack(dos: DataOutputStream, item: ItemStack) {
        val bytes = item.serializeAsBytes()
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }
    
    private fun readItemStack(dis: DataInputStream): ItemStack {
        val length = dis.readInt()
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        return ItemStack.deserializeBytes(bytes)
    }
    
    private fun writePredicate(dos: DataOutputStream, predicate: Predicate) {
        when (predicate) {
            is AirPredicate -> {
                dos.writeByte(0)
            }
            is AnyPredicate -> {
                dos.writeByte(1)
            }
            is StrictPredicate -> {
                dos.writeByte(2)
                writeString(dos, predicate.previewBlockData?.asString ?: "")
            }
            is LoosePredicate -> {
                dos.writeByte(3)
                writeString(dos, predicate.previewBlockData?.asString ?: "")
                val ignoredStates = predicate.ignoredStates
                dos.writeInt(ignoredStates.size)
                ignoredStates.forEach { writeString(dos, it) }
            }
            is RebarPredicate -> {
                dos.writeByte(4)
                writeString(dos, predicate.key.toString())
                writeString(dos, predicate.previewBlockData?.asString ?: "")
            }
            else -> {
                dos.writeByte(5)
                writeString(dos, predicate.previewBlockData?.asString ?: "")
                writeString(dos, predicate.hint ?: "")
            }
        }
    }
    
    private fun readPredicate(dis: DataInputStream): Predicate {
        val type = dis.readUnsignedByte()
        return when (type) {
            0 -> AirPredicate
            1 -> AnyPredicate
            2 -> {
                val blockDataStr = readString(dis)
                val blockData = if (blockDataStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(blockDataStr) } catch (e: Exception) { null }
                } else null
                if (blockData != null) StrictPredicate(blockData) else AirPredicate
            }
            3 -> {
                val previewStr = readString(dis)
                val preview = if (previewStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(previewStr) } catch (e: Exception) { null }
                } else null
                val ignoredCount = dis.readInt()
                val ignoredStates = mutableSetOf<String>()
                repeat(ignoredCount) {
                    ignoredStates.add(readString(dis))
                }
                if (preview != null) LoosePredicate(preview, ignoredStates) else AirPredicate
            }
            4 -> {
                val keyStr = readString(dis)
                val previewStr = readString(dis)
                val key = parseNamespacedKey(keyStr)
                val preview = if (previewStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(previewStr) } catch (e: Exception) { null }
                } else null
                if (preview != null) RebarPredicate(key, preview) else AirPredicate
            }
            else -> {
                val previewStr = readString(dis)
                val hint = readString(dis)
                val preview = if (previewStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(previewStr) } catch (e: Exception) { null }
                } else null
                if (preview != null) StrictPredicate(preview) else AirPredicate
            }
        }
    }
    
    private fun writeValue(dos: DataOutputStream, value: Any) {
        when (value) {
            is String -> {
                dos.writeByte(0)
                writeString(dos, value)
            }
            is Int -> {
                dos.writeByte(1)
                dos.writeInt(value)
            }
            is Double -> {
                dos.writeByte(2)
                dos.writeDouble(value)
            }
            is Boolean -> {
                dos.writeByte(3)
                dos.writeBoolean(value)
            }
            is List<*> -> {
                dos.writeByte(4)
                dos.writeInt(value.size)
                for (item in value) {
                    if (item != null) writeValue(dos, item)
                }
            }
            is Map<*, *> -> {
                dos.writeByte(5)
                dos.writeInt(value.size)
                @Suppress("UNCHECKED_CAST")
                for ((k, v) in value as Map<String, Any>) {
                    writeString(dos, k)
                    writeValue(dos, v)
                }
            }
            else -> {
                dos.writeByte(6)
                writeString(dos, value.toString())
            }
        }
    }
    
    private fun readValue(dis: DataInputStream): Any {
        return when (val type = dis.readUnsignedByte()) {
            0 -> readString(dis)
            1 -> dis.readInt()
            2 -> dis.readDouble()
            3 -> dis.readBoolean()
            4 -> {
                val count = dis.readInt()
                val list = mutableListOf<Any>()
                repeat(count) {
                    list.add(readValue(dis))
                }
                list
            }
            5 -> {
                val count = dis.readInt()
                val map = mutableMapOf<String, Any>()
                repeat(count) {
                    val key = readString(dis)
                    val value = readValue(dis)
                    map[key] = value
                }
                map
            }
            else -> readString(dis)
        }
    }
    
    private fun writeString(dos: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        dos.writeShort(bytes.size)
        dos.write(bytes)
    }
    
    private fun readString(dis: DataInputStream): String {
        val length = dis.readUnsignedShort()
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    
    private fun parseNamespacedKey(key: String): NamespacedKey {
        val parts = key.split(":")
        return when {
            parts.size == 2 -> NamespacedKey(parts[0], parts[1])
            parts.size == 1 -> NamespacedKey("minecraft", parts[0])
            else -> NamespacedKey("minecraft", key)
        }
    }
}
