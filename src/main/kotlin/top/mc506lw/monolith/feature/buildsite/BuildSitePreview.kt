package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import top.mc506lw.monolith.feature.preview.SmoothBoundingBoxRenderer
import top.mc506lw.monolith.MonolithLib
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.CoordinateTransform
import top.mc506lw.monolith.core.transform.Facing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BuildSitePreviewManager {

    private val logger = MonolithLogger.getLogger("Preview")
    
    private val activePreviews = ConcurrentHashMap<UUID, BuildSitePreview>()
    private val playerPreviews = ConcurrentHashMap<UUID, UUID>()
    private val previewTasks = ConcurrentHashMap<UUID, BukkitTask>()
    
    fun startPreview(
        player: Player,
        blueprint: Blueprint,
        anchorLocation: Location,
        facing: Facing,
        validationResult: ValidationResult
    ): BuildSitePreview? {
        stopPreview(player)
        
        val world = anchorLocation.world ?: return null
        
        val preview = BuildSitePreview(
            id = UUID.randomUUID(),
            playerId = player.uniqueId,
            blueprintId = blueprint.id,
            world = world,
            boundingBox = validationResult.boundingBox,
            validationResult = validationResult,
            facing = facing,
            anchorLocation = anchorLocation.clone()
        )
        
        activePreviews[preview.id] = preview
        playerPreviews[player.uniqueId] = preview.id
        
        preview.show()
        
        scheduleAutoCancel(player)
        
        return preview
    }
    
    fun movePreviewTo(player: Player, newAnchorLocation: Location, newFacing: Facing): Boolean {
        val preview = getPreview(player) ?: return false
        if (!preview.isActive) return false

        val newAnchorX = newAnchorLocation.blockX
        val newAnchorY = newAnchorLocation.blockY
        val newAnchorZ = newAnchorLocation.blockZ

        val offsetX = newAnchorX - preview.anchorLocation.blockX
        val offsetY = newAnchorY - preview.anchorLocation.blockY
        val offsetZ = newAnchorZ - preview.anchorLocation.blockZ

        if (offsetX == 0 && offsetY == 0 && offsetZ == 0 && preview.facing == newFacing) return true

        preview.anchorLocation.x = newAnchorX.toDouble()
        preview.anchorLocation.y = newAnchorY.toDouble()
        preview.anchorLocation.z = newAnchorZ.toDouble()

        if (preview.facing != newFacing) {
            logger.debug("preview=${preview.id}", "预览朝向变更", "player" to player.name, "oldFacing" to preview.facing, "newFacing" to newFacing)

            val blueprint = top.mc506lw.monolith.api.MonolithAPI.getInstance().registry.get(preview.blueprintId)
            if (blueprint != null) {
                val newValidationResult = BuildSiteValidator.validate(blueprint, newAnchorLocation, newFacing)

                logger.debug("preview=${preview.id}", "预览框已旋转", "facing" to newFacing, "bounds" to MonolithLogger.ModuleLogger.formatCoordRange(
                    newValidationResult.boundingBox.minX, newValidationResult.boundingBox.minY, newValidationResult.boundingBox.minZ,
                    newValidationResult.boundingBox.maxX, newValidationResult.boundingBox.maxY, newValidationResult.boundingBox.maxZ
                ))

                val newBoxData = SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
                    newValidationResult.boundingBox.minX, newValidationResult.boundingBox.minY, newValidationResult.boundingBox.minZ,
                    newValidationResult.boundingBox.maxX, newValidationResult.boundingBox.maxY, newValidationResult.boundingBox.maxZ
                )

                preview.boxRenderer?.moveTo(newBoxData)
                preview.facing = newFacing
                preview.boundingBox = newValidationResult.boundingBox
                preview.validationResult = newValidationResult
            }
        } else {
            preview.boxRenderer?.updatePosition(offsetX, offsetY, offsetZ)
        }

        preview.errorMarkerDisplays.forEach { marker ->
            if (marker.isValid) {
                marker.teleport(Location(preview.world,
                    marker.location.x + offsetX,
                    marker.location.y + offsetY,
                    marker.location.z + offsetZ
                ))
            }
        }

        resetAutoCancel(player)

        return true
    }
    
    private fun scheduleAutoCancel(player: Player) {
        var countdown = 30
        val task = Bukkit.getScheduler().runTaskTimer(MonolithLib.instance, Runnable {
            countdown--
            if (countdown <= 0) {
                if (hasActivePreview(player)) {
                    player.sendMessage(I18n.Message.BuildSite.previewExpired)
                    stopPreview(player)
                }
                previewTasks.remove(player.uniqueId)?.cancel()
            } else if (countdown <= 3 && hasActivePreview(player)) {
                player.sendActionBar(I18n.Message.BuildSite.previewCountdown(countdown))
            }
        }, 20L, 20L)
        
        previewTasks[player.uniqueId] = task
    }
    
    private fun resetAutoCancel(player: Player) {
        val existingTask = previewTasks.remove(player.uniqueId)
        existingTask?.cancel()
        
        if (hasActivePreview(player)) {
            scheduleAutoCancel(player)
        }
    }
    
    fun stopPreview(player: Player) {
        val task = previewTasks.remove(player.uniqueId)
        task?.cancel()
        
        val previewId = playerPreviews.remove(player.uniqueId) ?: return
        val preview = activePreviews.remove(previewId) ?: return
        preview.hide()
    }
    
    fun stopAllPreviews() {
        previewTasks.values.forEach { it.cancel() }
        previewTasks.clear()
        
        activePreviews.values.forEach { it.hide() }
        activePreviews.clear()
        playerPreviews.clear()
    }
    
    fun getPreview(player: Player): BuildSitePreview? {
        val previewId = playerPreviews[player.uniqueId] ?: return null
        return activePreviews[previewId]
    }
    
    fun hasActivePreview(player: Player): Boolean {
        return playerPreviews.containsKey(player.uniqueId)
    }
    
    fun confirmPreview(player: Player): BuildSitePreview? {
        val preview = getPreview(player) ?: return null
        return preview
    }
    
    fun cancelPreview(player: Player) {
        stopPreview(player)
    }
}

class BuildSitePreview(
    val id: UUID,
    val playerId: UUID,
    val blueprintId: String,
    val world: World,
    var boundingBox: BoundingBox,
    var validationResult: ValidationResult,
    var facing: Facing,
    var anchorLocation: Location
) {
    
    var boxRenderer: SmoothBoundingBoxRenderer? = null
    internal var errorMarkerDisplays = mutableListOf<BlockDisplay>()
    var isActive: Boolean = false
        private set

    fun show() {
        if (isActive) return
        isActive = true
        
        val color = if (validationResult.isValid) Color.RED else Color.fromRGB(255, 85, 85)
        
        boxRenderer = SmoothBoundingBoxRenderer(
            plugin = MonolithLib.instance,
            world = world,
            initialColor = color,
            thickness = 0.08f,
            maxMoveRadius = 10.0,
            interpolationTicks = 8,
            cornerSize = 0.25f
        )
        
        val boxData = SmoothBoundingBoxRenderer.BoundingBoxData.fromMinMax(
            boundingBox.minX, boundingBox.minY, boundingBox.minZ,
            boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ
        )
        boxRenderer!!.show(boxData)

        createErrorMarkers()
    }
    
    fun hide() {
        if (!isActive) return
        isActive = false
        
        boxRenderer?.hide()
        boxRenderer = null
        
        errorMarkerDisplays.forEach { 
            if (it.isValid) it.remove() 
        }
        errorMarkerDisplays.clear()
    }
    
    private fun createErrorMarkers() {
        val errorColor = Color.fromRGB(255, 0, 0)
        
        for (error in validationResult.errors) {
            for (pos in error.positions) {
                createErrorMarker(pos, errorColor)
            }
        }
        
        val warningColor = Color.fromRGB(255, 255, 0)
        for (warning in validationResult.warnings) {
            for (pos in warning.positions) {
                createErrorMarker(pos, warningColor)
            }
        }
    }
    
    private fun createErrorMarker(pos: Vector3i, color: Color) {
        val location = Location(world, pos.x.toDouble() + 0.5, pos.y.toDouble() + 0.5, pos.z.toDouble() + 0.5)
        
        try {
            val display = world.spawn(location, BlockDisplay::class.java) { d ->
                d.block = Bukkit.createBlockData(if (color == Color.fromRGB(255, 0, 0)) Material.REDSTONE_BLOCK else Material.GOLD_BLOCK)
                d.glowColorOverride = color
                d.isPersistent = false
                d.brightness = Display.Brightness(15, 15)
                
                d.transformation = Transformation(
                    Vector3f(-0.3f, -0.3f, -0.3f),
                    AxisAngle4f(),
                    Vector3f(0.6f, 0.6f, 0.6f),
                    AxisAngle4f()
                )
            }
            
            errorMarkerDisplays.add(display)
        } catch (_: Exception) {}
    }
    
    fun getSummaryMessage(): List<Component> {
        val messages = mutableListOf<Component>()
        
        messages.add(I18n.Message.BuildSite.previewHeader)
        messages.add(I18n.Message.BuildSite.previewBlueprint(blueprintId))
        messages.add(I18n.Message.BuildSite.previewFacing(facing.name))
        messages.add(I18n.Message.BuildSite.previewSize(boundingBox.width, boundingBox.height, boundingBox.depth))
        messages.add(I18n.Message.BuildSite.previewPosition(
            boundingBox.minX, boundingBox.minY, boundingBox.minZ,
            boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ
        ))
        
        if (validationResult.isValid) {
            messages.add(I18n.Message.BuildSite.previewValid)
        } else {
            messages.add(I18n.Message.BuildSite.previewErrors(validationResult.errors.size))
            for (error in validationResult.errors) {
                messages.add(LegacyComponentSerializer.legacySection().deserialize("  §c- ${error.message}"))
            }
        }
        
        if (validationResult.warnings.isNotEmpty()) {
            messages.add(I18n.Message.BuildSite.previewWarnings)
            for (warning in validationResult.warnings) {
                messages.add(LegacyComponentSerializer.legacySection().deserialize("  §e- ${warning.message}"))
            }
        }
        
        messages.add(I18n.Message.BuildSite.previewInstructions)
        messages.add(I18n.Message.BuildSite.previewAutoCancel(30))
        messages.add(I18n.Message.BuildSite.previewFooter)
        
        return messages
    }
}
