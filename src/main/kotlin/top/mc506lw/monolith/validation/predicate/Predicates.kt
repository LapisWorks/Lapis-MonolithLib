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
    
    private fun parseNamespacedKey(key: String): NamespacedKey {
        val parts = key.split(":")
        return when {
            parts.size == 2 -> NamespacedKey(parts[0], parts[1])
            parts.size == 1 -> NamespacedKey("minecraft", parts[0])
            else -> NamespacedKey("minecraft", key)
        }
    }
}
