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
import org.bukkit.inventory.ItemStack
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
import top.mc506lw.monolith.feature.buildsite.BuildSiteListener
import top.mc506lw.monolith.feature.buildsite.BuildSiteRegistry
import top.mc506lw.monolith.internal.listener.MonolithBlockListener
import top.mc506lw.monolith.internal.listener.RebarControllerListener
import top.mc506lw.monolith.internal.scheduler.TickScheduler
import top.mc506lw.monolith.internal.selection.SelectionManager
import top.mc506lw.monolith.internal.selection.SelectionWand
import top.mc506lw.monolith.internal.command.MonolithTabCompleter
import top.mc506lw.monolith.integration.MultiblockWrench
import top.mc506lw.monolith.integration.ProjectControllerRegistry
import top.mc506lw.monolith.integration.FormedStructureListener
import top.mc506lw.monolith.feature.buildsite.BuildSiteAnchorBlock
import top.mc506lw.monolith.feature.buildsite.BuildSiteAnchorItem
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

    val temDirectory: File
        get() = ioModule.temDirectory

    val worksDirectory: File
        get() = ioModule.worksDirectory

    val projectsDirectory: File
        get() = ioModule.projectsDirectory

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
        BuildSiteRegistry.cleanup()
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
            "easybuild" -> { handleBuildEasy(sender, args.drop(1)); true }
            "printer" -> { handleBuildPrinter(sender, args.drop(1)); true }
            "bp" -> { handleBlueprintDomain(sender, args.drop(1)); true }
            "site" -> { handleSiteDomain(sender, args.drop(1)); true }
            "wand" -> { handleEditWand(sender); true }
            "save" -> { handleVisionSave(sender, args.drop(1)); true }
            "merge" -> { handleMerge(sender, args.drop(1)); true }
            "project" -> { handleProjectDomain(sender, args.drop(1)); true }
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
        server.pluginManager.registerEvents(FormedStructureListener, this)
        server.pluginManager.registerEvents(buildSiteListener, this)
        server.pluginManager.registerEvents(top.mc506lw.monolith.feature.buildsite.EasyBuildManager, this)
    }

    private fun registerCommands() {
        getCommand("monolith")?.setExecutor(this)
        getCommand("monolith")?.tabCompleter = MonolithTabCompleter()
    }

    private fun initMachines() {
        try {
            VirtualDisplayAnchorRegistry.register()

            // 注册工地展位
            io.github.pylonmc.rebar.block.RebarBlock.register(
                BuildSiteAnchorBlock.KEY,
                BuildSiteAnchorBlock.MATERIAL,
                BuildSiteAnchorBlock::class.java
            )
            // 用最简单的空物品注册，实际发放用 BuildSiteAnchorItem.createItem()
            val protoItem = io.github.pylonmc.rebar.item.builder.ItemStackBuilder
                .rebar(BuildSiteAnchorBlock.MATERIAL, BuildSiteAnchorItem.KEY)
                .name(I18n.translatable("item.build_site_anchor.name"))
                .build()
            io.github.pylonmc.rebar.item.RebarItem.register(
                BuildSiteAnchorItem::class.java,
                protoItem,
                BuildSiteAnchorItem.KEY
            )
            moduleLogger.info { "Registered BuildSiteAnchor" }
        } catch (e: Exception) {
            moduleLogger.warn { "Anchor registration failed: ${e.message}" }
        }

        try {
            io.github.pylonmc.rebar.item.RebarItem.register(SelectionWand::class.java, SelectionWand.STACK, SelectionWand.KEY)
        } catch (e: Exception) {
            moduleLogger.warn { "Selection wand initialization failed: ${e.message}" }
        }

        try {
            io.github.pylonmc.rebar.item.RebarItem.register(
                MultiblockWrench::class.java,
                MultiblockWrench.STACK,
                MultiblockWrench.KEY
            )
            moduleLogger.info { "Registered MultiblockWrench" }
        } catch (e: Exception) {
            moduleLogger.warn { "MultiblockWrench initialization failed: ${e.message}" }
        }
    }

    private fun loadStructures() {
        val blueprints = ioModule.loadAllBlueprints()

        blueprints.forEach { blueprint ->
            ProjectControllerRegistry.ensureRegistered(blueprint)
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
        sender.sendMessage(H.sectionPreviewArg("easybuild [on|off]", "轻松放置模式"))
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
        sender.sendMessage(H.sectionPreviewArg("save <scaffold|assembled> <id> [--no-displays]", "保存阶段"))
        sender.sendMessage(H.sectionPreviewArg("merge <id>", "合并结构"))
        sender.sendMessage(H.sectionPreviewArg("project reload|test <id>", "热重载或发放完整测试工具包"))
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
            ProjectControllerRegistry.ensureRegistered(blueprint)
            api.registry.register(blueprint)
        }

        sender.sendMessage(I18n.Message.Command.Reload.complete(blueprints.size))
    }

    /** Reload one Works/<id>/Setting.yml without disturbing unrelated projects. */
    private fun handleProjectDomain(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }
        if (args.size < 2 || (!args[0].equals("reload", true) && !args[0].equals("test", true))) {
            sender.sendMessage(I18n.Message.Command.Project.usageReload)
            sender.sendMessage(I18n.Message.Command.Project.usageTest)
            return
        }
        val sourceId = args[1]
        val rebuilt = reloadProject(sourceId)
        if (rebuilt == null) {
            sender.sendMessage(I18n.Message.Command.blueprintNotFound(sourceId))
            return
        }
        sender.sendMessage(I18n.Message.Command.Project.reloaded(rebuilt.id, "Works/$sourceId/Setting.yml"))
        if (args[0].equals("test", true)) {
            if (sender !is Player) {
                sender.sendMessage(I18n.Message.Command.playerOnly)
                return
            }
            sender.inventory.addItem(BuildSiteAnchorItem.createItem(rebuilt.id))
            sender.inventory.addItem(MultiblockWrench.STACK.clone())
            rebuilt.scaffoldShape.blocks
                .asSequence()
                .filter { it.position != rebuilt.meta.controllerOffset }
                .groupingBy { it.blockData.material }
                .eachCount()
                .forEach { (material, count) ->
                    var remaining = count
                    while (remaining > 0) {
                        val amount = minOf(remaining, material.maxStackSize)
                        sender.inventory.addItem(ItemStack(material, amount))
                        remaining -= amount
                    }
                }
            sender.sendMessage(I18n.Message.Command.Project.testKitGranted)
        } else {
            sender.sendMessage(I18n.Message.Command.Project.formedUnchanged)
        }
    }

    fun reloadProject(id: String): Blueprint? {
        val rebuilt = ioModule.rebuildProject(id) ?: return null
        ProjectControllerRegistry.ensureRegistered(rebuilt)
        api.registry.register(rebuilt)
        BuildSiteRegistry.refreshBlueprint(id, rebuilt.id)
        if (rebuilt.id != id) api.registry.remove(id)
        return rebuilt
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

        val result = when (args.firstOrNull()?.lowercase()) {
            "on" -> top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.enableEasyBuild(sender)
            "off" -> top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.disableEasyBuild(sender)
            else -> top.mc506lw.monolith.feature.buildsite.EasyBuildManager.toggle(sender)
        }

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

        val result = when (args.firstOrNull()?.lowercase()) {
            "on" -> top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.enablePrinter(sender)
            "off" -> top.mc506lw.monolith.feature.buildsite.LitematicaModeManager.disablePrinter(sender)
            else -> top.mc506lw.monolith.feature.buildsite.PrinterManager.toggle(sender)
        }

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
            sender.sendMessage(I18n.Message.Command.List.hint(projectsDirectory.absolutePath))
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
            sender.sendMessage(I18n.Message.Command.Info.importDir(temDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.Info.blueprintDir(worksDirectory.absolutePath))
            sender.sendMessage(I18n.Message.Command.Info.productDir(projectsDirectory.absolutePath))
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
        sender.sendMessage(I18n.Message.Command.Info.stageBlocks(
            blueprint.scaffoldShape.blocks.size,
            blueprint.assembledShape.blocks.size
        ))
        val blockDisplays = blueprint.displayEntities.count {
            it.entityType == top.mc506lw.monolith.core.model.DisplayType.BLOCK
        }
        val itemDisplays = blueprint.displayEntities.size - blockDisplays
        sender.sendMessage(I18n.Message.Command.Info.displays(
            blueprint.displayEntities.size,
            blockDisplays,
            itemDisplays,
            blueprint.displayEntities.map { it.group.ifBlank { "default" } }.toSet().size
        ))
        val controllerKey = blueprint.controllerRebarKey
        sender.sendMessage(I18n.Message.Command.Info.controller(
            controllerKey?.toString() ?: "none",
            if (top.mc506lw.monolith.integration.ProjectControllerRegistry.isRegistered(blueprint)) "yes" else "no"
        ))
        val strategy = when (blueprint.formStrategy) {
            is top.mc506lw.monolith.core.model.FormStrategy.BlockOnly -> "block_only"
            is top.mc506lw.monolith.core.model.FormStrategy.FullDisplay -> "full_display"
            is top.mc506lw.monolith.core.model.FormStrategy.Hybrid -> "hybrid"
        }
        sender.sendMessage(I18n.Message.Command.Info.strategy(strategy))
        sender.sendMessage(I18n.Message.Command.Info.offsets(
            blueprint.meta.controllerOffset.toString(),
            blueprint.meta.displayOffset.toString()
        ))
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

        // 给予工地展位方块（取代旧蓝图纸）
        val anchorItem = BuildSiteAnchorItem.createItem(blueprintId)
        sender.inventory.addItem(anchorItem)
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
        val allSites = BuildSiteRegistry.all()
        if (allSites.isEmpty()) {
            sender.sendMessage(I18n.Message.Command.Site.noneNearby)
            return
        }
        sender.sendMessage(I18n.Message.Command.Site.listTitle(allSites.size))
        allSites.forEach { site ->
            val pos = site.block.location
            sender.sendMessage(I18n.Message.Command.Site.entry(
                I18n.Message.Command.Site.stateBuilding, site.blueprintId ?: "unknown", pos.blockX, pos.blockY, pos.blockZ
            ))
        }
    }

    /** 查找附近 [BuildSiteAnchorBlock]（新系统） */
    private fun findNearestAnchor(player: Player, range: Double = 10.0): BuildSiteAnchorBlock? {
        return BuildSiteRegistry.nearest(player, range)
    }

    private fun handleSiteInfo(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        val anchor = findNearestAnchor(sender)
        if (anchor != null) {
            val bp = anchor.blueprint
            val bpId = bp?.id ?: anchor.blueprintId ?: "unknown"
            val pos = anchor.block.location
            val rate = (anchor.getCompletionRate() * 100).toInt()
            sender.sendMessage(I18n.Message.Command.Site.infoTitle(bpId))
            sender.sendMessage(I18n.Message.Command.Site.infoState("建造中"))
            sender.sendMessage(I18n.Message.Command.Site.infoPosition(pos.blockX, pos.blockY, pos.blockZ))
            sender.sendMessage(I18n.Message.Command.Site.infoFacing(anchor.facing.name))
            sender.sendMessage(I18n.Message.Command.Site.infoProgress(rate, 100, "$rate%"))
            return
        }

        sender.sendMessage(I18n.Message.Command.Site.errNoneNearby)
    }

    private fun handleSiteCancel(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }

        val anchor = findNearestAnchor(sender)
        if (anchor != null) {
            val bpId = anchor.blueprint?.id ?: "unknown"
            io.github.pylonmc.rebar.block.BlockStorage.breakBlock(
                anchor.block,
                io.github.pylonmc.rebar.block.context.BlockBreakContext.PluginBreak(
                    anchor.block, normallyDrops = false, shouldSetToAir = true
                )
            )
            sender.sendMessage(I18n.Message.Command.Site.cancelled(bpId))
            return
        }

        sender.sendMessage(I18n.Message.Command.Site.errNoneCancel)
    }

    private fun handleVisionSave(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            sender.sendMessage(I18n.Message.Command.playerOnly)
            return
        }
        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }
        val stage = args.getOrNull(0)?.lowercase()
        val id = args.getOrNull(1)
        val option = args.getOrNull(2)?.lowercase()
        if (stage !in setOf("scaffold", "assembled") || id.isNullOrBlank()
            || (option != null && option != "--no-displays") || args.size > 3
        ) {
            sender.sendMessage(I18n.Message.Command.Edit.saveUsage)
            return
        }
        val selection = SelectionManager.getSelection(sender)
        if (!selection.isComplete) {
            sender.sendMessage(I18n.Message.Command.Edit.noSelection)
            return
        }
        val buildStage = if (stage == "scaffold")
            top.mc506lw.monolith.core.model.BuildStage.SCAFFOLD
        else
            top.mc506lw.monolith.core.model.BuildStage.ASSEMBLED
        val scan = top.mc506lw.monolith.feature.editor.SelectionScanner.captureShape(
            selection,
            captureDisplay = option != "--no-displays"
        )
        if (scan == null) {
            sender.sendMessage(I18n.Message.Command.Edit.saveFailed("扫描选区失败"))
            return
        }
        val file = ioModule.saveStage(id, buildStage, scan.shape, scan.displayEntities, scan.controllerOffset)
        if (file == null) {
            sender.sendMessage(I18n.Message.Command.Edit.saveFailed("写入阶段文件失败"))
            return
        }
        sender.sendMessage(I18n.Message.Command.Edit.savedWithPath(
            stage ?: "unknown",
            scan.shape.blocks.size,
            scan.displayEntities.size,
            file.absolutePath
        ))
    }

    private fun handleMerge(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(Constants.Permissions.EDIT)) {
            sender.sendMessage(I18n.Message.Command.permissionDenied)
            return
        }
        val id = args.firstOrNull()
        if (id.isNullOrBlank()) {
            sender.sendMessage(I18n.Message.Command.Edit.mergeUsage)
            return
        }
        val blueprint = ioModule.mergeStages(id).getOrElse { error ->
            sender.sendMessage(I18n.Message.Command.Edit.errMergeFailed(error.message ?: "两阶段数据无效"))
            return
        }
        sender.sendMessage(I18n.Message.Command.Edit.merged(blueprint.blockCount, File(worksDirectory, "$id/$id.mnb").absolutePath))
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

}
