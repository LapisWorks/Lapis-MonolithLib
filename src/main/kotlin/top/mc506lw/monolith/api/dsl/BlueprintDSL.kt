package top.mc506lw.monolith.api.dsl

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.model.BlueprintMeta
import top.mc506lw.monolith.core.model.BuildStage
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.core.model.BlockEntry as ModelBlockEntry

class BlueprintDSL(private val id: String) {
    private var sizeX: Int = 1
    private var sizeY: Int = 1
    private var sizeZ: Int = 1
    private var centerOffset: Vector3i = Vector3i.ZERO
    
    private val blocks = mutableListOf<BlockEntry>()
    private val slots = mutableMapOf<String, Vector3i>()
    private var metaName: String = id
    private var metaDescription: String = ""
    private var metaAuthor: String = ""
    private var metaVersion: String = "1.0"
    private var formStrategy: top.mc506lw.monolith.core.model.FormStrategy = top.mc506lw.monolith.core.model.FormStrategy.BlockOnly
    private var controllerMaterial: org.bukkit.Material = org.bukkit.Material.STRUCTURE_BLOCK
    
    private data class BlockEntry(val x: Int, val y: Int, val z: Int, val predicate: Predicate, val slotId: String? = null)
    
    fun size(x: Int, y: Int, z: Int) {
        this.sizeX = x
        this.sizeY = y
        this.sizeZ = z
    }
    
    fun center(x: Int, y: Int, z: Int) {
        this.centerOffset = Vector3i(x, y, z)
    }
    
    fun block(x: Int, y: Int, z: Int, predicate: Predicate) {
        blocks.add(BlockEntry(x, y, z, predicate))
    }
    
    fun block(x: Int, y: Int, z: Int, blockData: BlockData) {
        blocks.add(BlockEntry(x, y, z, Predicates.strict(blockData)))
    }
    
    fun block(x: Int, y: Int, z: Int, material: Material) {
        blocks.add(BlockEntry(x, y, z, Predicates.strict(Bukkit.createBlockData(material))))
    }
    
    fun block(x: Int, y: Int, z: Int, blockDataStr: String) {
        val blockData = Bukkit.createBlockData(blockDataStr)
        blocks.add(BlockEntry(x, y, z, Predicates.strict(blockData)))
    }
    
    fun loose(x: Int, y: Int, z: Int, material: Material, ignoredStates: Set<String> = emptySet()) {
        val blockData = Bukkit.createBlockData(material)
        blocks.add(BlockEntry(x, y, z, Predicates.loose(material = material, preview = blockData)))
    }
    
    fun loose(x: Int, y: Int, z: Int, blockData: BlockData, ignoredStates: Set<String> = emptySet()) {
        blocks.add(BlockEntry(x, y, z, Predicates.loose(material = blockData.material, preview = blockData)))
    }
    
    fun rebar(x: Int, y: Int, z: Int, key: String, preview: Material) {
        blocks.add(BlockEntry(x, y, z, Predicates.rebar(key, preview)))
    }
    
    fun rebar(x: Int, y: Int, z: Int, key: String, preview: BlockData) {
        blocks.add(BlockEntry(x, y, z, Predicates.rebar(key, preview)))
    }
    
    fun rebar(x: Int, y: Int, z: Int, key: NamespacedKey, preview: BlockData) {
        blocks.add(BlockEntry(x, y, z, Predicates.rebar(key, preview)))
    }
    
    fun air(x: Int, y: Int, z: Int) {
        blocks.add(BlockEntry(x, y, z, Predicates.air()))
    }
    
    fun any(x: Int, y: Int, z: Int) {
        blocks.add(BlockEntry(x, y, z, Predicates.any()))
    }
    
    fun slot(slotId: String, x: Int, y: Int, z: Int) {
        slots[slotId] = Vector3i(x, y, z)
    }
    
    fun slotBlock(slotId: String, x: Int, y: Int, z: Int, predicate: Predicate) {
        blocks.add(BlockEntry(x, y, z, predicate, slotId))
        slots[slotId] = Vector3i(x, y, z)
    }
    
    fun slotBlock(slotId: String, x: Int, y: Int, z: Int, blockData: BlockData) {
        blocks.add(BlockEntry(x, y, z, Predicates.strict(blockData), slotId))
        slots[slotId] = Vector3i(x, y, z)
    }
    
    fun slotRebar(slotId: String, x: Int, y: Int, z: Int, key: String, preview: Material) {
        blocks.add(BlockEntry(x, y, z, Predicates.rebar(key, preview), slotId))
        slots[slotId] = Vector3i(x, y, z)
    }
    
    fun formStrategy(strategy: top.mc506lw.monolith.core.model.FormStrategy) {
        this.formStrategy = strategy
    }

    fun controllerMaterial(material: org.bukkit.Material) {
        this.controllerMaterial = material
    }

    fun meta(name: String, description: String = "", author: String = "", version: String = "1.0") {
        this.metaName = name
        this.metaDescription = description
        this.metaAuthor = author
        this.metaVersion = version
    }
    
    internal fun build(): Blueprint {
        val shapeBlocks = blocks.map { entry ->
            ModelBlockEntry(
                position = Vector3i(entry.x, entry.y, entry.z),
                blockData = entry.predicate.previewBlockData ?: Bukkit.createBlockData(Material.STONE)
            )
        }
        
        val shape = Shape(shapeBlocks)
        
        val meta = BlueprintMeta(
            displayName = metaName,
            description = metaDescription,
            controllerOffset = centerOffset,
            author = metaAuthor
        )
        
        return Blueprint(
            id = id,
            stages = mapOf(BuildStage.SCAFFOLD to shape, BuildStage.ASSEMBLED to shape),
            meta = meta,
            formStrategy = formStrategy,
            controllerMaterial = controllerMaterial
        )
    }
}

fun buildBlueprint(id: String, init: BlueprintDSL.() -> Unit): Blueprint {
    return BlueprintDSL(id).apply(init).build()
}

fun org.bukkit.plugin.java.JavaPlugin.registerMonolithStructure(id: String, init: BlueprintDSL.() -> Unit): Blueprint {
    val blueprint = buildBlueprint(id, init)
    top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.register(blueprint)
    return blueprint
}
