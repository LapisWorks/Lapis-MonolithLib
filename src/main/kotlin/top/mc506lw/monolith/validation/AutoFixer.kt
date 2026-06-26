package top.mc506lw.monolith.validation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Shape
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.transform.BlockStateRotator
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import io.github.pylonmc.rebar.block.BlockStorage
import java.util.concurrent.CompletableFuture

object AutoFixer {
    private val log = MonolithLogger.getLogger("AutoFix")

    private const val MAX_FIXES_PER_TICK = 500

    fun fixAssembledState(assembledShape: Shape, worldLocation: Location, facing: Facing, centerOffset: Vector3i = Vector3i.ZERO): Int {
        val world = worldLocation.world ?: return 0
        val transform = CoordinateTransform(facing)
        val controllerPos = Vector3i(
            worldLocation.blockX,
            worldLocation.blockY,
            worldLocation.blockZ
        )

        log.debug("fix", "修复成型状态", "anchor" to worldLocation, "facing" to facing, "centerOffset" to centerOffset)

        var fixedCount = 0

        for (blockEntry in assembledShape.blocks) {
            val worldPos = transform.toWorldPosition(
                controllerPos = controllerPos,
                relativePos = blockEntry.position,
                centerOffset = centerOffset
            )

            if (!world.isChunkLoaded(worldPos.x shr 4, worldPos.z shr 4)) continue

            val block = world.getBlockAt(worldPos.x, worldPos.y, worldPos.z)
            val rotatedBlockData = BlockStateRotator.rotate(blockEntry.blockData.clone(), facing.rotationSteps)

            if (block.blockData != rotatedBlockData) {
                block.blockData = rotatedBlockData
                fixedCount++
            }
        }

        if (fixedCount > 0) {
            playCompletionEffects(world, Vector3i(worldLocation.blockX, worldLocation.blockY, worldLocation.blockZ))
        }

        return fixedCount
    }

    fun fixBlocks(
        blocksToFix: List<BlockFixEntry>,
        world: World,
        maxPerTick: Int = MAX_FIXES_PER_TICK
    ): CompletableFuture<Int> {
        val future = CompletableFuture<Int>()

        if (blocksToFix.isEmpty()) {
            future.complete(0)
            return future
        }

        val iterator = blocksToFix.iterator()
        val fixedCount = intArrayOf(0)

        val task = object : Runnable {
            override fun run() {
                var fixedThisTick = 0

                while (iterator.hasNext() && fixedThisTick < maxPerTick) {
                    val entry = iterator.next()
                    val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)

                    if (block.blockData.material == entry.targetBlockData.material) {
                        block.blockData = entry.targetBlockData
                        fixedCount[0]++
                        fixedThisTick++
                    }
                }

                if (!iterator.hasNext()) {
                    if (fixedCount[0] > 0) {
                        playCompletionEffects(world, blocksToFix.first().worldPos)
                    }
                    future.complete(fixedCount[0])
                }
            }
        }

        Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, task, 1L, 1L)

        return future
    }

    fun fixBlocksSync(
        blocksToFix: List<BlockFixEntry>,
        world: World
    ): Int {
        var fixedCount = 0

        for (entry in blocksToFix) {
            val block = world.getBlockAt(entry.worldPos.x, entry.worldPos.y, entry.worldPos.z)

            if (block.blockData.material == entry.targetBlockData.material) {
                block.blockData = entry.targetBlockData
                fixedCount++
            }
        }

        if (fixedCount > 0 && blocksToFix.isNotEmpty()) {
            playCompletionEffects(world, blocksToFix.first().worldPos)
        }

        return fixedCount
    }

    private fun playCompletionEffects(world: World, pos: Vector3i) {
        val location = Location(world, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

        world.spawnParticle(
            Particle.HAPPY_VILLAGER,
            location,
            20,
            1.0, 1.0, 1.0,
            0.1
        )

        world.playSound(location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.5f)
    }
}
