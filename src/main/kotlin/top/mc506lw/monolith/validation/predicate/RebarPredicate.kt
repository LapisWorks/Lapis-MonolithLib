package top.mc506lw.monolith.validation.predicate

import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.feature.rebar.RebarAdapter

class RebarPredicate(
    private val rebarKey: NamespacedKey,
    override val previewBlockData: BlockData
) : Predicate {
    
    override val hint: String? = "Rebar: ${rebarKey.namespace}:${rebarKey.key}"
    
    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        val block: Block? = context.block
        
        return block != null && RebarAdapter.isRebarBlock(block, rebarKey)
    }
    
    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return test(blockData, context)
    }
    
    val key: NamespacedKey get() = rebarKey
    
    override fun toString(): String = "RebarPredicate(key=$rebarKey)"
}

/** 穿透 [RotatedPredicate] 等包装，取出底层要求的 Rebar key（无则 null）。 */
fun Predicate.rebarKeyOfPredicate(): NamespacedKey? = when (this) {
    is RebarPredicate -> key
    is RotatedPredicate -> original.rebarKeyOfPredicate()
    else -> null
}
