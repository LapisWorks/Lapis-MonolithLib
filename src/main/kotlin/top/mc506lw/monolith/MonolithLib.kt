package top.mc506lw.monolith

import io.github.pylonmc.rebar.addon.RebarAddon
import io.github.pylonmc.rebar.block.BlockStorage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.api.BlueprintAPI
import top.mc506lw.monolith.common.Constants
import top.mc506lw.monolith.common.I18n
import top.mc506lw.monolith.common.LogConfig
import top.mc506lw.monolith.common.MonolithLogger
import top.mc506lw.monolith.core.model.Blueprint
import top.mc506lw.monolith.lifecycle.ChunkHandler
import top.mc506lw.monolith.core.io.IOModule
import top.mc506lw.monolith.feature.material.MaterialModule
import top.mc506lw.monolith.feature.preview.PreviewModule
import top.mc506lw.monolith.feature.preview.StructurePreviewManager
import top.mc506lw.monolith.feature.builder.StructureBuildManager
import top.mc506lw.monolith.feature.rebar.RebarModule
import top.mc506lw.monolith.feature.buildsite.BuildSiteManager
import top.mc506lw.monolith.feature.buildsite.BuildSite
import top.mc506lw.monolith.feature.buildsite.BuildSiteListener
import top.mc506lw.monolith.feature.buildsite.BuildSiteState
import top.mc506lw.monolith.internal.listener.MonolithBlockListener
import top.mc506lw.monolith.internal.listener.RebarControllerListener
import top.mc506lw.monolith.internal.scheduler.TickScheduler
import top.mc506lw.monolith.internal.selection.SelectionManager
import top.mc506lw.monolith.internal.selection.SelectionWand
import top.mc506lw.monolith.internal.command.MonolithTabCompleter
import top.mc506lw.monolith.test.TestModule
import top.mc506lw.monolith.feature.machine.BlueprintTableMachine
import top.mc506lw.monolith.feature.virtual.VirtualDisplayAnchorRegistry
import java.io.File
import java.util.Locale

class MonolithLib : JavaPlugin(), RebarAddon {

    companion object {
        @JvmStatic
        lateinit var instance: MonolithLib
            private set

        private val moduleLogger = MonolithLogger.getLogger("Core")
    }

    override val javaPlugin: JavaPlugin get() = this
    override val languages: Set<Locale> = setOf(Locale.ENGLISH, Locale.CHINESE)
    override val material: Material = Material.STRUCTURE_BLOCK

    private lateinit var api: BlueprintAPI
    private lateinit var scheduler: TickScheduler
    private lateinit var ioModule: IOModule
    private lateinit var previewModule: PreviewModule
    private lateinit var materialModule: MaterialModule
    private lateinit var rebarModule: RebarModule
    private lateinit var buildSiteListener: BuildSiteListener
    private lateinit var blockListener: MonolithBlockListener
    private lateinit var chunkHandler: ChunkHandler

    val importsDirectory: File
        get() = ioModule.importsDirectory

    val blueprintsDirectory: File
        get() = ioModule.blueprintsDirectory

    val productsDirectory: File
        get() = ioModule.productsDirectory

    override fun onEnable() {
        instance = this

        LogConfig.load(dataFolder)
        moduleLogger.info { "Initializing MonolithLib v${pluginMeta.version}..." }

        registerWithRebar()

        initializeCore()
        initializeModules()
        loadStructures()
        initBuildSiteSystem()
        registerListeners()
        registerCommands()
        initTestModule()
        initMachines()
        SelectionManager.init()

        moduleLogger.info { "Initialization complete! Version: ${pluginMeta.version}" }
    }

    override fun onDisable() {
        moduleLogger.info { "Shutting down..." }

        scheduler.shutdown()
        previewModule.onDisable()
        materialModule.clearCache()
        StructurePreviewManager.cleanup()
        StructureBuildManager.cleanup()
        BuildSiteManager.stopTracking()
        BuildSiteManager.cleanup()
        top.mc506lw.monolith.feature.buildsite.EasyBuildManager.cleanup()
        top.mc506lw.monolith.feature.buildsite.PrinterManager.cleanup()
        top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.cleanup()
        SelectionManager.shutdown()

        moduleLogger.info { "Shutdown complete" }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!command.name.equals("monolith", ignoreCase = true)) return false

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        return when (args[0].lowercase()) {
            "preview" -> { handlePreviewDomain(sender, args.drop(1)); true }
            "build" -> { handleBuildDomain(sender, args.drop(1)); true }
            "bp" -> { handleBlueprintDomain(sender, args.drop(1)); true }
            "site" -> { handleSiteDomain(sender, args.drop(1)); true }
            "edit" -> { handleEditDomain(sender, args.drop(1)); true }
            "reload" -> { handleReload(sender); true }
            else -> { sendHelp(sender); true }
        }
    }

    private fun initializeCore() {
        scheduler = TickScheduler(this)
        api = BlueprintAPI()
        MonolithAPI.setInstance(api)

        blockListener = MonolithBlockListener.getInstance()
        chunkHandler = ChunkHandler()
    }

    private fun initializeModules() {
        ioModule = IOModule(dataFolder)
        previewModule = PreviewModule(this)
        materialModule = MaterialModule(this)
        rebarModule = RebarModule(this)
    }

    private fun initBuildSiteSystem() {
        buildSiteListener = BuildSiteListener()
        BuildSiteManager.init(this)

        val siteCount = BuildSiteManager.getAllActiveSites().size
        if (siteCount > 0) {
            moduleLogger.info { "Restored $siteCount persisted build sites" }
            top.mc506lw.monolith.feature.buildsite.EasyBuildManager.rebuildIndex()
        }

        Bukkit.getScheduler().runTaskTimer(this, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.onPlayerTick(player)
            }
        }, 20L, 20L)
    }

    private fun registerListeners() {
        server.pluginManager.registerEvents(blockListener, this)
        server.pluginManager.registerEvents(chunkHandler, this)
        server.pluginManager.registerEvents(RebarControllerListener, this)
        server.pluginManager.registerEvents(buildSiteListener, this)
        server.pluginManager.registerEvents(top.mc506lw.monolith.feature.buildsite.EasyBuildManager, this)
    }

    private fun registerCommands() {
        getCommand("monolith")?.setExecutor(this)
        getCommand("monolith")?.tabCompleter = MonolithTabCompleter()
    }

    private fun initTestModule() {
        try {
            TestModule.init()
        } catch (e: Exception) {
            moduleLogger.warn { "Test module initialization failed: ${e.message}" }
        }
    }

    private fun initMachines() {
        try {
            BlueprintTableMachine.registerAll()
            VirtualDisplayAnchorRegistry.register()
        } catch (e: Exception) {
            moduleLogger.warn { "Machine module initialization failed: ${e.message}" }
        }

        try {
            io.github.pylonmc.rebar.item.RebarItem.register(SelectionWand::class.java, SelectionWand.STACK, SelectionWand.KEY)
        } catch (e: Exception) {
            moduleLogger.warn { "Selection wand initialization failed: ${e.message}" }
        }
    }

    private fun loadStructures() {
        val blueprints = ioModule.loadAllBlueprints()

        blueprints.forEach { blueprint ->
            api.registry.register(blueprint)
            moduleLogger.info { "Registered blueprint: ${blueprint.id} (${blueprint.sizeX}x${blueprint.sizeY}x${blueprint.sizeZ}, ${blueprint.blockCount} non-air blocks)" }
        }

        moduleLogger.info { "Total blueprints loaded: ${blueprints.size}" }
    }

    private fun sendHelp(sender: CommandSender) {
        val H = I18n.Message.Command.Help
        sender.sendMessage(H.header)
        sender.sendMessage(H.title)
        sender.sendMessage("")
        sender.sendMessage(H.separator)
        sender.sendMessage(H.sectionPreview)
        sender.sendMessage(H.sectionPreviewArg("<ID>", "预览完整结构"))
        sender.sendMessage(H.sectionPreviewArg("stop", "停止预览"))
        sender.sendMessage("")
        sender.sendMessage(H.sectionBuild)
        sender.sendMessage(H.sectionPreviewArg("here <ID> [facing]", "一键建造"))
        sender.sendMessage(H.sectionPreviewArg("easy [on|off]", "轻松放置模式"))
        sender.sendMessage(H.sectionPreviewArg("printer [on|off]", "自动打印模式"))
        sender.sendMessage("")
        sender.sendMessage(H.sectionBp)
        sender.sendMessage(H.sectionPreviewArg("list", "列出蓝图"))
        sender.sendMessage(H.sectionPreviewArg("info <ID>", "蓝图详情"))
        sender.sendMessage(H.sectionPreviewArg("give <ID>", "给予蓝图物品"))
        sender.sendMessage("")
        sender.sendMessage(H.sectionSite)
        sender.sendMessage(H.sectionPreviewArg("list", "活跃工地列表"))
        sender.sendMessage(H.sectionPreviewArg("info", "附近工地状态"))
        sender.sendMessage(H.sectionPreviewArg("cancel", "取消工地"))
        sender.sendMessage("")
        sender.sendMessage(H.sectionEdit)
        sender.sendMessage(H.sectionPreviewArg("wand", "获取选区魔杖"))
        sender.sendMessage(H.sectionPreviewArg("save <name> [--scaffold|--assembled]","保存结构"))
        sender.sendMessage(H.sectionPreviewArg("merge <a> <b>", "合并结构"))
        sender.sendMessage("")
        sender.sendMessage(H.sectionReload)
        sender.sendMessage(H.separator)
        sender.sendMessage(H.footerBlank)
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission(Constants.Permissions.RELOAD)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        sender.sendMessage(I18n.Message.Command.Reload.starting)

        scheduler.cancelAllTasks()
        api.registry.clear()

        val blueprints = ioModule.loadAllBlueprints()
        blueprints.forEach { blueprint ->
            api.registry.register(blueprint)
        }

        sender.sendMessage(I18n.Message.Command.Reload.complete(blueprints.size))
    }

    private fun handlePreviewDomain(sender: CommandSender, args: List<String>) {
        if (!sender.isOp) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        when {
            args.isEmpty() -> handlePreviewShow(sender, emptyList())
            args[0].equals("stop", ignoreCase = true) -> handlePreviewStop(sender)
            else -> handlePreviewShow(sender, args.toList())
        }
    }

    private fun handlePreviewShow(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        val blueprintId = args.getOrNull(0)
        if (blueprintId == null) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.preview)
            sender.sendMessage(I18n.Message.Command.ErrUsage.previewStop)
            sender.sendMessage(I18n.Message.Common.hintTabComplete)
            return
        }

        val blueprint = api.registry.get(blueprintId)
        if (blueprint == null) {
            sender.sendMessage(I18n.Message.Command.blueprintNotFound(blueprintId))
            sender.sendMessage(I18n.Message.Command.hintBpList)
            return
        }

        val targetLocation = sender.location.clone().add(sender.location.direction.normalize())
        targetLocation.x = targetLocation.blockX.toDouble()
        targetLocation.y = targetLocation.blockY.toDouble()
        targetLocation.z = targetLocation.blockZ.toDouble()

        val facing = top.mc506lw.monolith.core.transform.Facing.fromYaw(sender.location.yaw)

        val session = StructurePreviewManager.startPreview(
            player = sender,
            blueprint = blueprint,
            controllerLocation = targetLocation,
            facing = facing
        )

        if (session != null) {
            sender.sendMessage(I18n.Message.Preview.started(blueprint.id, facing.name))
            sender.sendMessage(I18n.Message.Preview.willExpire)
        } else {
            sender.sendMessage(I18n.Message.Preview.errCreateFailed)
        }
    }

    private fun handlePreviewStop(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        StructurePreviewManager.cancelPreview(sender)
        sender.sendMessage(I18n.Message.Preview.cancelled)
    }

    private fun handleBuildDomain(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.build)
            sender.sendMessage(I18n.Message.Command.ErrUsage.buildHere)
            sender.sendMessage(I18n.Message.Command.ErrUsage.buildEasy)
            sender.sendMessage(I18n.Message.Command.ErrUsage.buildPrinter)
            return
        }

        when (args[0].lowercase()) {
            "here" -> handleBuildHere(sender, args.drop(1))
            "easy" -> handleBuildEasy(sender, args.drop(1))
            "printer" -> handleBuildPrinter(sender, args.drop(1))
            else -> {
                sender.sendMessage(I18n.Message.Command.ErrUnknown.build(args[0]))
                sender.sendMessage(I18n.Message.Command.ErrUnknown.availableBuild)
            }
        }
    }

    private fun handleBuildHere(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission(Constants.Permissions.BUILD)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val blueprintId = args.getOrNull(0)
        if (blueprintId == null) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.buildHere)
            return
        }

        val blueprint = api.registry.get(blueprintId)
        if (blueprint == null) {
            sender.sendMessage(I18n.Message.Command.blueprintNotFound(blueprintId))
            return
        }

        val targetLocation = sender.location.clone().add(sender.location.direction.normalize())
        targetLocation.x = targetLocation.blockX.toDouble()
        targetLocation.y = targetLocation.blockY.toDouble()
        targetLocation.z = targetLocation.blockZ.toDouble()

        val facing = top.mc506lw.monolith.core.transform.Facing.fromYaw(sender.location.yaw)

        val builder = StructureBuildManager.startBuild(
            player = sender,
            blueprint = blueprint,
            controllerLocation = targetLocation,
            facing = facing
        )

        if (builder == null) {
            sender.sendMessage(I18n.Message.Command.errBuildFailed)
        }
    }

    private fun handleBuildEasy(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission("monolith.easybuild")) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val result = top.mc506lw.monolith.feature.buildsite.EasyBuildManager.toggle(sender)

        when (result) {
            true -> {
                sender.sendMessage(I18n.Message.BuildMode.easybuildEnabled)
                sender.sendMessage(I18n.Message.BuildMode.easybuildHint1)
                sender.sendMessage(I18n.Message.BuildMode.easybuildHint2)
                sender.sendMessage(I18n.Message.BuildMode.easybuildHint3)
            }
            false -> {
                sender.sendMessage(I18n.Message.BuildMode.easybuildDisabled)
            }
            null -> {
                sender.sendMessage(I18n.Message.BuildMode.errNoSiteEasybuild)
            }
        }
    }

    private fun handleBuildPrinter(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission("monolith.printer")) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val result = top.mc506lw.monolith.feature.buildsite.PrinterManager.toggle(sender)

        when (result) {
            true -> {
                sender.sendMessage(I18n.Message.BuildMode.printerEnabled)
                sender.sendMessage(I18n.Message.BuildMode.printerHint1)
                sender.sendMessage(I18n.Message.BuildMode.printerHint2)
                sender.sendMessage(I18n.Message.BuildMode.printerHint3)
            }
            false -> {
                sender.sendMessage(I18n.Message.BuildMode.printerDisabled)
            }
            null -> {
                sender.sendMessage(I18n.Message.BuildMode.errNoSitePrinter)
            }
        }
    }

    private fun handleBlueprintDomain(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.bp)
            sender.sendMessage(I18n.Message.Command.ErrUsage.bpList)
            sender.sendMessage(I18n.Message.Command.ErrUsage.bpInfo)
            sender.sendMessage(I18n.Message.Command.ErrUsage.bpGive)
            return
        }

        when (args[0].lowercase()) {
            "list" -> handleBpList(sender)
            "info" -> handleBpInfo(sender, args.drop(1))
            "give" -> handleBpGive(sender, args.drop(1))
            else -> {
                sender.sendMessage(I18n.Message.Command.ErrUnknown.bp(args[0]))
                sender.sendMessage(I18n.Message.Command.ErrUnknown.availableBp)
            }
        }
    }

    private fun handleBpList(sender: CommandSender) {
        val blueprints = api.registry.getAll()

        if (blueprints.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.List.empty)
            sender.sendMessage(I18n.Message.Command.List.hint(importsDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.List.formats)
            return
        }

        sender.sendMessage(I18n.Message.Command.List.title(blueprints.size))
        blueprints.forEach { (id, blueprint) ->
            val rebarInfo = if (blueprint.controllerRebarKey != null) " [Rebar]" else ""
            sender.sendMessage(I18n.Message.Command.List.entry(
                id, "${blueprint.sizeX}x${blueprint.sizeY}x${blueprint.sizeZ}", blueprint.blockCount, rebarInfo))
        }
    }

    private fun handleBpInfo(sender: CommandSender, args: List<String>) {
        val blueprintId = args.getOrNull(0)
        if (blueprintId == null) {
            val rebarStatus = if (rebarModule.isAvailable())
                I18n.Message.Command.Info.rebarEnabled
            else
                I18n.Message.Command.Info.rebarDisabled

            sender.sendMessage(I18n.Message.Command.Info.title)
            sender.sendMessage(I18n.Message.Command.Info.version(Constants.PLUGIN_VERSION))
            sender.sendMessage(I18n.Message.Command.Info.registered(api.registry.size))
            sender.sendMessage(I18n.Message.Command.Info.importDir(importsDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.Info.blueprintDir(blueprintsDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.Info.productDir(productsDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.Info.formats)
            sender.sendMessage(I18n.Message.Command.Info.rebarIntegration(rebarStatus))
            return
        }

        val blueprint = api.registry.get(blueprintId)
        if (blueprint == null) {
            sender.sendMessage(I18n.Message.Command.Info.bpNotFound(blueprintId))
            return
        }
        sender.sendMessage(I18n.Message.Command.Info.bpTitle(blueprint.id))
        sender.sendMessage(I18n.Message.Command.Info.size(blueprint.sizeX, blueprint.sizeY, blueprint.sizeZ))
        sender.sendMessage(I18n.Message.Command.Info.blockCount(blueprint.blockCount))
        sender.sendMessage(I18n.Message.Command.Info.name(blueprint.meta.displayName))
        if (blueprint.meta.description.isNotEmpty()) {
            sender.sendMessage(I18n.Message.Command.Info.description(blueprint.meta.description))
        }
    }

    private fun handleBpGive(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission("monolithlib.blueprint")) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val blueprintId = args.getOrNull(0)
        if (blueprintId == null) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.bpGive)
            return
        }

        val blueprint = api.registry.get(blueprintId)
        if (blueprint == null) {
            sender.sendMessage(I18n.Message.Command.blueprintNotFound(blueprintId))
            return
        }

        if (blueprint.controllerRebarKey == null) {
            sender.sendMessage(I18n.Message.Command.Info.bpNotFound(blueprintId))
            return
        }

        val rebarItem = io.github.pylonmc.rebar.item.builder.ItemStackBuilder
            .rebar(Material.STRUCTURE_BLOCK, blueprint.controllerRebarKey)
            .build()
        sender.inventory.addItem(rebarItem)
        sender.sendMessage(I18n.Message.Command.Bp.given(blueprintId))
    }

    private fun handleSiteDomain(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.site)
            return
        }

        when (args[0].lowercase()) {
            "list" -> handleSiteList(sender)
            "info" -> handleSiteInfo(sender)
            "cancel" -> handleSiteCancel(sender)
            else -> sender.sendMessage(I18n.Message.Command.ErrUnknown.site(args[0]))
        }
    }

    private fun handleSiteList(sender: CommandSender) {
        val allSites = BuildSiteManager.getAllActiveSites()
        if (allSites.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.Site.noneNearby)
            return
        }
        sender.sendMessage(I18n.Message.Command.Site.listTitle(allSites.size))
        allSites.forEach { site ->
            val stateComp = when (site.state) {
                BuildSiteState.BUILDING -> I18n.Message.Command.Site.stateBuilding
                BuildSiteState.AWAITING_CORE -> I18n.Message.Command.Site.stateAwaiting
                BuildSiteState.VIRTUAL -> I18n.Message.Command.Site.stateVirtual
            }
            val pos = site.coreWorldPos
            sender.sendMessage(I18n.Message.Command.Site.entry(
                stateComp, site.blueprintId, pos.x, pos.y, pos.z
            ))
        }
    }

    private fun findNearestSite(player: Player, range: Double = 10.0): BuildSite? {
        val playerLoc = player.location
        val rangeSq = range * range
        var nearest: BuildSite? = null
        var nearestDistSq = Double.MAX_VALUE

        for (site in BuildSiteManager.getAllActiveSites()) {
            if (site.anchorLocation.world?.name != playerLoc.world?.name) continue
            if (site.isCompleted) continue

            val dx = playerLoc.x - site.anchorLocation.x
            val dy = playerLoc.y - site.anchorLocation.y
            val dz = playerLoc.z - site.anchorLocation.z
            val distSq = dx * dx + dy * dy + dz * dz

            if (distSq <= rangeSq && distSq < nearestDistSq) {
                nearest = site
                nearestDistSq = distSq
            }
        }

        return nearest
    }

    private fun handleSiteInfo(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        val site = findNearestSite(sender)
        if (site == null) {
            sender.sendMessage(I18n.Message.Command.Site.errNoneNearby)
            return
        }

        val pos = site.coreWorldPos
        sender.sendMessage(I18n.Message.Command.Site.infoTitle(site.blueprintId))
        sender.sendMessage(I18n.Message.Command.Site.infoState(site.state.name))
        sender.sendMessage(I18n.Message.Command.Site.infoPosition(pos.x, pos.y, pos.z))
        sender.sendMessage(I18n.Message.Command.Site.infoFacing(site.facing.name))
        if (site.isCompleted) {
            sender.sendMessage(I18n.Message.Command.Site.infoCompleted)
        } else {
            val totalBlocks = site.allGhostBlocks.count { !it.isCore }
            val placedCount = site.placedBlocks.size
            val pct = if (totalBlocks > 0) (placedCount * 100 / totalBlocks) else 0
            sender.sendMessage(I18n.Message.Command.Site.infoProgress(placedCount, totalBlocks, "${pct}%"))
        }
    }

    private fun handleSiteCancel(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        val site = findNearestSite(sender)
        if (site == null) {
            sender.sendMessage(I18n.Message.Command.Site.errNoneCancel)
            return
        }

        BuildSiteManager.removeSite(site.id)
        sender.sendMessage(I18n.Message.Command.Site.cancelled(site.blueprintId))
    }

    private fun handleEditDomain(sender: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.edit)
            sender.sendMessage(I18n.Message.Command.ErrUsage.editWand)
            sender.sendMessage(I18n.Message.Command.ErrUsage.editSave)
            sender.sendMessage(I18n.Message.Command.ErrUsage.editMerge)
            return
        }

        when (args[0].lowercase()) {
            "wand" -> handleEditWand(sender)
            "save" -> handleEditSave(sender, args.drop(1))
            "merge" -> handleEditMerge(sender, args.drop(1))
            else -> sender.sendMessage(I18n.Message.Command.ErrUnknown.edit(args[0]))
        }
    }

    private fun handleEditWand(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        sender.inventory.addItem(SelectionWand.STACK)
        sender.sendMessage(I18n.Message.Command.Edit.wandGiven)
    }

    private fun handleEditSave(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val name = args.getOrNull(0)
        if (name == null) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.editSave)
            return
        }

        val selection = SelectionManager.getSelection(sender)
        if (!selection.isComplete) {
            sender.sendMessage(I18n.Message.Command.Edit.noSelection)
            return
        }

        val isAssembled = args.any { it.equals("--assembled", ignoreCase = true) }
        val isScaffold = args.any { it.equals("--scaffold", ignoreCase = true) }

        sender.sendMessage(I18n.Message.Command.Edit.saveDev)
    }

    private fun handleEditMerge(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }

        val a = args.getOrNull(0)
        val b = args.getOrNull(1)
        if (a == null || b == null) {
            sender.sendMessage(I18n.Message.Command.ErrUsage.editMerge)
            return
        }

        val bpA = api.registry.get(a)
        val bpB = api.registry.get(b)
        if (bpA == null || bpB == null) {
            sender.sendMessage(I18n.Message.Command.blueprintNotFound(if (bpA == null) a else b))
            return
        }

        sender.sendMessage(I18n.Message.Command.Edit.mergeDev)
    }
}
