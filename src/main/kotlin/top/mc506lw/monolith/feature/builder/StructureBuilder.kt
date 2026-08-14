package top.mc506lw.monolith.feature.builder

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.validation.predicate.Predicate
import top.mc506lw.monolith.validation.predicate.Predicates
import top.mc506lw.monolith.validation.predicate.RotatedPredicate
import top.mc506lw.monolith.validation.predicate.rebarKeyOfPredicate
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib

class StructureBuilder(
    private val player: Player,
    private val blueprint: Blueprint,
    private val controllerLocation: Location,
    private val facing: Facing,
    private val isSurvival: Boolean
) {
    private val transform = CoordinateTransform(facing)
    private val rotationSteps = facing.rotationSteps
    
    private var isBuilding = false
    private var buildTask: BukkitRunnable? = null
    
    val blocksToPlace: List<BuildEntry> by lazy {
        val entries = mutableListOf<BuildEntry>()
        
        for (block in blueprint.assembledShape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = Vector3i(
                    controllerLocation.blockX,
                    controllerLocation.blockY,
                    controllerLocation.blockZ
                ),
                relativePos = block.position,
                centerOffset = blueprint.meta.controllerOffset
            )
            
            val originalPreview = block.blockData ?: Material.STONE.createBlockData()
            val rotatedBlockData = BlockStateRotator.rotate(originalPreview, rotationSteps)
            
            val predicate = block.effectivePredicate
            val rotatedPredicate = RotatedPredicate(predicate, rotationSteps)
            
            entries.add(BuildEntry(
                worldPos = worldPos,
                blockData = rotatedBlockData,
                material = rotatedBlockData.material,
                predicate = rotatedPredicate,
                rebarKey = predicate.rebarKeyOfPredicate()
            ))
        }
        
        entries.sortedWith(compareBy<BuildEntry> { it.worldPos.y }.thenBy { it.worldPos.x }.thenBy { it.worldPos.z })
    }
    
    fun canBuild(): BuildCheckResult {
        val world = controllerLocation.world ?: return BuildCheckResult.WORLD_UNLOADED
        
        val materialCounts = mutableMapOf<Material, Int>()
        val rebarKeyCounts = mutableMapOf<org.bukkit.NamespacedKey, Int>()
        val conflicts = mutableListOf<Vector3i>()
        
        for (entry in blocksToPlace) {
            val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
            
            if (!block.type.isAir && block.type != entry.material) {
                conflicts.add(entry.worldPos)
            }
            
            if (isSurvival && block.type.isAir) {
                if (entry.rebarKey != null) {
                    rebarKeyCounts[entry.rebarKey] = (rebarKeyCounts[entry.rebarKey] ?: 0) + 1
                } else {
                    materialCounts[entry.material] = (materialCounts[entry.material] ?: 0) + 1
                }
            }
        }
        
        if (conflicts.isNotEmpty()) {
            return BuildCheckResult.CONFLICTS(conflicts)
        }
        
        if (!isSurvival) {
            return BuildCheckResult.SUCCESS
        }
        
        val inventory = player.inventory
        val missing = mutableMapOf<Material, Int>()
        val missingRebar = mutableMapOf<org.bukkit.NamespacedKey, Int>()
        
        for ((material, required) in materialCounts) {
            var remaining = required
            
            for (item in inventory.contents) {
                if (item != null && item.type == material) {
                    val take = minOf(item.amount, remaining)
                    remaining -= take
                    if (remaining <= 0) break
                }
            }
            
            if (remaining > 0) {
                missing[material] = remaining
            }
        }
        
        for ((key, required) in rebarKeyCounts) {
            var remaining = required
            for (item in inventory.contents) {
                if (item != null && isRebarItem(item, key)) {
                    val take = minOf(item.amount, remaining)
                    remaining -= take
                    if (remaining <= 0) break
                }
            }
            if (remaining > 0) {
                missingRebar[key] = remaining
            }
        }
        
        return if (missing.isEmpty() && missingRebar.isEmpty()) {
            BuildCheckResult.SUCCESS
        } else {
            BuildCheckResult.MISSING_MATERIALS(missing, missingRebar)
        }
    }
    
    fun startBuild(): Boolean {
        if (isBuilding) return false
        
        val checkResult = canBuild()
        when (checkResult) {
            is BuildCheckResult.WORLD_UNLOADED -> {
                player.sendMessage(I18n.Message.Builder.errWorldUnloaded)
                return false
            }
            is BuildCheckResult.CONFLICTS -> {
                player.sendMessage(I18n.Message.Builder.errConflictingBlocks)
                player.sendMessage(I18n.Message.Builder.conflictPositions(checkResult.conflicts.take(5).joinToString { "(${it.x}, ${it.y}, ${it.z})" }))
                if (checkResult.conflicts.size > 5) {
                    player.sendMessage(I18n.Message.Builder.moreConflicts(checkResult.conflicts.size - 5))
                }
                return false
            }
            is BuildCheckResult.MISSING_MATERIALS -> {
                val missing = checkResult.missing
                player.sendMessage(I18n.Message.Builder.errInsufficientMaterials)
                missing.forEach { (material, count) ->
                    player.sendMessage(I18n.Message.Builder.missingMaterial(material.name, count))
                }
                checkResult.missingRebar.forEach { (key, count) ->
                    player.sendMessage(I18n.Message.Builder.missingMaterial("${key.namespace}:${key.key} (Rebar)", count))
                }
                return false
            }
            is BuildCheckResult.SUCCESS -> {
                // Continue
            }
        }
        
        isBuilding = true
        
        buildTask = object : BukkitRunnable() {
            private val iterator = blocksToPlace.iterator()
            private var placed = 0
            private val total = blocksToPlace.size
            private var currentY = -999
            
            override fun run() {
                if (!iterator.hasNext()) {
                    finishBuild()
                    cancel()
                    return
                }
                
                val world = controllerLocation.world
                if (world == null) {
                    player.sendMessage(I18n.Message.Builder.errWorldUnloadedCancelled)
                    cancelBuild()
                    return
                }
                
                var builtThisTick = 0
                val maxPerTick = 500
                
                while (iterator.hasNext() && builtThisTick < maxPerTick) {
                    val entry = iterator.next()
                    
                    if (entry.worldPos.y != currentY) {
                        currentY = entry.worldPos.y
                    }
                    
                    val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)
                    
                    if (block.type.isAir) {
                        if (entry.rebarKey != null) {
                            val placed = try {
                                io.github.pylonmc.rebar.block.BlockStorage.placeBlock(block, entry.rebarKey) != null
                            } catch (e: Exception) {
                                false
                            }
                            if (!placed) {
                                // Rebar 方块放置失败（如未注册），跳过
                                continue
                            }
                            if (isSurvival) consumeRebarItem(entry.rebarKey)
                        } else {
                            if (isSurvival) consumeMaterial(entry.material)
                            block.blockData = entry.blockData
                        }
                        
                        if (builtThisTick < 10) {
                            playBuildEffects(block.location)
                        }
                        
                        placed++
                        builtThisTick++
                    }
                }
                
                if (placed % 500 == 0 || placed == total) {
                    val progress = (placed * 100 / total)
                    player.sendMessage(I18n.Message.Builder.progress(placed, total, "$progress%"))
                }
            }
        }
        
        buildTask?.runTaskTimer(MonolithLib.instance, 1L, 1L)
        
        player.sendMessage(I18n.Message.Builder.startBuild(blueprint.id))
        player.sendMessage(I18n.Message.Builder.buildInfo(blocksToPlace.size))
        
        return true
    }
    
    fun cancelBuild() {
        buildTask?.cancel()
        isBuilding = false
        player.sendMessage(I18n.Message.Builder.cancelled)
    }
    
    private fun consumeMaterial(material: Material) {
        val inventory = player.inventory
        var remaining = 1
        
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type != material) continue
            
            val take = minOf(item.amount, remaining)
            item.amount -= take
            remaining -= take
            
            if (item.amount <= 0) {
                inventory.setItem(i, null)
            }
            
            if (remaining <= 0) break
        }
    }

    private fun consumeRebarItem(key: org.bukkit.NamespacedKey) {
        val inventory = player.inventory
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (!isRebarItem(item, key)) continue
            if (item.amount <= 1) inventory.setItem(i, null) else item.amount--
            return
        }
    }

    private fun isRebarItem(item: ItemStack, key: org.bukkit.NamespacedKey): Boolean = try {
        io.github.pylonmc.rebar.item.RebarItem.fromStack(item)?.key == key
    } catch (_: Exception) {
        false
    }
    
    private fun playBuildEffects(location: Location) {
        val world = location.world ?: return
        
        world.spawnParticle(
            Particle.BLOCK,
            location.clone().add(0.5, 0.5, 0.5),
            5,
            0.2, 0.2, 0.2,
            Material.STONE.createBlockData()
        )
    }
    
    private fun finishBuild() {
        isBuilding = false
        
        player.sendMessage(I18n.Message.Builder.complete)
        player.sendMessage(I18n.Message.Builder.structureInfo(blueprint.id))
        
        controllerLocation.world?.playSound(
            controllerLocation,
            Sound.ENTITY_PLAYER_LEVELUP,
            1.0f,
            1.0f
        )
    }
    
    data class BuildEntry(
        val worldPos: Vector3i,
        val blockData: BlockData,
        val material: Material,
        val predicate: Predicate,
        val rebarKey: org.bukkit.NamespacedKey? = null
    )
    
    sealed class BuildCheckResult {
        object SUCCESS : BuildCheckResult()
        object WORLD_UNLOADED : BuildCheckResult()
        data class CONFLICTS(val conflicts: List<Vector3i>) : BuildCheckResult()
        data class MISSING_MATERIALS(
            val missing: Map<Material, Int>,
            val missingRebar: Map<org.bukkit.NamespacedKey, Int> = emptyMap()
        ) : BuildCheckResult()
    }
}
