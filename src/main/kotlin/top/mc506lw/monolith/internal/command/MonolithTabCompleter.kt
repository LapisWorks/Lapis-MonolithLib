package top.mc506lw.monolith.internal.command

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import top.mc506lw.monolith.api.MonolithAPI
import top.mc506lw.monolith.MonolithLib
import java.io.File

class MonolithTabCompleter : TabCompleter {

    companion object {
        private val DOMAINS = listOf("preview", "build", "easybuild", "printer", "bp", "site", "wand", "save", "merge", "project", "reload")
        
        val BUILD_SUBS = listOf("here", "printer")
        val BP_SUBS = listOf("list", "info", "give")
        val SITE_SUBS = listOf("list", "info", "cancel")
        
        val TOGGLE_OPTIONS = listOf("on", "off")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (!command.name.equals("monolith", ignoreCase = true)) return null
        
        return when (args.size) {
            1 -> filter(args[0], DOMAINS)
            2 -> filterSecondLevel(sender, args)
            3 -> filterThirdLevel(args)
            4 -> filterFourthLevel(args)
            else -> mutableListOf()
        }
    }

    private fun filterSecondLevel(sender: CommandSender, args: Array<out String>): MutableList<String> {
        return when (args[0].lowercase()) {
            "preview" -> filterPreviewSecondLevel(args[1])
            "build" -> filter(args[1], BUILD_SUBS)
            "bp" -> filter(args[1], BP_SUBS)
            "site" -> filter(args[1], SITE_SUBS)
            "save" -> filter(args[1], listOf("scaffold", "assembled"))
            "merge" -> filterScaffoldIds(args[1])
            "project" -> filter(args[1], listOf("reload", "test"))
            "easybuild", "printer" -> filter(args[1], TOGGLE_OPTIONS)
            else -> mutableListOf()
        }
    }

    private fun filterPreviewSecondLevel(input: String): MutableList<String> {
        val lowerInput = input.lowercase()
        val results = mutableListOf<String>()
        results.addAll(filterBlueprintIds(lowerInput))
        if ("stop".startsWith(lowerInput)) results.add("stop")
        return results
    }

    private fun filterThirdLevel(args: Array<out String>): MutableList<String> {
        val domain = args[0].lowercase()
        val subCmd = args[1].lowercase()
        
        return when {
            domain == "build" && subCmd == "here" -> filterBlueprintIds(args[2])
            domain == "build" && subCmd == "printer" -> filter(args[2], TOGGLE_OPTIONS)
            
            domain == "bp" && (subCmd == "info" || subCmd == "give") -> filterBlueprintIds(args[2])
            
            domain == "save" && subCmd == "scaffold" -> filterScaffoldIds(args[2])
            domain == "save" && subCmd == "assembled" -> filterAssembledIds(args[2])
            domain == "merge" -> filterMergeIds(args[2])
            domain == "project" && (subCmd == "reload" || subCmd == "test") -> filterBlueprintIds(args[2])
            
            else -> mutableListOf()
        }
    }

    private fun filterFourthLevel(args: Array<out String>): MutableList<String> =
        if (args[0].equals("save", true) && args[1].lowercase() in setOf("scaffold", "assembled")) {
            filter(args[3], listOf("--no-displays"))
        } else {
            mutableListOf()
        }

    private fun filter(input: String, candidates: List<String>): MutableList<String> {
        val lowerInput = input.lowercase()
        return candidates.filter { it.startsWith(lowerInput) }.toMutableList()
    }

    private fun filterBlueprintIds(input: String): MutableList<String> {
        val lowerInput = input.lowercase()
        return try {
            MonolithAPI.getInstance().registry.getAll().keys
                .filter { it.startsWith(lowerInput) }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /** IDs with a scaffold stage file already saved (can scaffold-save again or merge). */
    private fun filterScaffoldIds(input: String): MutableList<String> = stageIds("scaffold", input)

    /** IDs with a scaffold file, so the assembled stage can be recorded after it. */
    private fun filterAssembledIds(input: String): MutableList<String> = stageIds("scaffold", input)

    /** IDs that have BOTH scaffold and assembled stage files. */
    private fun filterMergeIds(input: String): MutableList<String> {
        val lowerInput = input.lowercase()
        val dir = temDirectory() ?: return mutableListOf()
        val scaffold = stageFileIds(dir, "scaffold")
        val assembled = stageFileIds(dir, "assembled")
        return scaffold.intersect(assembled).filter { it.startsWith(lowerInput) }.toMutableList()
    }

    private fun stageIds(stage: String, input: String): MutableList<String> {
        val lowerInput = input.lowercase()
        val dir = temDirectory() ?: return mutableListOf()
        return stageFileIds(dir, stage).filter { it.startsWith(lowerInput) }.toMutableList()
    }

    private fun stageFileIds(dir: File, stage: String): Set<String> =
        dir.listFiles()?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".mnb.$stage") }
            ?.map { it.name.removeSuffix(".mnb.$stage") }
            ?.toSet()
            ?: emptySet()

    private fun temDirectory(): File? = try {
        MonolithLib.instance.temDirectory
    } catch (_: Exception) {
        null
    }
}
