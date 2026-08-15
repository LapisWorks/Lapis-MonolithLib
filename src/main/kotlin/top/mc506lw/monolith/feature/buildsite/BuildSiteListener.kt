package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.block.BlockStorage
import io.github.pylonmc.rebar.event.RebarBlockPlaceEvent
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

    /** 玩家上次使用的朝向（旋转/确认后记住，下次预览默认沿用，不再退回 yaw 方向）。 */
    private val lastFacingByPlayer = ConcurrentHashMap<UUID, Facing>()

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
                BuildSiteAnchorItem.BLUEPRINT_ID_KEY,
                PersistentDataType.STRING
            ) != true) return null

        // 确保是 Rebar 物品
        try {
            io.github.pylonmc.rebar.item.RebarItem.fromStack(item)
        } catch (_: Exception) { return null }

        return item.itemMeta?.persistentDataContainer?.get(
            BuildSiteAnchorItem.BLUEPRINT_ID_KEY,
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
        // 默认朝向 = 上次记住的朝向；没有则取当前 yaw
        val facing = lastFacingByPlayer[player.uniqueId] ?: Facing.fromYaw(player.location.yaw)

        val pending = pendingConfirmations[player.uniqueId]
        if (pending != null
            && pending.blueprintId == blueprintId
            && pending.targetLocation.blockX == targetLocation.blockX
            && pending.targetLocation.blockY == targetLocation.blockY
            && pending.targetLocation.blockZ == targetLocation.blockZ
            && System.currentTimeMillis() - pending.timestamp < confirmTimeoutMs
        ) {
            // 朝向锁定：确认用预览时的朝向（pending.facing），不随当前 yaw 改变
            confirmAnchorPlace(event, player, item, blueprint, targetLocation, pending.facing)
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
            // 记住本次放置的朝向，下次预览默认沿用
            lastFacingByPlayer[player.uniqueId] = facing
        }

        player.sendMessage(I18n.Message.BuildSite.created)
        player.sendMessage(I18n.Message.BuildSite.createdHint1)
        player.sendMessage(I18n.Message.BuildSite.createdHint2)
    }

    // ---------- 建造追踪 ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val placedBlock = event.block
        // 区块索引 O(1) 定位覆盖该位置的工地
        val anchor = BuildSiteRegistry.findCovering(placedBlock) ?: return
        // Rebar 放置是"先设方块材质、后写注册表"两段式：BlockPlaceEvent 可能发生在
        // 注册完成之前，立即判定会读到中间态（材质对、注册表空 → 假黄块 + 计数器误减）。
        // 推迟 1 tick 再评估；rebar 注册完成后还有 RebarBlockPlaceEvent(MONITOR) 兜底即时刷新。
        Bukkit.getScheduler().runTask(MonolithLib.instance, Runnable {
            if (!placedBlock.world.isChunkLoaded(placedBlock.x shr 4, placedBlock.z shr 4)) return@Runnable
            // 即时刷新该位置的幽灵：放对 → 幽灵立刻消失；放错 → 立刻变色（所有附近玩家同步）
            anchor.renderPositionForPlayer(placedBlock)
            // 异步完成度检查：0→100% 跃迁时提示一次；不阻塞主线程、不刷屏
            anchor.requestCompletionCheck(event.player)
        })
    }

    /**
     * Rebar 放置完成（注册表已写入，MONITOR 保证时序）后立即重新评估该位置：
     * 修复"先设材质、后写注册表"竞态导致的黄块冻结 / 完成度计数器卡住（假缺 1 块）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRebarPlaced(event: RebarBlockPlaceEvent) {
        val placedBlock = event.block
        val anchor = BuildSiteRegistry.findCovering(placedBlock) ?: return
        anchor.renderPositionForPlayer(placedBlock)
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

        // 区块索引 O(1) 定位覆盖该位置的工地
        val anchor = BuildSiteRegistry.findCovering(brokenBlock)
        if (anchor != null) {
            // 破坏方块后重置完成度状态，允许再次触发完成提示
            anchor.resetCompletionReport()
            // 破坏事件触发时方块尚未真正移除（事件结束后才破坏），
            // 下一 tick 再刷新该位置的幽灵，确保读到破坏后的真实方块状态
            Bukkit.getScheduler().runTask(MonolithLib.instance, Runnable {
                anchor.renderPositionForPlayer(brokenBlock)
            })
        }
    }

    // ---------- 玩家移动 ----------

    private val lastMoveChunk = ConcurrentHashMap<UUID, String>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to

        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        // 节流：仅当跨区块边界时才触发渲染，避免每格移动全量重算
        val chunkKey = "${to.blockX shr 4}:${to.blockZ shr 4}"
        val last = lastMoveChunk[player.uniqueId]
        if (last == chunkKey) return
        lastMoveChunk[player.uniqueId] = chunkKey

        val px = to.blockX
        val py = to.blockY
        val pz = to.blockZ
        val farSq = BuildSiteAnchorBlock.FAR_RENDER_DISTANCE_SQ

        // 以"玩家位置"为中心做 O(1) 粗筛（基于缓存 AABB），不再要求玩家必须靠近锚点：
        // 大型建筑的远侧也能触发幽灵渲染；离开范围则清理该玩家在本工地的渲染。
        BuildSiteRegistry.all()
            .filter { it.block.world == player.world }
            .forEach { anchor ->
                if (anchor.intersectsPlayerRange(px, py, pz, BuildSiteAnchorBlock.RENDER_RADIUS) ||
                    anchor.block.location.distanceSquared(to) <= farSq
                ) {
                    anchor.renderForPlayer(player)
                } else {
                    anchor.hideFromPlayer(player)
                }
            }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        pendingConfirmations.remove(playerId)
        lastFacingByPlayer.remove(playerId)
        BuildSitePreviewManager.stopPreview(event.player)
        LitematicaModeManager.onPlayerQuit(playerId)
        top.mc506lw.monolith.feature.buildmode.PrinterTask.stop(event.player)
        EasyBuildManager.onPlayerQuit(playerId)
        BuildSiteRegistry.all().forEach { it.removePlayerRenderings(event.player) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player

        // 蹲下 + 主手持工地展位 + 预览激活 → 滚轮旋转预览朝向（不切物品栏、不取消预览）
        if (player.isSneaking
            && BuildSitePreviewManager.hasActivePreview(player)
            && readAnchorItemBlueprintId(player.inventory.itemInMainHand) != null
        ) {
            val previousSlot = event.previousSlot
            val newSlot = event.newSlot
            // 滚轮滚动方向（处理物品栏 0↔8 环绕：滚轮一次滚动恒为 ±1）
            var delta = (newSlot - previousSlot + 9) % 9
            if (delta > 4) delta -= 9
            if (delta != 0) {
                event.isCancelled = true
                // 复位物品栏，避免滚动把展位切走
                player.inventory.heldItemSlot = previousSlot

                // delta < 0（上滚）→ 顺时针；delta > 0（下滚）→ 逆时针
                if (BuildSitePreviewManager.rotatePreview(player, clockwise = delta < 0)) {
                    val preview = BuildSitePreviewManager.getPreview(player)
                    // 同步 pending 朝向：确认时使用旋转后的朝向
                    preview?.let { p ->
                        pendingConfirmations[player.uniqueId]?.let { pending ->
                            pendingConfirmations[player.uniqueId] = pending.copy(facing = p.facing)
                        }
                        // 记住旋转后的朝向，下次预览默认沿用
                        lastFacingByPlayer[player.uniqueId] = p.facing
                    }
                    // actionbar 反馈新朝向（旋转过程中不刷屏）
                    player.sendActionBar(I18n.Message.BuildSite.previewFacing(preview?.facing?.name ?: "?"))
                }
            }
            return
        }

        // 原有逻辑：切换物品栏 → 取消预览
        if (BuildSitePreviewManager.hasActivePreview(player)) {
            player.sendMessage(I18n.Message.BuildSite.previewCancelledHotbarHint)
            BuildSitePreviewManager.stopPreview(player)
            pendingConfirmations.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        // O(1) 覆盖判断（缓存 AABB）；只回收该区块内的幽灵，避免整站渲染被清空导致闪没
        BuildSiteRegistry.all()
            .filter { it.block.world == event.world }
            .filter { it.coversChunk(event.chunk.x, event.chunk.z) }
            .forEach { it.removeRenderingsInChunk(event.chunk.x, event.chunk.z) }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        BuildSiteRegistry.all()
            .filter { it.block.world == event.world }
            .filter { it.coversChunk(event.chunk.x, event.chunk.z) }
            .forEach { anchor ->
                Bukkit.getOnlinePlayers()
                    .filter { it.world == event.world }
                    .forEach(anchor::renderForPlayer)
            }
    }

    // ---------- 辅助 ----------

}
