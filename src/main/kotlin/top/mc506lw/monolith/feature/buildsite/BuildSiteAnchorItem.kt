package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import io.github.pylonmc.rebar.item.interfaces.BlockInteractRebarItemHandler
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.monolith.MonolithLib

/**
 * 工地展位物品 — 玩家手持此物品右键方块可创建工地。
 *
 * 实际放置逻辑由 [BuildSiteListener] 处理（预览 → 确认 → 放置）。
 * 此类只是标记物品类型，用于 [BuildSiteListener.readAnchorItemBlueprintId] 的类型检查。
 */
class BuildSiteAnchorItem(stack: ItemStack) : RebarItem(stack), BlockInteractRebarItemHandler {

    /**
     * 方块交互由 [BuildSiteListener.onPlayerInteract] 统一处理，
     * 此处不做任何操作以避免冲突。
     */
    override fun onInteractWithBlock(event: PlayerInteractEvent, priority: EventPriority) {
        // 不做任何操作，请 BuildSiteListener 处理
    }

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "build_site_anchor")

        /** PDC key：蓝图 ID。物品契约见 [BuildSiteListener.readAnchorItemBlueprintId]。 */
        @JvmField
        val BLUEPRINT_ID_KEY: NamespacedKey = NamespacedKey(MonolithLib.instance, "blueprint_id")

        /** 为指定蓝图创建展位物品。 */
        fun createItem(blueprintId: String): ItemStack {
            val item = ItemStackBuilder.rebar(BuildSiteAnchorBlock.MATERIAL, KEY)
                .name(top.mc506lw.monolith.common.I18n.translatable(
                    "item.build_site_anchor.name", "blueprint_id" to blueprintId
                ))
                .build()

            val meta = item.itemMeta ?: return item
            meta.persistentDataContainer.set(
                BLUEPRINT_ID_KEY,
                PersistentDataType.STRING,
                blueprintId
            )
            item.itemMeta = meta
            return item
        }

        /**
         * 基于任意 [RebarItem] 生成锚点物品，避免依赖固定材质。
         * 生成的物品会设置蓝图 ID 的 PDC，行为与 [createItem] 一致。
         */
        fun asAnchor(baseStack: ItemStack, blueprintId: String): ItemStack {
            val copy = baseStack.clone()
            val meta = copy.itemMeta ?: return copy
            meta.persistentDataContainer.set(
                BLUEPRINT_ID_KEY,
                PersistentDataType.STRING,
                blueprintId
            )
            copy.itemMeta = meta
            return copy
        }
    }
}
