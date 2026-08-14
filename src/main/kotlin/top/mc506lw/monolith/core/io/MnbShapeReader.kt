package top.mc506lw.monolith.core.io

import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.model.BlockEntry
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.model.ShapeBuilder
import top.mc506lw.monolith.core.math.Vector3i
import java.io.*

object MnbShapeReader : ShapeReader {
    
    override val formatName: String = "Monolith Binary"
    override val supportedExtensions: Set<String> = setOf("mnb")
    
    private const val MAGIC: Int = 0x4D4E4231
    private const val CURRENT_VERSION = 4
    
    override fun read(file: File): Shape? {
        return try {
            file.inputStream().use { input ->
                deserialize(input)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override fun read(input: InputStream): Shape? {
        return try {
            deserialize(input)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun deserialize(input: InputStream): Shape {
        DataInputStream(BufferedInputStream(input)).use { dis ->
            val magic = dis.readInt()
            require(magic == MAGIC) { "无效的 Monolith Binary 文件" }
            
            val version = dis.readInt()
            require(version <= CURRENT_VERSION) { "不支持的版本: $version" }
            
            val blockCount = dis.readInt()
            val builder = ShapeBuilder()
            
            repeat(blockCount) {
                val x = dis.readShort().toInt()
                val y = dis.readShort().toInt()
                val z = dis.readShort().toInt()
                
                val blockDataStr = readString(dis)
                val blockData = if (blockDataStr.isNotEmpty()) {
                    try { Bukkit.createBlockData(blockDataStr) } catch (e: Exception) { null }
                } else null
                
                if (blockData != null) {
                    builder.addBlock(x, y, z, blockData)
                }
            }
            
            return builder.build()
        }
    }
    
    private fun readString(dis: DataInputStream): String {
        val length = dis.readInt()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
