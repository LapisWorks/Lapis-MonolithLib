package top.mc506lw.monolith.feature.buildsite

import io.github.pylonmc.rebar.block.BlockStorage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
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
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.transform.Facing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BuildSiteListener : Listener {

    private val legacy = LegacyComponentSerializer.legacySection()
    private val logger = MonolithLogger.getLogger("BSL")

    private data class PendingConfirmation(
        val blueprintId: String,
        val targetLocation: Location,
        val facing: Facing,
        val timestamp: Long
    )

    private val pendingConfirmations = ConcurrentHashMap<UUID, PendingConfirmation>()
    private val confirmTimeoutMs = 30_000L
    private val recentConfirmations = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val placedBlock = event.block
        val placedPos = Vector3i(placedBlock.x, placedBlock.y, placedBlock.z)

        val site = BuildSiteManager.getSiteAt(placedPos) ?: return

        when (site.state) {
            BuildSiteState.BUILDING -> handlePlaceInBuilding(site, placedPos, placedBlock, player, event)
            BuildSiteState.AWAITING_CORE -> handlePlaceInAwaitingCore(site, placedPos, placedBlock, player, event)
            BuildSiteState.VIRTUAL -> {
                event.isCancelled = true
                player.sendMessage(I18n.translatable("chat.build_site.err_vm_zone_block"))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val brokenBlock = event.block
        val brokenPos = Vector3i(brokenBlock.x, brokenBlock.y, brokenBlock.z)

        val site = BuildSiteManager.getSiteAt(brokenPos)
        if (site != null) {
            when (site.state) {
                BuildSiteState.BUILDING -> handleBreakInBuilding(site, brokenPos, brokenBlock, player, event)
                BuildSiteState.AWAITING_CORE -> handleBreakInAwaitingCore(site, brokenPos, brokenBlock, player, event)
                BuildSiteState.VIRTUAL -> handleBreakInVirtual(site, brokenPos, brokenBlock, player, event)
            }
            return
        }

        val completedSite = BuildSiteManager.getCompletedSiteAt(brokenPos)
        if (completedSite != null) {
            handleBreakInCompleted(completedSite, brokenPos, brokenBlock, player, event)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val item = player.inventory.itemInMainHand

        if (BlueprintItem.isBlueprintItem(item)) {
            handleBlueprintPlace(event, player, item)
            return
        }

        handleControllerActivation(event, player, item)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val from = event.from
        val to = event.to ?: return

        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.state == BuildSiteState.VIRTUAL) continue
            if (site.isCompleted) continue

            val siteWorld = site.anchorLocation.world
            if (siteWorld != player.world) continue

            val dx = to.x - site.anchorLocation.x
            val dy = to.y - site.anchorLocation.y
            val dz = to.z - site.anchorLocation.z
            val distSq = dx * dx + dy * dy + dz * dz

            if (distSq < (BuildSite.UNLOAD_DISTANCE * BuildSite.UNLOAD_DISTANCE).toDouble()) {
                site.renderForPlayer(player)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        for (site in BuildSiteManager.getAllActiveSites()) {
            site.removeRenderingForPlayer(event.player.uniqueId)
        }
        pendingConfirmations.remove(event.player.uniqueId)
        BuildSitePreviewManager.stopPreview(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        if (BuildSitePreviewManager.hasActivePreview(player)) {
            player.sendMessage(I18n.translatable("chat.build_site.preview_cancelled_hotbar"))
            BuildSitePreviewManager.stopPreview(player)
            pendingConfirmations.remove(player.uniqueId)
        }
    }

    private fun handleBlueprintPlace(event: PlayerInteractEvent, player: Player, item: ItemStack) {
        val clickedBlock = event.clickedBlock ?: return
        val blockFace = event.blockFace ?: return

        val blueprintId = BlueprintItem.getBlueprintId(item) ?: return
        val blueprint = top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.get(blueprintId) ?: return

        val targetBlock = clickedBlock.getRelative(blockFace)
        val targetLocation = targetBlock.location

        val playerYaw = player.location.yaw
        val playerPitch = player.location.pitch

        val facing = Facing.fromYaw(playerYaw)

        logger.debug("site=preview", "预览朝向计算", "player" to player.name, "facing" to facing, "yaw" to MonolithLogger.ModuleLogger.formatYaw(playerYaw), "pitch" to MonolithLogger.ModuleLogger.formatPitch(playerPitch), "steps" to facing.rotationSteps)

        targetLocation.yaw = playerYaw
        targetLocation.pitch = playerPitch

        val existing = BuildSiteManager.getSiteAt(Vector3i(targetLocation.blockX, targetLocation.blockY, targetLocation.blockZ))
        if (existing != null) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.siteExists(blueprintId)))
            return
        }
        
        val lastConfirmTime = recentConfirmations[player.uniqueId] ?: 0L
        if (System.currentTimeMillis() - lastConfirmTime < 2000L) {
            return
        }
        
        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.containsPosition(Vector3i(targetLocation.blockX, targetLocation.blockY, targetLocation.blockZ))) {
                player.sendMessage(I18n.translatable("chat.build_site.err_position_in_site"))
                return
            }
        }

        val pending = pendingConfirmations[player.uniqueId]
        if (pending != null && pending.blueprintId == blueprintId
            && pending.targetLocation.blockX == targetLocation.blockX
            && pending.targetLocation.blockY == targetLocation.blockY
            && pending.targetLocation.blockZ == targetLocation.blockZ
            && System.currentTimeMillis() - pending.timestamp < confirmTimeoutMs
        ) {
            confirmBlueprintPlace(event, player, item, blueprint, blueprintId, targetLocation, facing)
            return
        }

        startPreviewPhase(event, player, blueprint, blueprintId, targetLocation, facing)
    }

    private fun startPreviewPhase(
        event: PlayerInteractEvent,
        player: Player,
        blueprint: top.mc506lw.monolith.core.model.Blueprint,
        blueprintId: String,
        targetLocation: Location,
        facing: Facing
    ) {
        event.isCancelled = true

        val existingPreview = BuildSitePreviewManager.getPreview(player)

        if (existingPreview != null && existingPreview.isActive) {
            val moved = BuildSitePreviewManager.movePreviewTo(player, targetLocation, facing)
            if (moved) {
                if (existingPreview.facing != facing) {
                    player.sendMessage("")
                    player.sendMessage(I18n.translatable("chat.build_site.preview_rotated"))
                } else {
                    player.sendMessage("")
                    player.sendMessage(I18n.translatable("chat.build_site.preview_moved"))
                }
                player.sendMessage(I18n.translatable("chat.build_site.preview_confirm_hint"))

                pendingConfirmations[player.uniqueId] = PendingConfirmation(
                    blueprintId = blueprintId,
                    targetLocation = targetLocation.clone(),
                    facing = facing,
                    timestamp = System.currentTimeMillis()
                )
                return
            }
        }

        BuildSitePreviewManager.stopPreview(player)

        val validationResult = BuildSiteValidator.validate(blueprint, targetLocation, facing)

        logger.debug("site=preview", "预览边界框计算", "player" to player.name, "blueprint" to blueprintId, "facing" to facing, "bounds" to MonolithLogger.ModuleLogger.formatCoordRange(
            validationResult.boundingBox.minX, validationResult.boundingBox.minY, validationResult.boundingBox.minZ,
            validationResult.boundingBox.maxX, validationResult.boundingBox.maxY, validationResult.boundingBox.maxZ
        ), "size" to "${validationResult.boundingBox.width}x${validationResult.boundingBox.height}x${validationResult.boundingBox.depth}")

        BuildSitePreviewManager.startPreview(player, blueprint, targetLocation, facing, validationResult)

        for (msg in BuildSitePreviewManager.getPreview(player)?.getSummaryMessage() ?: emptyList()) {
            player.sendMessage(msg)
        }

        player.sendMessage("")
        player.sendMessage(I18n.translatable("chat.build_site.preview_confirm_hint"))
        player.sendMessage(I18n.translatable("chat.build_site.preview_timeout_hint"))

        pendingConfirmations[player.uniqueId] = PendingConfirmation(
            blueprintId = blueprintId,
            targetLocation = targetLocation.clone(),
            facing = facing,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun confirmBlueprintPlace(
        event: PlayerInteractEvent,
        player: Player,
        item: ItemStack,
        blueprint: top.mc506lw.monolith.core.model.Blueprint,
        blueprintId: String,
        targetLocation: Location,
        facing: Facing
    ) {
        pendingConfirmations.remove(player.uniqueId)
        BuildSitePreviewManager.stopPreview(player)

        val site = BuildSiteManager.createSite(blueprint, targetLocation, facing)
        if (site == null) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errCreateFail))
            return
        }

        event.isCancelled = true

        item.amount -= 1
        if (item.amount <= 0) {
            player.inventory.setItemInMainHand(null)
        }

        val existingCount = site.placedBlocks.size
        if (existingCount > 0) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.existingBlocks(existingCount)))
        }

        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.created))
        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.infoLine(blueprintId, facing.name)))
        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.layersInfo(site.totalLayers, site.currentLayer + 1)))
        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.hintCore))

        if (site.checkIfAllNonCoreLayersComplete()) {
            site.enterAwaitingCore()
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.allLayersComplete))
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errCoreBlocked))
        }

        site.renderForPlayer(player)
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
        
        recentConfirmations[player.uniqueId] = System.currentTimeMillis()
    }

    private fun handleControllerActivation(event: PlayerInteractEvent, player: Player, item: ItemStack) {
        val clickedBlock = event.clickedBlock ?: return
        val clickedPos = Vector3i(clickedBlock.x, clickedBlock.y, clickedBlock.z)

        val rebarItem = try {
            io.github.pylonmc.rebar.item.RebarItem.fromStack(item)
        } catch (e: Exception) {
            logger.warn("controller", "RebarItem解析异常", "player" to player.name, "error" to e.message)
            null
        }

        if (rebarItem == null) {
            logger.trace("controller", "非Rebar物品跳过", "player" to player.name, "itemType" to item.type, "itemName" to item.itemMeta?.displayName)
            return
        }

        logger.debug("controller", "检测到Rebar控制器", "player" to player.name, "rebarKey" to rebarItem.schema.key, "clickedPos" to clickedPos)

        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.state != BuildSiteState.AWAITING_CORE) continue

            val isCore = site.isCorePosition(clickedPos) || site.isNearCorePosition(clickedPos)
            logger.trace("controller", "检查工地匹配", "siteId" to site.id, "state" to site.state, "isCore" to isCore, "clickedPos" to clickedPos, "coreWorldPos" to site.coreWorldPos, "coreRebarKey" to site.coreRebarKey)

            if (!isCore) continue

            if (site.coreRebarKey != null && rebarItem.schema.key != site.coreRebarKey) {
                logger.warn("controller", "控制器key不匹配", "player" to player.name, "siteId" to site.id, "required" to site.coreRebarKey, "actual" to rebarItem.schema.key)
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errWrongController))
                return
            }

            logger.info("site=${site.id}", "控制器激活成功", "player" to player.name, "rebarKey" to rebarItem.schema.key)

            event.isCancelled = true

            item.amount -= 1
            if (item.amount <= 0) {
                player.inventory.setItemInMainHand(null)
            }

            activateVirtualMachine(site, player)
            return
        }

        logger.trace("controller", "未找到匹配的AWAITING_CORE工地", "player" to player.name)
    }

    private fun activateVirtualMachine(site: BuildSite, player: Player) {
        site.transitionToVirtual()
        site.markCompleted()

        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.activated))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f)

        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun handlePlaceInBuilding(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockPlaceEvent
    ) {
        if (site.isCorePosition(pos)) {
            event.isCancelled = true
            return
        }

        if (!site.containsPosition(pos)) return

        site.recordPlacement(pos)

        if (site.checkLayerCompletion()) {
            checkLayerAdvancement(site, player)
        }

        site.renderForPlayer(player)
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun handlePlaceInAwaitingCore(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockPlaceEvent
    ) {
        if (site.isCorePosition(pos)) {
            event.isCancelled = true
            return
        }

        if (!site.containsPosition(pos)) return

        site.recordPlacement(pos)
        BuildSiteManager.saveAll()
    }

    private fun handleBreakInBuilding(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockBreakEvent
    ) {
        if (site.isCorePosition(pos)) {
            if (block.type == Material.RED_STAINED_GLASS) {
                event.isDropItems = false
                cancelBuildSite(site, player, event)
            }
            return
        }

        if (!site.containsPosition(pos)) return

        event.isDropItems = false

        val dropItem = ItemStack(block.type)
        block.world.dropItemNaturally(block.location, dropItem)

        val ghost = site.getGhostAt(pos)
        if (ghost != null) {
            block.blockData = ghost.previewBlockData.clone()
        }

        site.removePlacement(pos)

        val incompleteLayer = site.findFirstIncompleteLayer()
        if (incompleteLayer >= 0 && incompleteLayer < site.currentLayer) {
            site.advanceToLayer(incompleteLayer)
        }

        site.renderForPlayer(player)
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun handleBreakInAwaitingCore(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockBreakEvent
    ) {
        if (site.isCorePosition(pos)) {
            event.isCancelled = true
            return
        }

        if (!site.containsPosition(pos)) return

        event.isDropItems = false

        val dropItem = ItemStack(block.type)
        block.world.dropItemNaturally(block.location, dropItem)

        val ghost = site.getGhostAt(pos)
        if (ghost != null) {
            block.blockData = ghost.previewBlockData.clone()
        }

        site.removePlacement(pos)

        val revertedLayer = site.revertToBuilding()
        if (revertedLayer >= 0) {
            player.sendMessage(legacy.serialize(I18n.Message.BuildSite.blockDestroyedRevert(revertedLayer + 1)))
        }

        site.renderForPlayer(player)
        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun handleBreakInVirtual(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockBreakEvent
    ) {
        logger.debug("site=${site.id}", "虚拟区破坏检测", "player" to player.name, "pos" to pos, "state" to site.state)

        if (!site.isVirtualPosition(pos)) {
            logger.trace("site=${site.id}", "位置不在虚拟区域", "pos" to pos)
            return
        }

        event.isCancelled = true

        disassembleVirtualMachine(site, player)
    }

    private fun handleBreakInCompleted(
        site: BuildSite, pos: Vector3i, block: Block, player: Player, event: BlockBreakEvent
    ) {
        if (!site.containsPosition(pos)) return

        event.isCancelled = true

        val reverted = site.revertFromCompleted(player)
        if (reverted) {
            if (site.coreRebarKey != null) {
                try {
                    val controllerItem = io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                        .rebar(Material.LODESTONE, site.coreRebarKey)
                        .build()
                    player.world.dropItemNaturally(player.location, controllerItem)
                } catch (_: Exception) {}
            }

            site.renderForPlayer(player)
            EasyBuildManager.onSiteUpdated(site)
            BuildSiteManager.saveAll()
        }
    }

    private fun disassembleVirtualMachine(site: BuildSite, player: Player) {
        val defaultDrop = if (site.coreRebarKey != null) {
            try {
                io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                    .rebar(Material.LODESTONE, site.coreRebarKey)
                    .build()
            } catch (e: Exception) {
                ItemStack(Material.LODESTONE)
            }
        } else {
            null
        }

        val disassembleEvent = BuildSiteDisassembleEvent(
            site = site,
            player = player,
            dropRebarItem = defaultDrop != null,
            customDrop = defaultDrop
        )

        Bukkit.getPluginManager().callEvent(disassembleEvent)

        if (disassembleEvent.isCancelled) return

        val success = site.disassembleFromVirtual()
        if (!success) return

        if (disassembleEvent.dropRebarItem) {
            val drop = disassembleEvent.customDrop
            if (drop != null) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }

        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.disassembled))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f)

        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun cancelBuildSite(
        site: BuildSite, player: Player, event: BlockBreakEvent
    ) {
        val blueprintItem = BlueprintItem.create(site.blueprintId)
        BlueprintItem.setFacing(blueprintItem, site.facing)

        val cancelEvent = BuildSiteCancelEvent(
            site = site,
            player = player,
            returnBlueprint = true,
            cleanUp = true,
            droppedItem = blueprintItem
        )

        Bukkit.getPluginManager().callEvent(cancelEvent)

        if (cancelEvent.isCancelled) {
            event.isCancelled = true
            return
        }

        event.isDropItems = false

        if (cancelEvent.cleanUp) {
            Bukkit.getLogger().info("[MonolithLib] cancelBuildSite: 清理工地 ${site.id}")
            site.removeCoreGlass()
            site.removeAllRenderings()
            site.removeCoreItemDisplay()
        } else {
            Bukkit.getLogger().info("[MonolithLib] cancelBuildSite: 跳过清理 (cleanUp=false)")
        }

        if (cancelEvent.returnBlueprint) {
            val drop = cancelEvent.droppedItem
            if (drop != null) {
                player.inventory.addItem(drop)
            }
        }

        player.sendMessage(legacy.serialize(I18n.Message.BuildSite.cancelledReturned))

        BuildSiteManager.removeSite(site.id)
        BuildSiteManager.saveAll()
    }

    private fun checkLayerAdvancement(site: BuildSite, player: Player) {
        if (!site.checkLayerCompletion()) return

        val advancedCount = site.advanceToNextIncompleteLayer()

        if (advancedCount > 0) {
            for (step in 1..advancedCount) {
                val completedLayer = site.currentLayer - advancedCount + step
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.layerCompleted(completedLayer + 1)))
            }

            if (site.isLastLayer() && site.isLayerFullyPlaced(site.currentLayer)) {
                site.enterAwaitingCore()
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.allLayersComplete))
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errCoreBlocked))

                scheduleRenderingUpdate(site)
                EasyBuildManager.onSiteUpdated(site)
                BuildSiteManager.saveAll()
                return
            }

            scheduleRenderingUpdate(site)
        } else {
            if (site.checkIfAllNonCoreLayersComplete()) {
                site.enterAwaitingCore()
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.allLayersComplete))
                player.sendMessage(legacy.serialize(I18n.Message.BuildSite.errCoreBlocked))

                scheduleRenderingUpdate(site)
                EasyBuildManager.onSiteUpdated(site)
                BuildSiteManager.saveAll()
                return
            }
        }

        EasyBuildManager.onSiteUpdated(site)
        BuildSiteManager.saveAll()
    }

    private fun scheduleRenderingUpdate(site: BuildSite) {
        Bukkit.getScheduler().runTaskLater(top.mc506lw.monolith.MonolithLib.instance, Runnable {
            for (onlinePlayer in Bukkit.getOnlinePlayers()) {
                site.renderForPlayer(onlinePlayer)
            }
        }, 5L)
    }

    private fun getPlayerFacingDirection(yaw: Float): String {
        val normalizedYaw = ((yaw % 360) + 360) % 360
        return when {
            normalizedYaw < 45 || normalizedYaw >= 315 -> "南方 (SOUTH)"
            normalizedYaw < 135 -> "西方 (WEST)"
            normalizedYaw < 225 -> "北方 (NORTH)"
            else -> "东方 (EAST)"
        }
    }
}
