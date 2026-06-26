package top.mc506lw.monolith.feature.machine

import io.github.pylonmc.rebar.block.RebarBlock
import io.github.pylonmc.rebar.item.RebarItem
import io.github.pylonmc.rebar.item.builder.ItemStackBuilder
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapelessRecipe
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.MonolithLib

object BlueprintTableMachine {

    private val logger = MonolithLogger.getLogger("Machine")

    fun registerAll() {
        registerBlockWithItem(BlueprintTableBlock.KEY, BlueprintTableBlock.MATERIAL, BlueprintTableBlock::class.java)
        registerCraftingRecipe()

        logger.info { "Blueprint table machine registered" }
    }

    private fun registerBlockWithItem(key: NamespacedKey, material: Material, blockClass: Class<out RebarBlock>) {
        RebarBlock.register(key, material, blockClass)

        val itemStack = ItemStackBuilder.rebar(material, key).build()
        RebarItem.register(RebarItem::class.java, itemStack, key)
    }

    private fun registerCraftingRecipe() {
        val recipeKey = NamespacedKey(MonolithLib.instance, "blueprint_table_craft")
        val result = ItemStackBuilder.rebar(BlueprintTableBlock.MATERIAL, BlueprintTableBlock.KEY).build()

        val recipe = ShapelessRecipe(recipeKey, result)
        recipe.addIngredient(Material.LOOM)
        recipe.addIngredient(4, Material.PAPER)

        MonolithLib.instance.server.addRecipe(recipe)
    }
}
