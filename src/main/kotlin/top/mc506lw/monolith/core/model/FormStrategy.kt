package top.mc506lw.monolith.core.model

import top.mc506lw.monolith.core.math.Vector3i

/**
 * 多方块成型策略 — 决定当多方块形成时，世界中的方块应该呈现什么样子。
 *
 * ## 三种模式
 *
 * ### BlockOnly（默认）
 * 纯多方块结构。成型后就是世界中的真实方块本身，不做任何变换。
 * 适合：祭坛、建筑、简单机器。
 *
 * ### FullDisplay
 * 展示实体全覆盖。成型后所有"地基"方块被隐藏（替换为屏障/空气），
 * 替换为 BlockDisplay / ItemDisplay 实体。
 * 适合：传送门、全息特效、动态装饰。
 *
 * ### Hybrid
 * 混合模式。部分方块保留为真实方块，部分位置替换为展示实体。
 * [hiddenPositions] 中指定哪些相对位置要被隐藏并替换为 Display。
 * 适合：带屏幕的机器（外壳保留，屏幕位置用 Display）。
 */
sealed interface FormStrategy {

    /**
     * 纯方块结构，成型后无额外效果。
     */
    data object BlockOnly : FormStrategy

    /**
     * 展示实体全覆盖。
     * @param hideOriginalBlocks 是否隐藏原始方块（默认 true）
     */
    data class FullDisplay(
        val hideOriginalBlocks: Boolean = true
    ) : FormStrategy

    /**
     * 混合模式。
     * @param hiddenPositions 需要被隐藏并替换为 Display 的相对位置集合
     */
    data class Hybrid(
        val hiddenPositions: Set<Vector3i> = emptySet()
    ) : FormStrategy {
        init {
            require(hiddenPositions.isNotEmpty()) {
                "Hybrid 策略必须指定至少一个 hiddenPositions"
            }
        }
    }
}
