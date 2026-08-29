package de.emilo.spotifycontrol.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.emilo.spotifycontrol.SpotifyControl;
import de.emilo.spotifycontrol.config.ConfigManager;
import de.emilo.spotifycontrol.config.Lang;
import de.emilo.spotifycontrol.net.ServerNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import de.emilo.spotifycontrol.platform.Platform;

public final class SpotifyControlCommand {

    private SpotifyControlCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("spotifycontrol")
                    .then(Commands.literal("on")
                            .requires(Platform.permission(2))
                            .executes(SpotifyControlCommand::enable))
                    .then(Commands.literal("off")
                            .requires(Platform.permission(2))
                            .executes(SpotifyControlCommand::disable))
                    .then(Commands.literal("status")
                            .executes(SpotifyControlCommand::showStatus))
                    .then(Commands.literal("reload")
                            .requires(Platform.permission(2))
                            .executes(SpotifyControlCommand::reloadConfig))
                    .then(Commands.literal("party")
                            .then(Commands.literal("start")
                                    .executes(context -> startParty(context, "SYNC"))
                                    .then(Commands.literal("sync")
                                            .executes(context -> startParty(context, "SYNC")))
                                    .then(Commands.literal("broadcast")
                                            .executes(context -> startParty(context, "BROADCAST"))))
                            .then(Commands.literal("stop")
                                    .executes(SpotifyControlCommand::stopParty))
                            .then(Commands.literal("leave")
                                    .executes(SpotifyControlCommand::leaveParty))
                            .then(Commands.literal("join")
                                    .then(Commands.argument("host", EntityArgument.player())
                                            .executes(SpotifyControlCommand::joinParty)))
                            .then(Commands.literal("invite")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(SpotifyControlCommand::inviteToParty))));

            LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
            dispatcher.register(Commands.literal("sc").redirect(node));
        });
    }

    private static int enable(CommandContext<CommandSourceStack> context) {
        ConfigManager.server().enabled = true;
        ConfigManager.saveServer();
        broadcastSettings(context);
        context.getSource().sendSuccess(() -> Lang.chat("mod.enabled"), true);
        return 1;
    }

    private static int disable(CommandContext<CommandSourceStack> context) {
        ConfigManager.server().enabled = false;
        ConfigManager.saveServer();
        SpotifyControl.parties().shutdown();
        broadcastSettings(context);
        context.getSource().sendSuccess(() -> Lang.chat("mod.disabled"), true);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        String state = Lang.plain(ConfigManager.server().enabled ? "mod.state_on" : "mod.state_off");
        context.getSource().sendSuccess(() -> Lang.chat("mod.status",
                "state", state,
                "parties", SpotifyControl.parties().partyCount(),
                "listeners", SpotifyControl.parties().listenerCount(),
                "voicechat", SpotifyControl.voicechatPresent() ? "yes" : "no"), false);
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        ConfigManager.loadServer();
        broadcastSettings(context);
        context.getSource().sendSuccess(() -> Lang.chat("mod.reloaded"), true);
        return 1;
    }

    private static int startParty(CommandContext<CommandSourceStack> context, String mode) throws CommandSyntaxException {
        SpotifyControl.parties().start(context.getSource().getPlayerOrException(), mode);
        return 1;
    }

    private static int stopParty(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SpotifyControl.parties().stop(context.getSource().getPlayerOrException());
        return 1;
    }

    private static int leaveParty(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SpotifyControl.parties().leave(context.getSource().getPlayerOrException());
        return 1;
    }

    private static int joinParty(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer host = EntityArgument.getPlayer(context, "host");
        SpotifyControl.parties().join(context.getSource().getPlayerOrException(), Platform.playerName(host));
        return 1;
    }

    private static int inviteToParty(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        SpotifyControl.parties().invite(context.getSource().getPlayerOrException(),
                Platform.playerName(target));
        return 1;
    }

    private static void broadcastSettings(CommandContext<CommandSourceStack> context) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            ServerNetworking.broadcastSettings(player);
        }
    }
}
