package top.mc506lw.monolith.feature.machine

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.block.interfaces.BlockBreakRebarBlockHandler
import io.github.pylonmc.rebar.block.interfaces.EntityHolderRebarBlock
import io.github.pylonmc.rebar.block.interfaces.GuiRebarBlock
import io.github.pylonmc.rebar.block.interfaces.VirtualInventoryRebarBlock
import io.github.pylonmc.rebar.block.context.BlockBreakContext
import io.github.pylonmc.rebar.block.context.BlockCreateContext
import io.github.pylonmc.rebar.entity.display.BlockDisplayBuilder
import io.github.pylonmc.rebar.entity.display.transform.TransformBuilder
import io.github.pylonmc.rebar.util.gui.GuiItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.MonolithLib
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.invui.inventory.VirtualInventory
import xyz.xenondevs.invui.inventory.event.UpdateReason

class BlueprintTableBlock(
    block: Block,
    context: BlockCreateContext
) : RebarBlock(block, context), EntityHolderRebarBlock, GuiRebarBlock, VirtualInventoryRebarBlock, BlockBreakRebarBlockHandler {

    constructor(block: Block, pdc: PersistentDataContainer) : this(block, BlockCreateContext.Default(block = block))

    override var disableBlockTextureEntity = true

    private val inputInventory = VirtualInventory(1)

    private lateinit var guiInstance: Gui

    private val dynamicItems = mutableListOf<AbstractItem>()

    override fun getVirtualInventories() = mapOf("input" to inputInventory)

    override val guiTitle: Component = Component.text("蓝图桌").color(NamedTextColor.GOLD)

    companion object {
        val KEY = NamespacedKey(MonolithLib.instance, "blueprint_table")
        val MATERIAL = Material.POLISHED_TUFF_WALL

        private const val DESKTOP_DISPLAY_NAME = "desktop"

        private val BLUEPRINT_SLOTS = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', 'a')
    }

    init {
        spawnDesktopDisplay()
    }

    override fun postLoad() {
        if (!isHeldEntityPresent(DESKTOP_DISPLAY_NAME)) {
            spawnDesktopDisplay()
        }
    }

    private fun spawnDesktopDisplay() {
        addEntity(DESKTOP_DISPLAY_NAME, BlockDisplayBuilder()
            .material(Material.DARK_OAK_PRESSURE_PLATE)
            .transformation(TransformBuilder()
                .translate(-0.6, 0.0, -0.5)
                .scale(1.2, 2.0, 1.0)
                .build()
            )
            .build(block.location.toCenterLocation().add(0.0, 0.5, 0.0))
        )
    }

    override fun createGui(): Gui {
        val blueprints = safeLoadBlueprints()
        dynamicItems.clear()

        guiInstance = Gui.builder()
            .setStructure(
                "# # # # # # # # #",
                "# i # 1 2 3 4 5 #",
                "# # # 6 7 8 9 a #",
                "# < b p > # # # #"
            )
            .addIngredient('#', GuiItems.background())
            .addIngredient('i', inputInventory)
            .apply {
                for (index in BLUEPRINT_SLOTS.indices) {
                    val slotItem = if (index < blueprints.size) {
                        blueprintSlot(blueprints[index])
                    } else {
                        Item.simple(emptySlot())
                    }
                    addIngredient(BLUEPRINT_SLOTS[index], slotItem)
                    if (slotItem is AbstractItem) dynamicItems.add(slotItem)
                }
            }
            .addIngredient('<', GuiItems.background())
            .addIngredient('>', GuiItems.background())
            .addIngredient('b', infoButton())
            .apply {
                val pItem = paperIndicator()
                addIngredient('p', pItem)
                dynamicItems.add(pItem)
            }
            .build()

        inputInventory.addPostUpdateHandler {
            refreshDynamicItems()
        }

        return guiInstance
    }

    private fun refreshDynamicItems() {
        for (item in dynamicItems) {
            item.notifyWindows()
        }
    }

    private fun safeLoadBlueprints(): List<Blueprint> {
        return try {
            MonolithAPI.getInstance().registry.getAll().values.toList()
        } catch (e: Exception) {
            MonolithLib.instance.logger.warning("[蓝图桌] 加载蓝图失败: ${e.message}")
            emptyList()
        }
    }

    private fun blueprintSlot(blueprint: Blueprint): AbstractItem {
        return object : AbstractItem() {
            override fun getItemProvider(player: Player): ItemProvider {
                val stack = if (checkPaper()) blueprintDisplayItem(blueprint) else lockedItem(blueprint)
                return Item.simple(stack).getItemProvider(player)
            }

            override fun handleClick(clickType: org.bukkit.event.inventory.ClickType, player: Player, click: Click) {
                if (checkPaper()) doCraft(player, blueprint)
            }
        }
    }

    private fun checkPaper(): Boolean {
        return try {
            val item = inputInventory.items[0]
            item != null && item.type == Material.PAPER && !item.isEmpty && item.amount >= 1
        } catch (e: Exception) {
            false
        }
    }

    private fun doCraft(player: Player, blueprint: Blueprint) {
        val paper = inputInventory.items[0]
        if (paper == null || paper.type != Material.PAPER || paper.amount < 1) return

        val newAmount = paper.amount - 1
        if (newAmount <= 0) {
            inputInventory.forceSetItem(UpdateReason.SUPPRESSED, 0, null)
        } else {
            val remaining = paper.clone()
            remaining.amount = newAmount
            inputInventory.forceSetItem(UpdateReason.SUPPRESSED, 0, remaining)
        }

        refreshDynamicItems()

        player.world.dropItemNaturally(player.location.add(0.0, 1.0, 0.0), blueprintResultItem(blueprint))
        player.sendMessage(Component.text("[蓝图桌] 成功制作出蓝图!").color(NamedTextColor.GREEN))
    }

    private fun blueprintDisplayItem(blueprint: Blueprint): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta: ItemMeta = item.itemMeta!!
        meta.displayName(Component.text(blueprint.meta.displayName.ifEmpty { blueprint.id }).color(NamedTextColor.AQUA))
        val lore = mutableListOf<Component>()
        lore.add(Component.empty())
        if (blueprint.meta.description.isNotEmpty()) {
            lore.add(Component.text(blueprint.meta.description).color(NamedTextColor.GRAY))
        }
        lore.add(Component.text("大小: ${blueprint.sizeX}x${blueprint.sizeY}x${blueprint.sizeZ}").color(NamedTextColor.YELLOW))
        lore.add(Component.text("方块数: ${blueprint.blockCount}").color(NamedTextColor.YELLOW))
        lore.add(Component.empty())
        lore.add(Component.text("点击制作").color(NamedTextColor.GREEN))
        meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun blueprintResultItem(blueprint: Blueprint): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta: ItemMeta = item.itemMeta!!
        meta.displayName(Component.text("${blueprint.meta.displayName.ifEmpty { blueprint.id }} 蓝图").color(NamedTextColor.AQUA))
        val lore = mutableListOf<Component>()
        lore.add(Component.empty())
        lore.add(Component.text("ID: ${blueprint.id}").color(NamedTextColor.DARK_AQUA))
        if (blueprint.meta.description.isNotEmpty()) {
            lore.add(Component.text(blueprint.meta.description).color(NamedTextColor.GRAY))
        }
        lore.add(Component.text("大小: ${blueprint.sizeX}x${blueprint.sizeY}x${blueprint.sizeZ}").color(NamedTextColor.YELLOW))
        lore.add(Component.empty())
        lore.add(Component.text("右键放置可创建工地").color(NamedTextColor.GREEN))
        meta.lore(lore)
        meta.persistentDataContainer.set(
            NamespacedKey(MonolithLib.instance, "blueprint_id"),
            PersistentDataType.STRING,
            blueprint.id
        )
        item.itemMeta = meta
        return item
    }

    private fun lockedItem(blueprint: Blueprint): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta: ItemMeta = item.itemMeta!!
        meta.displayName(Component.text("???").color(NamedTextColor.DARK_GRAY))
        meta.lore(listOf(Component.empty(), Component.text("需要放入纸张").color(NamedTextColor.RED)))
        item.itemMeta = meta
        return item
    }

    private fun emptySlot(): ItemStack {
        val item = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
        val meta: ItemMeta = item.itemMeta!!
        meta.displayName(Component.text("空槽位").color(NamedTextColor.DARK_GRAY))
        item.itemMeta = meta
        return item
    }

    private fun infoButton(): AbstractItem {
        return object : AbstractItem() {
            override fun getItemProvider(player: Player): ItemProvider {
                val item = ItemStack(Material.WRITABLE_BOOK)
                val meta: ItemMeta = item.itemMeta!!
                meta.displayName(Component.text("蓝图桌").color(NamedTextColor.GOLD))
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("放入纸张来制作蓝图").color(NamedTextColor.YELLOW),
                    Component.text("点击右侧选择要制作的蓝图").color(NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("蓝图可用于创建工地").color(NamedTextColor.AQUA)
                ))
                item.itemMeta = meta
                return Item.simple(item).getItemProvider(player)
            }
            override fun handleClick(clickType: org.bukkit.event.inventory.ClickType, player: Player, click: Click) {}
        }
    }

    private fun paperIndicator(): AbstractItem {
        return object : AbstractItem() {
            override fun getItemProvider(player: Player): ItemProvider {
                val has = checkPaper()
                val item = if (has) ItemStack(Material.PAPER) else ItemStack(Material.LIGHT_GRAY_DYE)
                val meta: ItemMeta = item.itemMeta!!
                if (has) {
                    meta.displayName(Component.text("已放入纸张").color(NamedTextColor.GREEN))
                    meta.lore(listOf(Component.text("数量: ${inputInventory.items[0]?.amount ?: 0}").color(NamedTextColor.WHITE)))
                } else {
                    meta.displayName(Component.text("需要纸张").color(NamedTextColor.RED))
                    meta.lore(listOf(Component.text("请放入纸张").color(NamedTextColor.GRAY)))
                }
                item.itemMeta = meta
                return Item.simple(item).getItemProvider(player)
            }
            override fun handleClick(clickType: org.bukkit.event.inventory.ClickType, player: Player, click: Click) {}
        }
    }

    override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext) {
        super<VirtualInventoryRebarBlock>.onBlockBreak(drops, context)
        tryRemoveAllEntities()
    }
}
