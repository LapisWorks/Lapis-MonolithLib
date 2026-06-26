package top.mc506lw.monolith.feature.buildsite

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.math.Vector3i
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.core.transform.Facing
import top.mc506lw.monolith.lifecycle.ChunkHandler
import top.mc506lw.monolith.MonolithLib
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BuildSiteManager {

    private val logger = MonolithLogger.getLogger("BuildSite")

    private val sites = ConcurrentHashMap<UUID, BuildSite>()
    private val locationIndex = ConcurrentHashMap<String, UUID>()
    private val chunkSites = ConcurrentHashMap<String, MutableSet<UUID>>()
    private var trackingTask: BukkitTask? = null
    private var autoSaveTask: BukkitTask? = null
    
    private lateinit var dataFolder: File
    private lateinit var saveFile: File
    
    private const val TRACKING_INTERVAL = 5L
    private const val AUTO_SAVE_INTERVAL = 300L
    private const val UNLOAD_DISTANCE_SQ = BuildSite.UNLOAD_DISTANCE.toLong() * BuildSite.UNLOAD_DISTANCE

    val chunkHandler = ChunkHandler()
    
    fun init(plugin: MonolithLib) {
        dataFolder = File(plugin.dataFolder, "buildsites")
        dataFolder.mkdirs()
        saveFile = File(dataFolder, "build_sites.yml")
        
        loadAll()
        startTracking(plugin)
        startAutoSave(plugin)
    }
    
    fun createSite(
        blueprint: Blueprint,
        anchorLocation: Location,
        facing: Facing
    ): BuildSite? {
        val existing = getSiteAt(anchorLocation)
        if (existing != null) return null

        val world = anchorLocation.world ?: return null
        if (!world.isChunkLoaded(anchorLocation.blockX shr 4, anchorLocation.blockZ shr 4)) {
            logger.warn("create", "区块未加载，无法创建工地", "pos" to anchorLocation.blockX.toString() + "," + anchorLocation.blockY + "," + anchorLocation.blockZ)
            return null
        }

        val tempSite = BuildSite(
            id = UUID.randomUUID(),
            blueprint = blueprint,
            anchorLocation = anchorLocation.clone(),
            facing = facing
        )

        for (existingSite in getAllActiveSites()) {
            if (existingSite.anchorLocation.world?.name != world.name) continue
            if (boxesOverlap(tempSite, existingSite)) {
                logger.info("create", "工地位置重叠", "newBlueprint" to blueprint.id, "existingSite" to existingSite.blueprintId)
                return null
            }
        }

        registerSite(tempSite)
        saveAll()

        return tempSite
    }

    private fun boxesOverlap(a: BuildSite, b: BuildSite): Boolean {
        return !(a.boundingMaxX < b.boundingMinX || a.boundingMinX > b.boundingMaxX ||
                a.boundingMaxY < b.boundingMinY || a.boundingMinY > b.boundingMaxY ||
                a.boundingMaxZ < b.boundingMinZ || a.boundingMinZ > b.boundingMaxZ)
    }
    
    private fun registerSite(site: BuildSite) {
        sites[site.id] = site
        
        val locationKey = locationToKey(site.anchorLocation)
        locationIndex[locationKey] = site.id
        
        val chunkKey = chunkKeyFromLocation(site.anchorLocation)
        chunkSites.getOrPut(chunkKey) { ConcurrentHashMap.newKeySet() }.add(site.id)
    }
    
    fun getSite(id: UUID): BuildSite? = sites[id]
    
    fun getSiteAt(location: Location): BuildSite? {
        val key = locationToKey(location)
        val siteId = locationIndex[key] ?: return null
        return sites[siteId]
    }

    fun getSiteAt(pos: Vector3i): BuildSite? {
        for (site in getAllActiveSites()) {
            if (site.containsPosition(pos)) return site
        }
        return null
    }

    fun getCompletedSiteAt(pos: Vector3i): BuildSite? {
        for (site in getAllSites()) {
            if (site.isCompleted && site.containsPosition(pos)) return site
        }
        return null
    }
    
    fun getSiteAtBlock(worldName: String, x: Int, y: Int, z: Int): BuildSite? {
        val key = "$worldName|$x|$y|$z"
        val siteId = locationIndex[key] ?: return null
        return sites[siteId]
    }
    
    fun getSitesInChunk(worldName: String, chunkX: Int, chunkZ: Int): List<BuildSite> {
        val chunkKey = "$worldName|$chunkX|$chunkZ"
        val siteIds = chunkSites[chunkKey] ?: return emptyList()
        return siteIds.mapNotNull { sites[it] }.filter { !it.isCompleted }
    }
    
    fun getAllActiveSites(): List<BuildSite> = sites.values.filter { !it.isCompleted }
    
    fun getAllSites(): List<BuildSite> = sites.values.toList()
    
    fun isActiveSite(siteId: UUID): Boolean = sites.containsKey(siteId)
    
    fun getSiteById(siteId: UUID): BuildSite? = sites[siteId]
    
    fun removeSite(siteId: UUID) {
        val site = sites.remove(siteId) ?: return

        val locationKey = locationToKey(site.anchorLocation)
        locationIndex.remove(locationKey)

        site.removeAllRenderings()
        EasyBuildManager.onSiteUpdated(site)

        val chunkKey = chunkKeyFromLocation(site.anchorLocation)
        chunkSites[chunkKey]?.remove(siteId)

        saveAll()
    }
    
    fun handleChunkLoad(worldName: String, chunkX: Int, chunkZ: Int) {
        val chunkKey = "$worldName|$chunkX|$chunkZ"
        val siteIdsInChunk = chunkSites[chunkKey] ?: return
        
        for (siteId in siteIdsInChunk) {
            val site = sites[siteId]
            if (site != null && !site.isCompleted) {
                for (player in Bukkit.getOnlinePlayers()) {
                    site.renderForPlayer(player)
                }
            }
        }
    }
    
    fun handleChunkUnload(worldName: String, chunkX: Int, chunkZ: Int) {
        val chunkKey = "$worldName|$chunkX|$chunkZ"
        val siteIdsInChunk = chunkSites[chunkKey]?.toList() ?: return

        for (siteId in siteIdsInChunk) {
            val site = sites[siteId]
            if (site != null) {
                try { site.removeAllRenderings() } catch (_: Exception) {}
            }
        }
    }
    
    fun onPlayerMove(player: Player) {
        val activeSites = getAllActiveSites()
        
        if (activeSites.isEmpty()) return
        
        for (site in activeSites) {
            if (site.anchorLocation.world?.name != player.world.name) continue
            
            val distSq = player.location.distanceSquared(site.anchorLocation)
            
            if (distSq <= UNLOAD_DISTANCE_SQ) {
                site.renderForPlayer(player)
            } else {
                site.removeRenderingForPlayer(player.uniqueId)
            }
        }
    }
    
    fun onPlayerQuit(player: Player) {
        for (site in getAllActiveSites()) {
            site.removeRenderingForPlayer(player.uniqueId)
        }
    }
    
    private fun startTracking(plugin: MonolithLib) {
        trackingTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tickTracking()
        }, TRACKING_INTERVAL, TRACKING_INTERVAL)
    }

    private fun startAutoSave(plugin: MonolithLib) {
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            saveAll()
        }, AUTO_SAVE_INTERVAL, AUTO_SAVE_INTERVAL)
    }
    
    private fun tickTracking() {
        for (player in Bukkit.getOnlinePlayers()) {
            onPlayerMove(player)
        }
    }
    
    fun stopTracking() {
        trackingTask?.cancel()
        trackingTask = null
        autoSaveTask?.cancel()
        autoSaveTask = null
    }
    
    fun cleanup() {
        stopTracking()
        for (site in sites.values) {
            site.removeAllRenderings()
        }
        sites.clear()
        locationIndex.clear()
        chunkSites.clear()
    }
    
    private fun locationToKey(location: Location): String {
        return "${location.world?.name}|${location.blockX}|${location.blockY}|${location.blockZ}"
    }
    
    private fun chunkKeyFromLocation(location: Location): String {
        val cx = location.blockX shr 4
        val cz = location.blockZ shr 4
        return "${location.world?.name}|$cx|$cz"
    }

    private fun loadAll() {
        if (!saveFile.exists()) return
        
        try {
            val lines = saveFile.readLines()
            var currentSite: MutableMap<String, Any>? = null
            val loadedSites = mutableListOf<MutableMap<String, Any>>()
            
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("site:") -> {
                        currentSite = mutableMapOf("id" to trimmed.substringAfter("site:").trim())
                        loadedSites.add(currentSite!!)
                    }
                    trimmed.contains(":") && currentSite != null -> {
                        val parts = trimmed.split(":", limit = 2)
                        if (parts.size == 2) {
                            currentSite!![parts[0].trim()] = parts[1].trim()
                        }
                    }
                    trimmed.isEmpty() -> {
                        currentSite = null
                    }
                }
            }
            
            for (siteData in loadedSites) {
                try {
                    restoreSiteFromData(siteData)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to load build site" }
                }
            }

            logger.info { "Loaded ${loadedSites.size} persisted build sites" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to read build site saves" }
        }
    }

    private fun restoreSiteFromData(data: Map<String, Any>) {
        val idStr = data["id"] as? String ?: return
        val blueprintId = data["blueprint_id"] as? String ?: return
        val worldName = data["world"] as? String ?: return
        val x = (data["x"] as? String)?.toIntOrNull() ?: return
        val y = (data["y"] as? String)?.toIntOrNull() ?: return
        val z = (data["z"] as? String)?.toIntOrNull() ?: return
        val facingStr = data["facing"] as? String ?: "NORTH"
        val stateStr = data["state"] as? String ?: "BUILDING"
        val layerStr = data["current_layer"] as? String ?: "0"
        val placedStr = data["placed_blocks"] as? String ?: ""
        val completedStr = data["completed"] as? String ?: "false"
        val backupStr = data["backup_data"] as? String ?: ""

        val api = try { MonolithAPI.getInstance() } catch (_: Exception) { return }
        val blueprint = api.registry.get(blueprintId)
        if (blueprint == null) {
            logger.warn { "Blueprint '$blueprintId' no longer exists, build site $idStr will be skipped (location: $worldName,$x,$y,$z)" }
            return
        }

        val world = Bukkit.getWorld(worldName) ?: return
        val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        val facing = try { Facing.valueOf(facingStr) } catch (_: Exception) { Facing.NORTH }
        val restoredState = try { BuildSiteState.valueOf(stateStr) } catch (_: Exception) { BuildSiteState.BUILDING }
        val currentLayer = layerStr.toIntOrNull() ?: 0
        val isCompleted = completedStr == "true"

        val placedBlocks = if (placedStr.isNotEmpty()) {
            placedStr.split(";").mapNotNull { pos ->
                val coords = pos.split(",")
                if (coords.size == 3) {
                    Vector3i(coords[0].toIntOrNull() ?: return@mapNotNull null,
                             coords[1].toIntOrNull() ?: return@mapNotNull null,
                             coords[2].toIntOrNull() ?: return@mapNotNull null)
                } else null
            }.toSet()
        } else emptySet()

        val site = BuildSite(
            id = UUID.fromString(idStr),
            blueprint = blueprint,
            anchorLocation = location,
            facing = facing,
            initialLayer = currentLayer,
            initialPlacedBlocks = placedBlocks,
            initialState = restoredState
        )

        if (isCompleted) {
            site.restoreCompleted()
        }

        if (backupStr.isNotEmpty()) {
            for (entry in backupStr.split(";")) {
                val eqIdx = entry.indexOf('=')
                if (eqIdx < 0) continue
                val posPart = entry.substring(0, eqIdx)
                val dataPart = entry.substring(eqIdx + 1)
                val coords = posPart.split(",")
                if (coords.size != 3) continue
                val bx = coords[0].toIntOrNull() ?: continue
                val by = coords[1].toIntOrNull() ?: continue
                val bz = coords[2].toIntOrNull() ?: continue
                try {
                    val blockData = Bukkit.createBlockData(dataPart)
                    site.backupData[Vector3i(bx, by, bz)] = blockData
                } catch (_: Exception) {}
            }
        }

        registerSite(site)

        if (restoredState == BuildSiteState.VIRTUAL && isCompleted) {
            logger.info("restore", "恢复已完成VIRTUAL工地", "siteId" to idStr, "backupSize" to site.backupData.size)
        } else if (restoredState == BuildSiteState.VIRTUAL) {
            logger.info("restore", "恢复VIRTUAL工地", "siteId" to idStr)
        }
    }
    
    fun saveAll() {
        try {
            val lines = mutableListOf<String>()
            
            for ((_, site) in sites) {
                lines.add("site: ${site.id}")
                lines.add("  blueprint_id: ${site.blueprintId}")
                lines.add("  world: ${site.anchorLocation.world?.name}")
                lines.add("  x: ${site.anchorLocation.blockX}")
                lines.add("  y: ${site.anchorLocation.blockY}")
                lines.add("  z: ${site.anchorLocation.blockZ}")
                lines.add("  facing: ${site.facing.name}")
                lines.add("  state: ${site.state.name}")
                lines.add("  current_layer: ${site.currentLayer}")
                lines.add("  completed: ${site.isCompleted}")
                
                val placedStr = site.placedBlocks.joinToString(";") { "${it.x},${it.y},${it.z}" }
                lines.add("  placed_blocks: $placedStr")
                
                if (site.backupData.isNotEmpty()) {
                    val backupStr = site.backupData.entries.joinToString(";") { (pos, data) ->
                        "${pos.x},${pos.y},${pos.z}=${data.getAsString()}"
                    }
                    lines.add("  backup_data: $backupStr")
                }
                
                lines.add("")
            }
            
            saveFile.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to save build site data" }
        }
    }
}
