package top.mc506lw.monolith.validation.predicate

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData

object AirPredicate : Predicate {
    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return blockData.material.isAir
    }
    
    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return blockData.material.isAir
    }
    
    override val previewBlockData: BlockData? = null
    override val hint: String? = "空气"
}

object AnyPredicate : Predicate {
    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return !blockData.material.isAir
    }
    
    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return !blockData.material.isAir
    }
    
    override val previewBlockData: BlockData? = null
    override val hint: String? = "任意非空方块"
}

/**
 * 自由空间：任何方块都匹配（含空气、地形方块）。
 * 用于"脚手架是空气、成型后也是空气"的位置——结构融入地形时这些位置不参与铺设，
 * 成型/解体都不会触碰，因此建造阶段不应因地形方块而显示不匹配。
 */
object FreeSpacePredicate : Predicate {
    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean = true

    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean = true

    override val previewBlockData: BlockData? = null
    override val hint: String? = "自由空间（地形可保留）"
}

object Predicates {
    fun air(): Predicate = AirPredicate
    
    fun strict(blockData: BlockData, ignoredStates: Set<String> = emptySet()): Predicate {
        return StrictPredicate(blockData, ignoredStates)
    }
    
    fun loose(
        material: Material? = null,
        states: Map<String, String?> = emptyMap(),
        preview: BlockData? = null
    ): Predicate {
        return LoosePredicate(material = material, statePatterns = states, previewBlockData = preview)
    }
    
    fun loose(blockData: BlockData, ignoredStates: Set<String> = emptySet()): Predicate {
        return LoosePredicate(blockData, ignoredStates)
    }
    
    fun rebar(key: NamespacedKey, preview: BlockData): Predicate {
        return RebarPredicate(key, preview)
    }
    
    fun rebar(key: String, preview: BlockData): Predicate {
        val nsKey = parseNamespacedKey(key)
        return RebarPredicate(nsKey, preview)
    }
    
    fun rebar(key: String, previewMaterial: Material): Predicate {
        val nsKey = parseNamespacedKey(key)
        val preview = Bukkit.createBlockData(previewMaterial)
        return RebarPredicate(nsKey, preview)
    }
    
    fun material(material: Material): Predicate {
        return MaterialPredicate.of(material)
    }

    fun any(): Predicate = AnyPredicate

    fun freeSpace(): Predicate = FreeSpacePredicate
    
    private fun parseNamespacedKey(key: String): NamespacedKey {
        val parts = key.split(":")
        return when {
            parts.size == 2 -> NamespacedKey(parts[0], parts[1])
            parts.size == 1 -> NamespacedKey("minecraft", parts[0])
            else -> NamespacedKey("minecraft", key)
        }
    }
}
