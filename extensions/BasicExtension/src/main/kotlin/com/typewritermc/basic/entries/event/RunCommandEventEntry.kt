package com.typewritermc.basic.entries.event

import dev.jorel.commandapi.CommandTree
import dev.jorel.commandapi.kotlindsl.playerExecutor
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.CustomCommandEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import com.typewritermc.engine.paper.targetOrSelfPlayer
import com.typewritermc.engine.paper.utils.msg
import dev.jorel.commandapi.kotlindsl.anyExecutor
import dev.jorel.commandapi.kotlindsl.argument
import dev.jorel.commandapi.kotlindsl.playerArgument
import org.bukkit.entity.Player

@Entry("on_run_command", "When a player runs a custom command", Colors.YELLOW, "mingcute:terminal-fill")
/**
 * The `Run Command Event` event is triggered when a command is run. This event can be used to add custom commands to the server.
 *
 * It allows any player to run the command.
 * And allows you to target a specific player if you have the permission `typewriter.<command>.other`.
 *
 * :::caution
 * This event is used for commands that **do not** already exist. If you are trying to detect when a player uses an already existing command, use the [`Detect Command Ran Event`](on_detect_command_ran) instead.
 * :::
 */
class RunCommandEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    override val command: String = "",
) : CustomCommandEntry {
    override fun CommandTree.builder() {
        playerExecutor { player, _ ->
            triggerAllFor(player, context())
        }
        playerArgument("target") {
            this.withPermission("typewriter.${command}.other")
            anyExecutor { sender, args ->
                val target = args["target"] as? Player
                if (target == null) {
                    sender.msg("Could not find player with name <green>${args.getRaw("target")}</green>")
                    return@anyExecutor
                }

                triggerAllFor(target, context())
                sender.msg("Triggered $command for <green>${target.name}</green>")
            }
        }
    }
}

