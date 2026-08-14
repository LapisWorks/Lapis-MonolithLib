package top.mc506lw.monolith.validation.predicate

import org.bukkit.Material
import org.bukkit.block.data.BlockData

class MaterialPredicate(
    private val targetMaterial: Material
) : Predicate {

    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return blockData.material == targetMaterial
    }

    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return blockData.material == targetMaterial
    }

    override val previewBlockData: BlockData? = targetMaterial.createBlockData()
    override val hint: String? = "Material: ${targetMaterial.name}"

    companion object {
        /** 按材质共享实例：百万级 ghost 数据里同材质条目复用同一个 predicate，避免逐条构造。 */
        private val CACHE = java.util.concurrent.ConcurrentHashMap<Material, MaterialPredicate>()

        fun of(material: Material): MaterialPredicate =
            CACHE.computeIfAbsent(material) { MaterialPredicate(it) }
    }
}
