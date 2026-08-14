package top.mc506lw.monolith.validation.predicate

import org.bukkit.Material
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.transform.BlockStateRotator

class RotatedPredicate(
    private val originalPredicate: Predicate,
    private val rotationSteps: Int
) : Predicate {

    val original: Predicate get() = originalPredicate

    // lazy：该属性仅在真正读取预览方块时才计算，避免每次构造都做
    // rotate + createBlockData（百万级构造时曾占 13%+ 的 CPU）
    override val previewBlockData: BlockData by lazy {
        BlockStateRotator.rotate(
            originalPredicate.previewBlockData ?: FALLBACK_PREVIEW,
            rotationSteps
        )
    }

    override val hint: String? = originalPredicate.hint

    override fun test(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        val inverseRotation = (4 - rotationSteps) % 4
        val rotatedBlockData = BlockStateRotator.rotate(blockData, inverseRotation)

        return originalPredicate.test(rotatedBlockData, context)
    }

    override fun testMaterialOnly(blockData: BlockData, context: Predicate.PredicateContext): Boolean {
        return originalPredicate.testMaterialOnly(blockData, context)
    }

    companion object {
        private val FALLBACK_PREVIEW: BlockData = Material.STONE.createBlockData()
    }
}
