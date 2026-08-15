package top.mc506lw.monolith.validation.predicate

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.datatypes.RebarSerializers
import io.github.pylonmc.rebar.registry.RebarRegistry
import io.github.pylonmc.rebar.util.position.BlockPosition
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.feature.rebar.RebarAdapter

class RebarPredicate(
    private val rebarKey: NamespacedKey,
    override val previewBlockData: BlockData
) : Predicate {
    
    override val hint: String? = "Rebar: ${rebarKey.namespace}:${rebarKey.key}"
    
    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        val block: Block? = context.block
        
        val ok = block != null && RebarAdapter.isRebarBlock(block, rebarKey)
        if (!ok && block != null && block.type == previewBlockData.material) {
            logYellowDiagnostic(block)
        }
        return ok
    }
    
    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return test(blockData, context)
    }
    
    val key: NamespacedKey get() = rebarKey
    
    override fun toString(): String = "RebarPredicate(key=$rebarKey)"
    
    /**
     * 黄块完整键诊断：对比"世界方块"与"正确方块"在 Rebar 各层的键信息，
     * 直接回答"我放的就是 relay_base 为什么黄"。
     */
    private fun logYellowDiagnostic(block: Block) {
        val now = System.currentTimeMillis()
        if (now - lastWarnTime < WARN_INTERVAL_MS) return
        lastWarnTime = now
        
        val logger = MonolithLogger.getLogger("RebarPredicate")
        
        // —— 世界方块 ——
        // 1) Rebar 内存注册表：是否在表、schema key
        val storageKey = try { BlockStorage.get(block)?.schema?.key } catch (e: Exception) { null }
        val inStorage = try { BlockStorage.isRebarBlock(block) } catch (e: Exception) { false }
        // 2) 方块自身 PDC 的所有键（Rebar 本体不写这里，但其它插件/兜底路径会写）
        val pdcKeys = try {
            (block.state as? org.bukkit.block.TileState)
                ?.persistentDataContainer?.keys?.map { it.toString() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        // 3) 区块 PDC 的 rebar 列表里该位置存的 key（磁盘层认为这里是什么）
        val chunkStored = try { readChunkStoredRebarKey(block) } catch (e: Exception) { "读取异常: ${e.message}" }
        
        // —— 正确方块 ——
        // 预期 key 是否真的注册在 Rebar、注册的材质是什么
        val expectedSchema = try { RebarRegistry.BLOCKS[rebarKey] } catch (e: Exception) { null }
        
        logger.debug(
            "黄块诊断",
            "材质匹配但 rebar 判定失败（完整键信息）",
            "pos" to "${block.x},${block.y},${block.z}",
            "chunkLoaded" to block.chunk.isLoaded,
            "world.material" to block.type.name,
            "world.storageKey" to storageKey,
            "world.inStorage" to inStorage,
            "world.pdcKeys" to pdcKeys,
            "world.chunkStoredKey" to chunkStored,
            "expected.key" to rebarKey.toString(),
            "expected.registered" to (expectedSchema != null),
            "expected.material" to expectedSchema?.material?.name
        )
    }
    
    /** 读区块 PDC 里 rebar 序列化列表中该位置的记录 key。 */
    private fun readChunkStoredRebarKey(block: Block): String {
        val list = block.chunk.persistentDataContainer
            .get(BlockStorage.rebarBlocksKey, BlockStorage.rebarBlocksType)
        if (list == null) return "区块无 rebar 记录"
        val target = BlockPosition.asLong(block.x, block.y, block.z)
        var count = 0
        for (element in list) {
            val pos = element.get(RebarBlock.rebarBlockPositionKey, RebarSerializers.LONG) ?: continue
            count++
            if (pos == target) {
                val key = element.get(RebarBlock.rebarBlockKeyKey, RebarSerializers.NAMESPACED_KEY)
                    ?: return "位置在列但 key 为空"
                return key.toString()
            }
        }
        return "列表中无此位置（共 $count 条）"
    }
    
    companion object {
        private const val WARN_INTERVAL_MS = 10_000L
        @Volatile private var lastWarnTime = 0L
    }
}

/** 穿透 [RotatedPredicate] 等包装，取出底层要求的 Rebar key（无则 null）。 */
fun Predicate.rebarKeyOfPredicate(): NamespacedKey? = when (this) {
    is RebarPredicate -> key
    is RotatedPredicate -> original.rebarKeyOfPredicate()
    else -> null
}
