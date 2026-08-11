package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.block.BlockStorage
import org.bukkit.Bukkit
import top.mc506lw.monolith.common.I18n
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.MonolithLib
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 工地监听器 — 管理 [BuildSiteAnchorBlock] 的放置、确认、预览、建造追踪。
 */
class BuildSiteListener : Listener {

    private val logger = MonolithLogger.getLogger("BSL")

    private data class PendingConfirmation(
        val blueprintId: String,
        val targetLocation: Location,
        val facing: Facing,
        val timestamp: Long
    )

    private val pendingConfirmations = ConcurrentHashMap<UUID, PendingConfirmation>()
    private val confirmTimeoutMs = 30_000L

    // ---------- 展位放置/确认 ----------

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val item = player.inventory.itemInMainHand

        val blueprintId = readAnchorItemBlueprintId(item)
        if (blueprintId != null) {
            handleAnchorPlace(event, player, item, blueprintId)
        }
    }

    private fun readAnchorItemBlueprintId(item: ItemStack): String? {
        if (item.type != BuildSiteAnchorBlock.MATERIAL) return null
        if (item.itemMeta?.persistentDataContainer?.has(
                NamespacedKey(MonolithLib.instance, "blueprint_id"),
                PersistentDataType.STRING
            ) != true) return null

        // 确保是 Rebar 物品
        try {
            io.github.pylonmc.rebar.item.RebarItem.fromStack(item)
        } catch (_: Exception) { return null }

        return item.itemMeta?.persistentDataContainer?.get(
            NamespacedKey(MonolithLib.instance, "blueprint_id"),
            PersistentDataType.STRING
        )
    }

    private fun handleAnchorPlace(
        event: PlayerInteractEvent,
        player: Player,
        item: ItemStack,
        blueprintId: String
    ) {
        val blueprint = MonolithAPI.getInstance().registry.get(blueprintId) ?: return
        val clickedBlock = event.clickedBlock ?: return
        val blockFace = event.blockFace
        val targetLocation = clickedBlock.getRelative(blockFace).location
        val facing = Facing.fromYaw(player.location.yaw)

        val pending = pendingConfirmations[player.uniqueId]
        if (pending != null
            && pending.blueprintId == blueprintId
            && pending.targetLocation.blockX == targetLocation.blockX
            && pending.targetLocation.blockY == targetLocation.blockY
            && pending.targetLocation.blockZ == targetLocation.blockZ
            && System.currentTimeMillis() - pending.timestamp < confirmTimeoutMs
        ) {
            confirmAnchorPlace(event, player, item, blueprint, targetLocation, facing)
            return
        }

        startAnchorPreview(event, player, blueprint, targetLocation, facing)
    }

    private fun startAnchorPreview(
        event: PlayerInteractEvent,
        player: Player,
        blueprint: top.mc506lw.monolith.core.model.Blueprint,
        targetLocation: Location,
        facing: Facing
    ) {
        event.isCancelled = true

        BuildSitePreviewManager.stopPreview(player)
        val validationResult = BuildSiteValidator.validate(blueprint, targetLocation, facing)
        BuildSitePreviewManager.startPreview(player, blueprint, targetLocation, facing, validationResult)

        for (msg in BuildSitePreviewManager.getPreview(player)?.getSummaryMessage() ?: emptyList()) {
            player.sendMessage(msg)
        }

        player.sendMessage(I18n.Message.BuildSite.previewConfirmHint)
        player.sendMessage(I18n.Message.BuildSite.previewTimeoutHint)

        pendingConfirmations[player.uniqueId] = PendingConfirmation(
            blueprintId = blueprint.id,
            targetLocation = targetLocation.clone(),
            facing = facing,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun confirmAnchorPlace(
        event: PlayerInteractEvent,
        player: Player,
        item: ItemStack,
        blueprint: top.mc506lw.monolith.core.model.Blueprint,
        targetLocation: Location,
        facing: Facing
    ) {
        event.isCancelled = true
        pendingConfirmations.remove(player.uniqueId)
        BuildSitePreviewManager.stopPreview(player)

        val validation = BuildSiteValidator.validate(blueprint, targetLocation, facing)
        if (!validation.isValid) {
            player.sendMessage(I18n.Message.BuildSite.errLocationConflict)
            return
        }

        val targetBlock = targetLocation.block
        if (targetBlock.type != Material.AIR) {
            player.sendMessage(I18n.Message.BuildSite.errTargetNotAir)
            return
        }

        item.amount -= 1
        if (item.amount <= 0) player.inventory.setItemInMainHand(null)

        val placed = BlockStorage.placeBlock(targetBlock, BuildSiteAnchorBlock.KEY)
        if (placed == null) {
            player.sendMessage(I18n.Message.BuildSite.errPlaceAnchorFailed)
            return
        }

        val anchor = placed as? BuildSiteAnchorBlock
        if (anchor != null) {
            anchor.initialize(blueprint.id, facing)
            anchor.renderForPlayer(player)
        }

        player.sendMessage(I18n.Message.BuildSite.created)
        player.sendMessage(I18n.Message.BuildSite.createdHint1)
        player.sendMessage(I18n.Message.BuildSite.createdHint2)
    }

    // ---------- 建造追踪 ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val placedBlock = event.block
        val anchor = findAnchorNearby(placedBlock) ?: return
        val player = event.player

        val rate = anchor.getCompletionRate()
        if (rate >= 1.0) {
            player.sendMessage(I18n.Message.BuildSite.completedHint)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val brokenBlock = event.block
        val player = event.player

        val rebarBlock = BlockStorage.get(brokenBlock)
        if (rebarBlock is BuildSiteAnchorBlock) {
            player.sendMessage(I18n.Message.BuildSite.cancelledHint)
            return
        }

        val anchor = findAnchorNearby(brokenBlock)
        if (anchor != null) {
            // 更新 ghost 颜色
            anchor.renderForPlayer(player)
        }
    }

    // ---------- 玩家移动 ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to

        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        // 查找附近的所有展位并渲染
        BuildSiteRegistry.all()
            .filter { it.block.world == player.world && it.block.location.distanceSquared(to) <= 20.0 * 20.0 }
            .forEach { it.renderForPlayer(player) }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        pendingConfirmations.remove(playerId)
        BuildSitePreviewManager.stopPreview(event.player)
        LitematicaModeManager.onPlayerQuit(playerId)
        top.mc506lw.monolith.feature.buildmode.PrinterTask.stop(event.player)
        EasyBuildManager.onPlayerQuit(playerId)
        BuildSiteRegistry.all().forEach { it.removePlayerRenderings(event.player) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        if (BuildSitePreviewManager.hasActivePreview(player)) {
            player.sendMessage(I18n.Message.BuildSite.previewCancelledHotbarHint)
            BuildSitePreviewManager.stopPreview(player)
            pendingConfirmations.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        BuildSiteRegistry.all()
            .filter { it.block.world == event.world }
            .filter { anchor -> anchor.getGhostEntries().any { it.worldPos.x shr 4 == event.chunk.x && it.worldPos.z shr 4 == event.chunk.z } }
            .forEach { it.removeAllRenderings() }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        BuildSiteRegistry.all()
            .filter { it.block.world == event.world }
            .filter { anchor -> anchor.getGhostEntries().any { it.worldPos.x shr 4 == event.chunk.x && it.worldPos.z shr 4 == event.chunk.z } }
            .forEach { anchor -> Bukkit.getOnlinePlayers().filter { it.world == event.world }.forEach(anchor::renderForPlayer) }
    }

    // ---------- 辅助 ----------

    private fun findAnchorNearby(block: Block): BuildSiteAnchorBlock? {
        val world = block.world
        for (dx in -7..7) {
            for (dy in -7..7) {
                for (dz in -7..7) {
                    val b = world.getBlockAt(block.x + dx, block.y + dy, block.z + dz)
                    val rebar = BlockStorage.get(b)
                    if (rebar is BuildSiteAnchorBlock && rebar.blueprint != null) {
                        return rebar
                    }
                }
            }
        }
        return null
    }
}
