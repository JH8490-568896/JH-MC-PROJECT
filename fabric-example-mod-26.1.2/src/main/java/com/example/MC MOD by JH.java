Content is user-generated and unverified.
package com.servermanager;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameModeArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ServerManager implements ModInitializer {

    public static final String MOD_ID = "servermanager";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static DatabaseManager database;
    public static PermissionManager permissions;
    public static HomeManager homes;
    public static WarpManager warps;
    public static ModerationManager moderation;
    public static TpaManager tpa;
    public static NickManager nicks;
    public static IgnoreManager ignores;
    public static SpeedManager speeds;
    public static FlyManager flyManager;
    public static ScoreboardManager scoreboard;
    public static StaffModeManager staffMode;
    public static VanishManager vanish;
    public static FreezeManager freeze;
    public static MailManager mail;
    public static PlaytimeManager playtime;
    public static ConfigManager config;

    public static final ExecutorService ASYNC = Executors.newFixedThreadPool(4);
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        LOGGER.info("[ServerManager] Initializing...");

        config      = new ConfigManager();
        database    = new DatabaseManager();
        permissions = new PermissionManager();
        homes       = new HomeManager();
        warps       = new WarpManager();
        moderation  = new ModerationManager();
        tpa         = new TpaManager();
        nicks       = new NickManager();
        ignores     = new IgnoreManager();
        speeds      = new SpeedManager();
        flyManager  = new FlyManager();
        scoreboard  = new ScoreboardManager();
        staffMode   = new StaffModeManager();
        vanish      = new VanishManager();
        freeze      = new FreezeManager();
        mail        = new MailManager();
        playtime    = new PlaytimeManager();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerAllCommands(dispatcher);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            database.init();
            scoreboard.onServerStart(server);
            LOGGER.info("[ServerManager] Server started — all systems online.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            database.shutdown();
            ASYNC.shutdown();
            LOGGER.info("[ServerManager] Shutting down.");
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            permissions.loadPlayer(player.getUuidAsString());
            playtime.onJoin(player.getUuidAsString());
            vanish.applyVanishToJoiner(player, server);
            String welcomeMsg = config.get("welcome_message", "&aWelcome, &e{player}&a!");
            player.sendMessage(Text.literal(colorize(welcomeMsg.replace("{player}", player.getName().getString()))), false);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            playtime.onLeave(player.getUuidAsString());
            tpa.cancelAll(player.getUuidAsString());
            freeze.unfreeze(player.getUuidAsString());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            scoreboard.onTick(server);
        });

        LOGGER.info("[ServerManager] Ready!");
    }

    private void registerAllCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> d) {
        // SPAWN
        d.register(CommandManager.literal("spawn")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                var spawn = p.getServerWorld().getSpawnPos();
                p.teleport(p.getServerWorld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
                p.sendMessage(Text.literal(colorize("&aTeleported to spawn!")), false);
                return 1;
            }));

        // HOME
        d.register(CommandManager.literal("home")
            .executes(ctx -> homes.goHome(ctx, "home"))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> homes.goHome(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("sethome")
            .executes(ctx -> homes.setHome(ctx, "home"))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> homes.setHome(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("delhome")
            .executes(ctx -> homes.delHome(ctx, "home"))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> homes.delHome(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("homes")
            .executes(ctx -> homes.listHomes(ctx)));

        // WARP
        d.register(CommandManager.literal("warp")
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> warps.goWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("setwarp")
            .requires(src -> hasPermission(src, "sm.setwarp"))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> warps.setWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("delwarp")
            .requires(src -> hasPermission(src, "sm.delwarp"))
            .then(CommandManager.argument("name", StringArgumentType.word())
                .executes(ctx -> warps.delWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(CommandManager.literal("warps")
            .executes(ctx -> warps.listWarps(ctx)));

        // TPA
        d.register(CommandManager.literal("tpa")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> tpa.sendRequest(ctx, EntityArgumentType.getPlayer(ctx, "player")))));

        d.register(CommandManager.literal("tpaccept")
            .executes(ctx -> tpa.accept(ctx)));

        d.register(CommandManager.literal("tpdeny")
            .executes(ctx -> tpa.deny(ctx)));

        // BACK
        d.register(CommandManager.literal("back")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                var pos = homes.getLastPos(p.getUuidAsString());
                if (pos == null) { p.sendMessage(Text.literal(colorize("&cNo previous location.")), false); return 0; }
                p.teleport(p.getServerWorld(), pos[0], pos[1], pos[2], p.getYaw(), p.getPitch());
                p.sendMessage(Text.literal(colorize("&aTeleported back!")), false);
                return 1;
            }));

        // MSG / REPLY
        d.register(CommandManager.literal("msg")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayerEntity sender = ctx.getSource().getPlayer();
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        String msg = StringArgumentType.getString(ctx, "message");
                        if (sender == null) return 0;
                        if (ignores.isIgnoring(target.getUuidAsString(), sender.getUuidAsString())) {
                            sender.sendMessage(Text.literal(colorize("&cThat player is ignoring you.")), false);
                            return 0;
                        }
                        String formatted = colorize("&7[&f" + sender.getName().getString() + " &7-> &f" + target.getName().getString() + "&7] &f" + msg);
                        sender.sendMessage(Text.literal(formatted), false);
                        target.sendMessage(Text.literal(formatted), false);
                        ignores.setLastMsg(sender.getUuidAsString(), target.getUuidAsString());
                        ignores.setLastMsg(target.getUuidAsString(), sender.getUuidAsString());
                        return 1;
                    }))));

        d.register(CommandManager.literal("reply")
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity sender = ctx.getSource().getPlayer();
                    if (sender == null) return 0;
                    String targetId = ignores.getLastMsg(sender.getUuidAsString());
                    if (targetId == null) { sender.sendMessage(Text.literal(colorize("&cNo one to reply to.")), false); return 0; }
                    ServerPlayerEntity target = SERVER.getPlayerManager().getPlayer(UUID.fromString(targetId));
                    if (target == null) { sender.sendMessage(Text.literal(colorize("&cPlayer is offline.")), false); return 0; }
                    String msg = StringArgumentType.getString(ctx, "message");
                    String formatted = colorize("&7[&f" + sender.getName().getString() + " &7-> &f" + target.getName().getString() + "&7] &f" + msg);
                    sender.sendMessage(Text.literal(formatted), false);
                    target.sendMessage(Text.literal(formatted), false);
                    return 1;
                })));

        // IGNORE
        d.register(CommandManager.literal("ignore")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    if (p == null) return 0;
                    ignores.toggle(p.getUuidAsString(), target.getUuidAsString());
                    boolean nowIgnoring = ignores.isIgnoring(p.getUuidAsString(), target.getUuidAsString());
                    p.sendMessage(Text.literal(colorize(nowIgnoring
                        ? "&eNow ignoring &f" + target.getName().getString()
                        : "&eUnignored &f" + target.getName().getString())), false);
                    return 1;
                })));

        // BROADCAST
        d.register(CommandManager.literal("broadcast")
            .requires(src -> hasPermission(src, "sm.broadcast"))
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String msg = StringArgumentType.getString(ctx, "message");
                    String formatted = colorize("&c[Broadcast] &f" + msg);
                    SERVER.getPlayerManager().getPlayerList().forEach(pl ->
                        pl.sendMessage(Text.literal(formatted), false));
                    return 1;
                })));

        // STAFFCHAT
        d.register(CommandManager.literal("staffchat")
            .requires(src -> hasPermission(src, "sm.staffchat"))
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    String msg = StringArgumentType.getString(ctx, "message");
                    String formatted = colorize("&b[Staff] &f" + p.getName().getString() + "&7: &f" + msg);
                    SERVER.getPlayerManager().getPlayerList().stream()
                        .filter(pl -> hasPermission(pl.getCommandSource(), "sm.staffchat"))
                        .forEach(pl -> pl.sendMessage(Text.literal(formatted), false));
                    return 1;
                })));

        // HELPOP
        d.register(CommandManager.literal("helpop")
            .then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    String msg = StringArgumentType.getString(ctx, "message");
                    String formatted = colorize("&e[HelpOP] &f" + p.getName().getString() + "&7: &f" + msg);
                    SERVER.getPlayerManager().getPlayerList().stream()
                        .filter(pl -> hasPermission(pl.getCommandSource(), "sm.staffchat"))
                        .forEach(pl -> pl.sendMessage(Text.literal(formatted), false));
                    p.sendMessage(Text.literal(colorize("&aYour message has been sent to staff.")), false);
                    return 1;
                })));

        // CLEARCHAT
        d.register(CommandManager.literal("clearchat")
            .requires(src -> hasPermission(src, "sm.clearchat"))
            .executes(ctx -> {
                for (int i = 0; i < 100; i++) {
                    SERVER.getPlayerManager().getPlayerList().forEach(pl ->
                        pl.sendMessage(Text.literal(" "), false));
                }
                SERVER.getPlayerManager().getPlayerList().forEach(pl ->
                    pl.sendMessage(Text.literal(colorize("&aChat has been cleared.")), false));
                return 1;
            }));

        // MAIL
        d.register(CommandManager.literal("mail")
            .then(CommandManager.literal("send")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayerEntity sender = ctx.getSource().getPlayer();
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            if (sender == null) return 0;
                            String msg = StringArgumentType.getString(ctx, "message");
                            mail.send(sender.getUuidAsString(), sender.getName().getString(), target.getUuidAsString(), msg);
                            sender.sendMessage(Text.literal(colorize("&aMail sent to &f" + target.getName().getString())), false);
                            return 1;
                        }))))
            .then(CommandManager.literal("read")
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    mail.readMail(p);
                    return 1;
                }))
            .then(CommandManager.literal("clear")
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    mail.clearMail(p.getUuidAsString());
                    p.sendMessage(Text.literal(colorize("&aMailbox cleared.")), false);
                    return 1;
                })));

        // KICK
        d.register(CommandManager.literal("kick")
            .requires(src -> hasPermission(src, "sm.kick"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> moderation.kick(ctx, EntityArgumentType.getPlayer(ctx, "player"), "Kicked by staff"))
                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> moderation.kick(ctx, EntityArgumentType.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "reason"))))));

        // BAN
        d.register(CommandManager.literal("ban")
            .requires(src -> hasPermission(src, "sm.ban"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> moderation.ban(ctx, EntityArgumentType.getPlayer(ctx, "player"), "Banned", -1))
                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> moderation.ban(ctx, EntityArgumentType.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "reason"), -1)))));

        // TEMPBAN
        d.register(CommandManager.literal("tempban")
            .requires(src -> hasPermission(src, "sm.ban"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1))
                    .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> moderation.ban(ctx, EntityArgumentType.getPlayer(ctx, "player"),
                            StringArgumentType.getString(ctx, "reason"), IntegerArgumentType.getInteger(ctx, "minutes")))))));

        // MUTE
        d.register(CommandManager.literal("mute")
            .requires(src -> hasPermission(src, "sm.mute"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> moderation.mute(ctx, EntityArgumentType.getPlayer(ctx, "player"), -1))
                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1))
                    .executes(ctx -> moderation.mute(ctx, EntityArgumentType.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "minutes"))))));

        // UNMUTE
        d.register(CommandManager.literal("unmute")
            .requires(src -> hasPermission(src, "sm.mute"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    moderation.unmute(target.getUuidAsString());
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aUnmuted &f" + target.getName().getString())), true);
                    return 1;
                })));

        // WARN
        d.register(CommandManager.literal("warn")
            .requires(src -> hasPermission(src, "sm.warn"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> moderation.warn(ctx, EntityArgumentType.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "reason"))))));

        // HISTORY
        d.register(CommandManager.literal("history")
            .requires(src -> hasPermission(src, "sm.history"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> moderation.history(ctx, EntityArgumentType.getPlayer(ctx, "player")))));

        // FREEZE
        d.register(CommandManager.literal("freeze")
            .requires(src -> hasPermission(src, "sm.freeze"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    boolean frozen = freeze.toggle(target.getUuidAsString());
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                        frozen ? "&b" + target.getName().getString() + " &ahas been frozen."
                               : "&b" + target.getName().getString() + " &ahas been unfrozen.")), true);
                    if (frozen) target.sendMessage(Text.literal(colorize("&cYou have been frozen by staff!")), false);
                    else        target.sendMessage(Text.literal(colorize("&aYou have been unfrozen.")), false);
                    return 1;
                })));

        // VANISH
        d.register(CommandManager.literal("vanish")
            .requires(src -> hasPermission(src, "sm.vanish"))
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                boolean isVanished = vanish.toggle(p, SERVER);
                p.sendMessage(Text.literal(colorize(isVanished ? "&aYou are now vanished." : "&cYou are no longer vanished.")), false);
                return 1;
            }));

        // STAFFMODE
        d.register(CommandManager.literal("staffmode")
            .requires(src -> hasPermission(src, "sm.staffmode"))
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                boolean active = staffMode.toggle(p);
                p.sendMessage(Text.literal(colorize(active ? "&aStaff mode &eENABLED." : "&cStaff mode &eDISABLED.")), false);
                return 1;
            }));

        // INVSEE
        d.register(CommandManager.literal("invsee")
            .requires(src -> hasPermission(src, "sm.invsee"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    ServerPlayerEntity staff  = ctx.getSource().getPlayer();
                    if (staff == null) return 0;
                    staff.sendMessage(Text.literal(colorize("&b--- Inventory of &f" + target.getName().getString() + " &b---")), false);
                    for (int i = 0; i < target.getInventory().size(); i++) {
                        var stack = target.getInventory().getStack(i);
                        if (!stack.isEmpty()) {
                            staff.sendMessage(Text.literal(colorize("&7Slot " + i + ": &f" + stack.getCount() + "x " + stack.getItem().toString())), false);
                        }
                    }
                    return 1;
                })));

        // SUDO
        d.register(CommandManager.literal("sudo")
            .requires(src -> hasPermission(src, "sm.sudo"))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        String cmd = StringArgumentType.getString(ctx, "command");
                        SERVER.getCommandManager().executeWithPrefix(target.getCommandSource(), cmd);
                        ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aSudoed &f" + target.getName().getString() + "&a: &f" + cmd)), true);
                        return 1;
                    }))));

        // PING
        d.register(CommandManager.literal("ping")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                int ping = p.networkHandler.getLatency();
                p.sendMessage(Text.literal(colorize("&aPing: &f" + ping + "ms")), false);
                return 1;
            }));

        // LAG
        d.register(CommandManager.literal("lag")
            .executes(ctx -> {
                double tps = Math.min(20.0, 1000.0 / Math.max(1, SERVER.getAverageTickTime()));
                String color = tps > 18 ? "&a" : tps > 15 ? "&e" : "&c";
                ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                    "&eTPS: " + color + String.format("%.2f", tps) +
                    " &e| Tick Time: &f" + String.format("%.2fms", SERVER.getAverageTickTime()) +
                    " &e| Players: &f" + SERVER.getPlayerManager().getCurrentPlayerCount())), false);
                return 1;
            }));

        // SERVERSTATS
        d.register(CommandManager.literal("serverstats")
            .requires(src -> hasPermission(src, "sm.stats"))
            .executes(ctx -> {
                Runtime rt = Runtime.getRuntime();
                long used  = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                long total = rt.totalMemory() / 1024 / 1024;
                long max   = rt.maxMemory() / 1024 / 1024;
                double tps = Math.min(20.0, 1000.0 / Math.max(1, SERVER.getAverageTickTime()));
                ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                    "&b--- Server Stats ---\n" +
                    "&eTPS: &f" + String.format("%.2f", tps) + "\n" +
                    "&eMemory: &f" + used + "MB / " + total + "MB (Max: " + max + "MB)\n" +
                    "&ePlayers: &f" + SERVER.getPlayerManager().getCurrentPlayerCount() + " / " + SERVER.getPlayerManager().getMaxPlayerCount() + "\n" +
                    "&eWorlds: &f" + SERVER.getWorldRegistryKeys().size())), false);
                return 1;
            }));

        // PLAYTIME
        d.register(CommandManager.literal("playtime")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                long minutes = playtime.getPlaytime(p.getUuidAsString());
                long hours   = minutes / 60;
                long mins    = minutes % 60;
                p.sendMessage(Text.literal(colorize("&aYour playtime: &f" + hours + "h " + mins + "m")), false);
                return 1;
            })
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .requires(src -> hasPermission(src, "sm.playtime.other"))
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    long minutes = playtime.getPlaytime(target.getUuidAsString());
                    long hours   = minutes / 60;
                    long mins    = minutes % 60;
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                        "&f" + target.getName().getString() + "&a's playtime: &f" + hours + "h " + mins + "m")), false);
                    return 1;
                })));

        // FEED
        d.register(CommandManager.literal("feed")
            .requires(src -> hasPermission(src, "sm.feed"))
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                p.setHunger(20, 5.0f);
                p.sendMessage(Text.literal(colorize("&aYou have been fed!")), false);
                return 1;
            })
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    target.setHunger(20, 5.0f);
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aFed &f" + target.getName().getString())), true);
                    target.sendMessage(Text.literal(colorize("&aYou have been fed by staff!")), false);
                    return 1;
                })));

        // HEAL
        d.register(CommandManager.literal("heal")
            .requires(src -> hasPermission(src, "sm.heal"))
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                p.setHealth(p.getMaxHealth());
                p.sendMessage(Text.literal(colorize("&aYou have been healed!")), false);
                return 1;
            })
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    target.setHealth(target.getMaxHealth());
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aHealed &f" + target.getName().getString())), true);
                    target.sendMessage(Text.literal(colorize("&aYou have been healed by staff!")), false);
                    return 1;
                })));

        // FLY
        d.register(CommandManager.literal("fly")
            .requires(src -> hasPermission(src, "sm.fly"))
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                boolean fl = flyManager.toggle(p);
                p.sendMessage(Text.literal(colorize(fl ? "&aFly enabled." : "&cFly disabled.")), false);
                return 1;
            })
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    boolean fl = flyManager.toggle(target);
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                        fl ? "&eFly enabled for &f" + target.getName().getString()
                           : "&eFly disabled for &f" + target.getName().getString())), true);
                    return 1;
                })));

        // SPEED
        d.register(CommandManager.literal("speed")
            .requires(src -> hasPermission(src, "sm.speed"))
            .then(CommandManager.argument("speed", IntegerArgumentType.integer(0, 10))
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    int spd = IntegerArgumentType.getInteger(ctx, "speed");
                    speeds.setSpeed(p, spd);
                    p.sendMessage(Text.literal(colorize("&aSpeed set to &f" + spd)), false);
                    return 1;
                })));

        // GAMEMODE
        d.register(CommandManager.literal("gm")
            .requires(src -> hasPermission(src, "sm.gamemode"))
            .then(CommandManager.argument("mode", GameModeArgumentType.gameMode())
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    GameMode mode = GameModeArgumentType.getGameMode(ctx, "mode");
                    p.changeGameMode(mode);
                    p.sendMessage(Text.literal(colorize("&aGamemode set to &f" + mode.getName())), false);
                    return 1;
                })
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(ctx -> {
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        GameMode mode = GameModeArgumentType.getGameMode(ctx, "mode");
                        target.changeGameMode(mode);
                        ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                            "&aSet &f" + target.getName().getString() + "&a's gamemode to &f" + mode.getName())), true);
                        return 1;
                    }))));

        // NICK
        d.register(CommandManager.literal("nick")
            .requires(src -> hasPermission(src, "sm.nick"))
            .then(CommandManager.argument("nickname", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    String nick = StringArgumentType.getString(ctx, "nickname");
                    nicks.setNick(p.getUuidAsString(), nick);
                    p.sendMessage(Text.literal(colorize("&aNickname set to: " + colorize(nick))), false);
                    return 1;
                }))
            .then(CommandManager.literal("reset")
                .executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) return 0;
                    nicks.clearNick(p.getUuidAsString());
                    p.sendMessage(Text.literal(colorize("&aNickname reset.")), false);
                    return 1;
                })));

        // SUICIDE
        d.register(CommandManager.literal("suicide")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                p.kill(SERVER);
                return 1;
            }));

        // RULES
        d.register(CommandManager.literal("rules")
            .executes(ctx -> {
                String rules = config.get("rules", "&b--- Server Rules ---\n&f1. Be respectful\n2. No cheating\n3. No griefing\n4. Have fun!");
                ctx.getSource().sendFeedback(() -> Text.literal(colorize(rules)), false);
                return 1;
            }));

        // DISCORD
        d.register(CommandManager.literal("discord")
            .executes(ctx -> {
                String link = config.get("discord_link", "https://discord.gg/yourserver");
                ctx.getSource().sendFeedback(() -> Text.literal(colorize("&bDiscord: &f" + link)), false);
                return 1;
            }));

        // SEEN
        d.register(CommandManager.literal("seen")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    long pt = playtime.getPlaytime(target.getUuidAsString());
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize(
                        "&f" + target.getName().getString() + " &ais online. Playtime: &f" + (pt / 60) + "h " + (pt % 60) + "m")), false);
                    return 1;
                })));

        // REPORT
        d.register(CommandManager.literal("report")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayerEntity reporter = ctx.getSource().getPlayer();
                        ServerPlayerEntity target   = EntityArgumentType.getPlayer(ctx, "player");
                        if (reporter == null) return 0;
                        String reason = StringArgumentType.getString(ctx, "reason");
                        moderation.addReport(reporter.getUuidAsString(), reporter.getName().getString(),
                            target.getUuidAsString(), target.getName().getString(), reason);
                        reporter.sendMessage(Text.literal(colorize("&aReport submitted against &f" + target.getName().getString())), false);
                        String alert = colorize("&c[Report] &f" + reporter.getName().getString() +
                            " &creported &f" + target.getName().getString() + "&c: &f" + reason);
                        SERVER.getPlayerManager().getPlayerList().stream()
                            .filter(pl -> hasPermission(pl.getCommandSource(), "sm.staffchat"))
                            .forEach(pl -> pl.sendMessage(Text.literal(alert), false));
                        return 1;
                    }))));

        // REPORTS
        d.register(CommandManager.literal("reports")
            .requires(src -> hasPermission(src, "sm.reports"))
            .executes(ctx -> { moderation.listReports(ctx); return 1; }));

        // BALANCE
        d.register(CommandManager.literal("balance")
            .executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p == null) return 0;
                double bal = database.getBalance(p.getUuidAsString());
                p.sendMessage(Text.literal(colorize("&aBalance: &f$" + String.format("%.2f", bal))), false);
                return 1;
            }));

        // PAY
        d.register(CommandManager.literal("pay")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        ServerPlayerEntity sender = ctx.getSource().getPlayer();
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        if (sender == null) return 0;
                        int amount = IntegerArgumentType.getInteger(ctx, "amount");
                        if (database.getBalance(sender.getUuidAsString()) < amount) {
                            sender.sendMessage(Text.literal(colorize("&cInsufficient funds.")), false);
                            return 0;
                        }
                        database.addBalance(sender.getUuidAsString(), -amount);
                        database.addBalance(target.getUuidAsString(), amount);
                        sender.sendMessage(Text.literal(colorize("&aPaid &f$" + amount + " &ato &f" + target.getName().getString())), false);
                        target.sendMessage(Text.literal(colorize("&aReceived &f$" + amount + " &afrom &f" + sender.getName().getString())), false);
                        return 1;
                    }))));

        // ECONOMY
        d.register(CommandManager.literal("economy")
            .requires(src -> hasPermission(src, "sm.economy"))
            .then(CommandManager.literal("give")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            database.addBalance(target.getUuidAsString(), amount);
                            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aGave &f$" + amount + " &ato &f" + target.getName().getString())), true);
                            return 1;
                        }))))
            .then(CommandManager.literal("take")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            database.addBalance(target.getUuidAsString(), -amount);
                            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aTook &f$" + amount + " &afrom &f" + target.getName().getString())), true);
                            return 1;
                        }))))
            .then(CommandManager.literal("set")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            database.setBalance(target.getUuidAsString(), amount);
                            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aSet &f" + target.getName().getString() + "&a's balance to &f$" + amount)), true);
                            return 1;
                        })))));

        // RANK
        d.register(CommandManager.literal("rank")
            .requires(src -> hasPermission(src, "sm.rank"))
            .then(CommandManager.literal("set")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            String group = StringArgumentType.getString(ctx, "group");
                            permissions.setGroup(target.getUuidAsString(), group);
                            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aSet &f" + target.getName().getString() + "&a's rank to &f" + group)), true);
                            return 1;
                        }))))
            .then(CommandManager.literal("get")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(ctx -> {
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        String group = permissions.getGroup(target.getUuidAsString());
                        ctx.getSource().sendFeedback(() -> Text.literal(colorize("&f" + target.getName().getString() + "&a's rank: &f" + group)), false);
                        return 1;
                    }))));

        // MAINTENANCE
        d.register(CommandManager.literal("maintenance")
            .requires(src -> hasPermission(src, "sm.maintenance"))
            .then(CommandManager.literal("on")
                .executes(ctx -> {
                    config.set("maintenance", "true");
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize("&cMaintenance mode &eON.")), true);
                    return 1;
                }))
            .then(CommandManager.literal("off")
                .executes(ctx -> {
                    config.set("maintenance", "false");
                    ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aMaintenance mode &eOFF.")), true);
                    return 1;
                })));
    }

    public static boolean hasPermission(ServerCommandSource src, String node) {
        if (src.hasPermissionLevel(4)) return true;
        if (src.getPlayer() == null) return true;
        return permissions.hasPermission(src.getPlayer().getUuidAsString(), node);
    }

    public static String colorize(String msg) {
        return msg
            .replace("&0", "\u00A70").replace("&1", "\u00A71").replace("&2", "\u00A72")
            .replace("&3", "\u00A73").replace("&4", "\u00A74").replace("&5", "\u00A75")
            .replace("&6", "\u00A76").replace("&7", "\u00A77").replace("&8", "\u00A78")
            .replace("&9", "\u00A79").replace("&a", "\u00A7a").replace("&b", "\u00A7b")
            .replace("&c", "\u00A7c").replace("&d", "\u00A7d").replace("&e", "\u00A7e")
            .replace("&f", "\u00A7f").replace("&l", "\u00A7l").replace("&n", "\u00A7n")
            .replace("&o", "\u00A7o").replace("&r", "\u00A7r").replace("&k", "\u00A7k")
            .replace("&m", "\u00A7m");
    }

    // ============================================================
    // CONFIG MANAGER
    // ============================================================
    public static class ConfigManager {
        private final Map<String, String> data = new ConcurrentHashMap<>();
        private final Path configFile;

        public ConfigManager() {
            configFile = Paths.get("config/servermanager.properties");
            load();
        }

        public void load() {
            try {
                Files.createDirectories(configFile.getParent());
                if (!Files.exists(configFile)) {
                    Properties p = new Properties();
                    p.setProperty("welcome_message", "&aWelcome, &e{player}&a!");
                    p.setProperty("discord_link",    "https://discord.gg/yourserver");
                    p.setProperty("rules",           "&b--- Server Rules ---\n&f1. Be respectful\n2. No cheating\n3. No griefing");
                    p.setProperty("maintenance",     "false");
                    p.setProperty("db_type",         "sqlite");
                    p.setProperty("db_host",         "localhost");
                    p.setProperty("db_port",         "3306");
                    p.setProperty("db_name",         "servermanager");
                    p.setProperty("db_user",         "root");
                    p.setProperty("db_pass",         "password");
                    try (Writer w = Files.newBufferedWriter(configFile)) { p.store(w, "ServerManager Config"); }
                }
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(configFile)) { p.load(r); }
                p.forEach((k, v) -> data.put(k.toString(), v.toString()));
            } catch (IOException e) {
                LOGGER.error("[Config] Failed to load config.", e);
            }
        }

        public String get(String key, String def) { return data.getOrDefault(key, def); }
        public void set(String key, String val) {
            data.put(key, val);
            try {
                Properties p = new Properties();
                data.forEach(p::setProperty);
                try (Writer w = Files.newBufferedWriter(configFile)) { p.store(w, "ServerManager Config"); }
            } catch (IOException e) { LOGGER.error("[Config] Save failed.", e); }
        }
    }

    // ============================================================
    // DATABASE MANAGER
    // ============================================================
    public static class DatabaseManager {
        public Connection conn;

        public void init() {
            try {
                String type = config.get("db_type", "sqlite");
                if (type.equalsIgnoreCase("mysql")) {
                    String url = "jdbc:mysql://" + config.get("db_host","localhost") + ":" +
                        config.get("db_port","3306") + "/" + config.get("db_name","servermanager") +
                        "?autoReconnect=true&useSSL=false";
                    conn = DriverManager.getConnection(url, config.get("db_user","root"), config.get("db_pass","password"));
                } else {
                    Files.createDirectories(Paths.get("data/servermanager"));
                    conn = DriverManager.getConnection("jdbc:sqlite:data/servermanager/data.db");
                }
                createTables();
                LOGGER.info("[Database] Connected ({})", type);
            } catch (Exception e) {
                LOGGER.error("[Database] Connection failed.", e);
            }
        }

        private void createTables() throws SQLException {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT, group_name TEXT DEFAULT 'Player', balance REAL DEFAULT 0, playtime INTEGER DEFAULT 0)");
                s.execute("CREATE TABLE IF NOT EXISTS homes (uuid TEXT, name TEXT, world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL, PRIMARY KEY(uuid, name))");
                s.execute("CREATE TABLE IF NOT EXISTS warps (name TEXT PRIMARY KEY, world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL)");
                s.execute("CREATE TABLE IF NOT EXISTS punishments (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, type TEXT, reason TEXT, staff TEXT, duration INTEGER DEFAULT -1, issued INTEGER, active INTEGER DEFAULT 1)");
                s.execute("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter_uuid TEXT, reporter_name TEXT, target_uuid TEXT, target_name TEXT, reason TEXT, timestamp INTEGER, resolved INTEGER DEFAULT 0)");
                s.execute("CREATE TABLE IF NOT EXISTS mail (id INTEGER PRIMARY KEY AUTOINCREMENT, from_uuid TEXT, from_name TEXT, to_uuid TEXT, message TEXT, timestamp INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS nicks (uuid TEXT PRIMARY KEY, nick TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS ignores (uuid TEXT, ignored TEXT, PRIMARY KEY(uuid, ignored))");
            }
        }

        public double getBalance(String uuid) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
                ps.setString(1, uuid); ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getDouble(1) : 0;
            } catch (SQLException e) { LOGGER.error("[DB] getBalance failed", e); return 0; }
        }

        public void addBalance(String uuid, double amount) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET balance=balance+?")) {
                    ps.setString(1, uuid); ps.setDouble(2, amount); ps.setDouble(3, amount); ps.execute();
                } catch (SQLException e) { LOGGER.error("[DB] addBalance failed", e); }
            });
        }

        public void setBalance(String uuid, double amount) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET balance=?")) {
                    ps.setString(1, uuid); ps.setDouble(2, amount); ps.setDouble(3, amount); ps.execute();
                } catch (SQLException e) { LOGGER.error("[DB] setBalance failed", e); }
            });
        }

        public String getGroup(String uuid) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT group_name FROM players WHERE uuid=?")) {
                ps.setString(1, uuid); ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getString(1) : "Player";
            } catch (SQLException e) { LOGGER.error("[DB] getGroup failed", e); return "Player"; }
        }

        public void setGroup(String uuid, String group) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players(uuid,group_name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET group_name=?")) {
                    ps.setString(1, uuid); ps.setString(2, group); ps.setString(3, group); ps.execute();
                } catch (SQLException e) { LOGGER.error("[DB] setGroup failed", e); }
            });
        }

        public void shutdown() {
            try { if (conn != null && !conn.isClosed()) conn.close(); }
            catch (SQLException e) { LOGGER.error("[DB] Shutdown error", e); }
        }
    }

    // ============================================================
    // PERMISSION MANAGER
    // ============================================================
    public static class PermissionManager {
        private static final Map<String, Integer> GROUP_WEIGHTS = new LinkedHashMap<>();
        private static final Map<String, Set<String>> GROUP_PERMS = new HashMap<>();
        private final Map<String, String> playerGroups = new ConcurrentHashMap<>();

        public PermissionManager() {
            GROUP_WEIGHTS.put("Guest",       0);
            GROUP_WEIGHTS.put("Player",      1);
            GROUP_WEIGHTS.put("VIP",         2);
            GROUP_WEIGHTS.put("MVP",         3);
            GROUP_WEIGHTS.put("Helper",      4);
            GROUP_WEIGHTS.put("Moderator",   5);
            GROUP_WEIGHTS.put("SeniorAdmin", 6);
            GROUP_WEIGHTS.put("Admin",       7);
            GROUP_WEIGHTS.put("Owner",       8);

            define("Player",      "sm.home","sm.warp","sm.tpa","sm.msg","sm.playtime","sm.fly");
            define("VIP",         "sm.nick","sm.fly","sm.feed","sm.heal");
            define("MVP",         "sm.speed","sm.gamemode");
            define("Helper",      "sm.kick","sm.mute","sm.warn","sm.freeze","sm.staffchat","sm.vanish","sm.reports","sm.history","sm.invsee","sm.staffmode");
            define("Moderator",   "sm.ban","sm.tempban","sm.broadcast","sm.clearchat","sm.sudo","sm.setwarp","sm.delwarp");
            define("SeniorAdmin", "sm.rank","sm.economy","sm.stats","sm.maintenance","sm.playtime.other");
            define("Admin",       "*");
            define("Owner",       "*");
        }

        private void define(String group, String... perms) {
            GROUP_PERMS.computeIfAbsent(group, k -> new HashSet<>()).addAll(Arrays.asList(perms));
        }

        public void loadPlayer(String uuid) {
            ASYNC.submit(() -> {
                String group = database.getGroup(uuid);
                playerGroups.put(uuid, group);
            });
        }

        public boolean hasPermission(String uuid, String node) {
            String group = playerGroups.getOrDefault(uuid, "Player");
            int weight   = GROUP_WEIGHTS.getOrDefault(group, 1);
            for (Map.Entry<String, Integer> e : GROUP_WEIGHTS.entrySet()) {
                if (e.getValue() <= weight) {
                    Set<String> perms = GROUP_PERMS.getOrDefault(e.getKey(), Collections.emptySet());
                    if (perms.contains("*") || perms.contains(node)) return true;
                }
            }
            return false;
        }

        public String getGroup(String uuid) { return playerGroups.getOrDefault(uuid, "Player"); }
        public void setGroup(String uuid, String group) {
            playerGroups.put(uuid, group);
            database.setGroup(uuid, group);
        }
    }

    // ============================================================
    // HOME MANAGER
    // ============================================================
    public static class HomeManager {
        private final Map<String, double[]> lastPos = new ConcurrentHashMap<>();

        public int setHome(CommandContext<ServerCommandSource> ctx, String name) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            double x = p.getX(), y = p.getY(), z = p.getZ();
            float yaw = p.getYaw(), pitch = p.getPitch();
            String world = p.getServerWorld().getRegistryKey().getValue().toString();
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT OR REPLACE INTO homes(uuid,name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, p.getUuidAsString()); ps.setString(2, name);
                    ps.setString(3, world); ps.setDouble(4, x); ps.setDouble(5, y);
                    ps.setDouble(6, z); ps.setFloat(7, yaw); ps.setFloat(8, pitch); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Homes] setHome failed", e); }
            });
            p.sendMessage(Text.literal(colorize("&aHome &f" + name + " &aset!")), false);
            return 1;
        }

        public int goHome(CommandContext<ServerCommandSource> ctx, String name) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            try (PreparedStatement ps = database.conn.prepareStatement(
                "SELECT world,x,y,z,yaw,pitch FROM homes WHERE uuid=? AND name=?")) {
                ps.setString(1, p.getUuidAsString()); ps.setString(2, name);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { p.sendMessage(Text.literal(colorize("&cHome &f" + name + " &cnot found.")), false); return 0; }
                double x = rs.getDouble(2), y = rs.getDouble(3), z = rs.getDouble(4);
                float yaw = rs.getFloat(5), pitch = rs.getFloat(6);
                lastPos.put(p.getUuidAsString(), new double[]{p.getX(), p.getY(), p.getZ()});
                p.teleport(p.getServerWorld(), x, y, z, yaw, pitch);
                p.sendMessage(Text.literal(colorize("&aTeleported to home &f" + name + "&a!")), false);
            } catch (SQLException e) { LOGGER.error("[Homes] goHome failed", e); }
            return 1;
        }

        public int delHome(CommandContext<ServerCommandSource> ctx, String name) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement("DELETE FROM homes WHERE uuid=? AND name=?")) {
                    ps.setString(1, p.getUuidAsString()); ps.setString(2, name); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Homes] delHome failed", e); }
            });
            p.sendMessage(Text.literal(colorize("&aHome &f" + name + " &adeleted.")), false);
            return 1;
        }

        public int listHomes(CommandContext<ServerCommandSource> ctx) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            try (PreparedStatement ps = database.conn.prepareStatement("SELECT name FROM homes WHERE uuid=?")) {
                ps.setString(1, p.getUuidAsString());
                ResultSet rs = ps.executeQuery();
                List<String> names = new ArrayList<>();
                while (rs.next()) names.add(rs.getString(1));
                p.sendMessage(Text.literal(colorize("&aHomes: &f" + String.join(", ", names))), false);
            } catch (SQLException e) { LOGGER.error("[Homes] listHomes failed", e); }
            return 1;
        }

        public double[] getLastPos(String uuid) { return lastPos.get(uuid); }
    }

    // ============================================================
    // WARP MANAGER
    // ============================================================
    public static class WarpManager {
        public int setWarp(CommandContext<ServerCommandSource> ctx, String name) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            String world = p.getServerWorld().getRegistryKey().getValue().toString();
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT OR REPLACE INTO warps(name,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, name); ps.setString(2, world);
                    ps.setDouble(3, p.getX()); ps.setDouble(4, p.getY()); ps.setDouble(5, p.getZ());
                    ps.setFloat(6, p.getYaw()); ps.setFloat(7, p.getPitch()); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Warps] setWarp failed", e); }
            });
            p.sendMessage(Text.literal(colorize("&aWarp &f" + name + " &acreated!")), false);
            return 1;
        }

        public int goWarp(CommandContext<ServerCommandSource> ctx, String name) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) return 0;
            try (PreparedStatement ps = database.conn.prepareStatement("SELECT x,y,z,yaw,pitch FROM warps WHERE name=?")) {
                ps.setString(1, name); ResultSet rs = ps.executeQuery();
                if (!rs.next()) { p.sendMessage(Text.literal(colorize("&cWarp &f" + name + " &cnot found.")), false); return 0; }
                p.teleport(p.getServerWorld(), rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getFloat(4), rs.getFloat(5));
                p.sendMessage(Text.literal(colorize("&aTeleported to &f" + name + "&a!")), false);
            } catch (SQLException e) { LOGGER.error("[Warps] goWarp failed", e); }
            return 1;
        }

        public int delWarp(CommandContext<ServerCommandSource> ctx, String name) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement("DELETE FROM warps WHERE name=?")) {
                    ps.setString(1, name); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Warps] delWarp failed", e); }
            });
            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aWarp &f" + name + " &adeleted.")), true);
            return 1;
        }

        public int listWarps(CommandContext<ServerCommandSource> ctx) {
            try (PreparedStatement ps = database.conn.prepareStatement("SELECT name FROM warps")) {
                ResultSet rs = ps.executeQuery();
                List<String> names = new ArrayList<>();
                while (rs.next()) names.add(rs.getString(1));
                ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aWarps: &f" + String.join(", ", names))), false);
            } catch (SQLException e) { LOGGER.error("[Warps] listWarps failed", e); }
            return 1;
        }
    }

    // ============================================================
    // TPA MANAGER
    // ============================================================
    public static class TpaManager {
        private final Map<String, String> requests   = new ConcurrentHashMap<>();
        private final Map<String, String> pendingFor = new ConcurrentHashMap<>();
        private static final long EXPIRE_MS = 30_000;

        public int sendRequest(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target) {
            ServerPlayerEntity sender = ctx.getSource().getPlayer();
            if (sender == null) return 0;
            if (sender.getUuid().equals(target.getUuid())) {
                sender.sendMessage(Text.literal(colorize("&cYou can't TPA to yourself.")), false); return 0;
            }
            requests.put(sender.getUuidAsString(), target.getUuidAsString());
            pendingFor.put(target.getUuidAsString(), sender.getUuidAsString());
            sender.sendMessage(Text.literal(colorize("&aTPA request sent to &f" + target.getName().getString())), false);
            target.sendMessage(Text.literal(colorize("&f" + sender.getName().getString() + " &awants to TP to you. &e/tpaccept &aor &e/tpdeny")), false);
            final String senderUUID = sender.getUuidAsString();
            final String targetUUID = target.getUuidAsString();
            ASYNC.submit(() -> {
                try { Thread.sleep(EXPIRE_MS); } catch (InterruptedException ignored) {}
                if (requests.containsKey(senderUUID)) {
                    requests.remove(senderUUID); pendingFor.remove(targetUUID);
                    ServerPlayerEntity s = SERVER.getPlayerManager().getPlayer(UUID.fromString(senderUUID));
                    if (s != null) s.sendMessage(Text.literal(colorize("&cTPA request expired.")), false);
                }
            });
            return 1;
        }

        public int accept(CommandContext<ServerCommandSource> ctx) {
            ServerPlayerEntity target = ctx.getSource().getPlayer();
            if (target == null) return 0;
            String requesterId = pendingFor.remove(target.getUuidAsString());
            if (requesterId == null) { target.sendMessage(Text.literal(colorize("&cNo pending TPA request.")), false); return 0; }
            requests.remove(requesterId);
            ServerPlayerEntity requester = SERVER.getPlayerManager().getPlayer(UUID.fromString(requesterId));
            if (requester == null) { target.sendMessage(Text.literal(colorize("&cRequester went offline.")), false); return 0; }
            requester.teleport(target.getServerWorld(), target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
            target.sendMessage(Text.literal(colorize("&aTPA accepted.")), false);
            requester.sendMessage(Text.literal(colorize("&aTeleporting to &f" + target.getName().getString())), false);
            return 1;
        }

        public int deny(CommandContext<ServerCommandSource> ctx) {
            ServerPlayerEntity target = ctx.getSource().getPlayer();
            if (target == null) return 0;
            String requesterId = pendingFor.remove(target.getUuidAsString());
            if (requesterId == null) { target.sendMessage(Text.literal(colorize("&cNo pending TPA.")), false); return 0; }
            requests.remove(requesterId);
            target.sendMessage(Text.literal(colorize("&cTPA denied.")), false);
            ServerPlayerEntity requester = SERVER.getPlayerManager().getPlayer(UUID.fromString(requesterId));
            if (requester != null) requester.sendMessage(Text.literal(colorize("&cYour TPA was denied.")), false);
            return 1;
        }

        public void cancelAll(String uuid) { requests.remove(uuid); pendingFor.remove(uuid); }
    }

    // ============================================================
    // MODERATION MANAGER
    // ============================================================
    public static class ModerationManager {
        private final Set<String> mutedPlayers = ConcurrentHashMap.newKeySet();
        private final Map<String, Long> mutedUntil = new ConcurrentHashMap<>();

        public int kick(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target, String reason) {
            logPunishment(target.getUuidAsString(), "KICK", reason, ctx.getSource().getName(), -1);
            target.networkHandler.disconnect(Text.literal(colorize("&cKicked: &f" + reason)));
            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aKicked &f" + target.getName().getString())), true);
            return 1;
        }

        public int ban(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target, String reason, int minutes) {
            logPunishment(target.getUuidAsString(), minutes > 0 ? "TEMPBAN" : "BAN", reason, ctx.getSource().getName(), minutes);
            String msg = minutes > 0
                ? colorize("&cTempbanned for &f" + minutes + " min&c: &f" + reason)
                : colorize("&cBanned: &f" + reason);
            target.networkHandler.disconnect(Text.literal(msg));
            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aBanned &f" + target.getName().getString())), true);
            return 1;
        }

        public int mute(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target, int minutes) {
            mutedPlayers.add(target.getUuidAsString());
            if (minutes > 0) mutedUntil.put(target.getUuidAsString(), System.currentTimeMillis() + (minutes * 60_000L));
            logPunishment(target.getUuidAsString(), minutes > 0 ? "TEMPMUTE" : "MUTE", "Muted", ctx.getSource().getName(), minutes);
            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aMuted &f" + target.getName().getString())), true);
            target.sendMessage(Text.literal(colorize("&cYou have been muted.")), false);
            return 1;
        }

        public void unmute(String uuid) { mutedPlayers.remove(uuid); mutedUntil.remove(uuid); }

        public boolean isMuted(String uuid) {
            Long until = mutedUntil.get(uuid);
            if (until != null && System.currentTimeMillis() > until) { mutedPlayers.remove(uuid); mutedUntil.remove(uuid); return false; }
            return mutedPlayers.contains(uuid);
        }

        public int warn(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target, String reason) {
            logPunishment(target.getUuidAsString(), "WARN", reason, ctx.getSource().getName(), -1);
            ctx.getSource().sendFeedback(() -> Text.literal(colorize("&aWarned &f" + target.getName().getString())), true);
            target.sendMessage(Text.literal(colorize("&c[Warning] &f" + reason)), false);
            return 1;
        }

        public int history(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target) {
            try (PreparedStatement ps = database.conn.prepareStatement(
                "SELECT type,reason,staff,issued FROM punishments WHERE uuid=? ORDER BY issued DESC LIMIT 10")) {
                ps.setString(1, target.getUuidAsString()); ResultSet rs = ps.executeQuery();
                ctx.getSource().sendFeedback(() -> Text.literal(colorize("&b--- History: &f" + target.getName().getString() + " &b---")), false);
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yy HH:mm");
                while (rs.next()) {
                    String line = colorize("&e" + rs.getString(1) + " &7by &f" + rs.getString(3) +
                        " &7(" + sdf.format(new Date(rs.getLong(4) * 1000)) + "): &f" + rs.getString(2));
                    ctx.getSource().sendFeedback(() -> Text.literal(line), false);
                }
            } catch (SQLException e) { LOGGER.error("[Moderation] history failed", e); }
            return 1;
        }

        public void addReport(String rUUID, String rName, String tUUID, String tName, String reason) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT INTO reports(reporter_uuid,reporter_name,target_uuid,target_name,reason,timestamp) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, rUUID); ps.setString(2, rName); ps.setString(3, tUUID);
                    ps.setString(4, tName); ps.setString(5, reason); ps.setLong(6, System.currentTimeMillis() / 1000); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Moderation] addReport failed", e); }
            });
        }

        public int listReports(CommandContext<ServerCommandSource> ctx) {
            try (PreparedStatement ps = database.conn.prepareStatement(
                "SELECT reporter_name,target_name,reason FROM reports WHERE resolved=0 ORDER BY timestamp DESC LIMIT 10")) {
                ResultSet rs = ps.executeQuery();
                ctx.getSource().sendFeedback(() -> Text.literal(colorize("&b--- Open Reports ---")), false);
                while (rs.next()) {
                    String line = colorize("&f" + rs.getString(1) + " &7reported &f" + rs.getString(2) + "&7: &f" + rs.getString(3));
                    ctx.getSource().sendFeedback(() -> Text.literal(line), false);
                }
            } catch (SQLException e) { LOGGER.error("[Moderation] listReports failed", e); }
            return 1;
        }

        private void logPunishment(String uuid, String type, String reason, String staff, int duration) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT INTO punishments(uuid,type,reason,staff,duration,issued) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, uuid); ps.setString(2, type); ps.setString(3, reason);
                    ps.setString(4, staff); ps.setInt(5, duration); ps.setLong(6, System.currentTimeMillis() / 1000); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Moderation] log failed", e); }
            });
        }
    }

    // ============================================================
    // NICK MANAGER
    // ============================================================
    public static class NickManager {
        private final Map<String, String> nicks = new ConcurrentHashMap<>();

        public void setNick(String uuid, String nick) {
            nicks.put(uuid, nick);
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement("INSERT OR REPLACE INTO nicks(uuid,nick) VALUES(?,?)")) {
                    ps.setString(1, uuid); ps.setString(2, nick); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Nicks] set failed", e); }
            });
        }

        public void clearNick(String uuid) {
            nicks.remove(uuid);
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement("DELETE FROM nicks WHERE uuid=?")) {
                    ps.setString(1, uuid); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Nicks] clear failed", e); }
            });
        }

        public String getNick(String uuid) { return nicks.get(uuid); }
    }

    // ============================================================
    // IGNORE MANAGER
    // ============================================================
    public static class IgnoreManager {
        private final Set<String> ignoreSet = ConcurrentHashMap.newKeySet();
        private final Map<String, String> lastMsgMap = new ConcurrentHashMap<>();

        private String key(String a, String b) { return a + ":" + b; }

        public void toggle(String uuid, String target) {
            String k = key(uuid, target);
            if (ignoreSet.contains(k)) {
                ignoreSet.remove(k);
                ASYNC.submit(() -> {
                    try (PreparedStatement ps = database.conn.prepareStatement("DELETE FROM ignores WHERE uuid=? AND ignored=?")) {
                        ps.setString(1, uuid); ps.setString(2, target); ps.execute();
                    } catch (SQLException e) { LOGGER.error("[Ignore] remove failed", e); }
                });
            } else {
                ignoreSet.add(k);
                ASYNC.submit(() -> {
                    try (PreparedStatement ps = database.conn.prepareStatement("INSERT OR IGNORE INTO ignores(uuid,ignored) VALUES(?,?)")) {
                        ps.setString(1, uuid); ps.setString(2, target); ps.execute();
                    } catch (SQLException e) { LOGGER.error("[Ignore] add failed", e); }
                });
            }
        }

        public boolean isIgnoring(String uuid, String target) { return ignoreSet.contains(key(uuid, target)); }
        public void setLastMsg(String uuid, String target) { lastMsgMap.put(uuid, target); }
        public String getLastMsg(String uuid) { return lastMsgMap.get(uuid); }
    }

    // ============================================================
    // SPEED MANAGER
    // ============================================================
    public static class SpeedManager {
        public void setSpeed(ServerPlayerEntity p, int level) {
            float speed = level / 10.0f * 0.2f;
            p.getAbilities().walkSpeed = speed;
            p.getAbilities().flySpeed  = speed * 2;
            p.sendAbilitiesUpdate();
        }
    }

    // ============================================================
    // FLY MANAGER
    // ============================================================
    public static class FlyManager {
        private final Set<String> flying = ConcurrentHashMap.newKeySet();

        public boolean toggle(ServerPlayerEntity p) {
            String uuid = p.getUuidAsString();
            if (flying.contains(uuid)) {
                flying.remove(uuid);
                p.getAbilities().allowFlying = false;
                p.getAbilities().flying      = false;
                p.sendAbilitiesUpdate();
                return false;
            } else {
                flying.add(uuid);
                p.getAbilities().allowFlying = true;
                p.sendAbilitiesUpdate();
                return true;
            }
        }
    }

    // ============================================================
    // FREEZE MANAGER
    // ============================================================
    public static class FreezeManager {
        private final Set<String> frozen = ConcurrentHashMap.newKeySet();
        public boolean toggle(String uuid) {
            if (frozen.contains(uuid)) { frozen.remove(uuid); return false; }
            frozen.add(uuid); return true;
        }
        public boolean isFrozen(String uuid) { return frozen.contains(uuid); }
        public void unfreeze(String uuid) { frozen.remove(uuid); }
    }

    // ============================================================
    // VANISH MANAGER
    // ============================================================
    public static class VanishManager {
        private final Set<String> vanished = ConcurrentHashMap.newKeySet();

        public boolean toggle(ServerPlayerEntity p, MinecraftServer server) {
            String uuid = p.getUuidAsString();
            if (vanished.contains(uuid)) {
                vanished.remove(uuid);
                server.getPlayerManager().getPlayerList().forEach(other -> {
                    if (!other.getUuid().equals(p.getUuid()))
                        other.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(
                            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.ADD_PLAYER, List.of(p)));
                });
                return false;
            } else {
                vanished.add(uuid);
                server.getPlayerManager().getPlayerList().stream()
                    .filter(other -> !hasPermission(other.getCommandSource(), "sm.vanish") && !other.getUuid().equals(p.getUuid()))
                    .forEach(other -> other.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(
                            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.REMOVE_PLAYER, List.of(p))));
                return true;
            }
        }

        public void applyVanishToJoiner(ServerPlayerEntity newPlayer, MinecraftServer server) {
            if (!hasPermission(newPlayer.getCommandSource(), "sm.vanish")) {
                vanished.forEach(uuid -> {
                    ServerPlayerEntity vp = server.getPlayerManager().getPlayer(UUID.fromString(uuid));
                    if (vp != null) newPlayer.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(
                            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.REMOVE_PLAYER, List.of(vp)));
                });
            }
        }
    }

    // ============================================================
    // STAFF MODE MANAGER
    // ============================================================
    public static class StaffModeManager {
        private final Set<String> inStaffMode = ConcurrentHashMap.newKeySet();
        private final Map<String, GameMode> savedGameModes = new ConcurrentHashMap<>();

        public boolean toggle(ServerPlayerEntity p) {
            String uuid = p.getUuidAsString();
            if (inStaffMode.contains(uuid)) {
                inStaffMode.remove(uuid);
                p.changeGameMode(savedGameModes.getOrDefault(uuid, GameMode.SURVIVAL));
                return false;
            } else {
                inStaffMode.add(uuid);
                savedGameModes.put(uuid, p.interactionManager.getGameMode());
                p.changeGameMode(GameMode.CREATIVE);
                return true;
            }
        }
    }

    // ============================================================
    // MAIL MANAGER
    // ============================================================
    public static class MailManager {
        public void send(String fromUUID, String fromName, String toUUID, String message) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT INTO mail(from_uuid,from_name,to_uuid,message,timestamp) VALUES(?,?,?,?,?)")) {
                    ps.setString(1, fromUUID); ps.setString(2, fromName);
                    ps.setString(3, toUUID); ps.setString(4, message);
                    ps.setLong(5, System.currentTimeMillis() / 1000); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Mail] send failed", e); }
            });
        }

        public void readMail(ServerPlayerEntity p) {
            try (PreparedStatement ps = database.conn.prepareStatement(
                "SELECT from_name,message,timestamp FROM mail WHERE to_uuid=? ORDER BY timestamp DESC LIMIT 10")) {
                ps.setString(1, p.getUuidAsString()); ResultSet rs = ps.executeQuery();
                p.sendMessage(Text.literal(colorize("&b--- Mailbox ---")), false);
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    p.sendMessage(Text.literal(colorize("&e" + sdf.format(new Date(rs.getLong(3) * 1000)) +
                        " &7from &f" + rs.getString(1) + "&7: &f" + rs.getString(2))), false);
                }
                if (!any) p.sendMessage(Text.literal(colorize("&7No mail.")), false);
            } catch (SQLException e) { LOGGER.error("[Mail] read failed", e); }
        }

        public void clearMail(String uuid) {
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement("DELETE FROM mail WHERE to_uuid=?")) {
                    ps.setString(1, uuid); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Mail] clear failed", e); }
            });
        }
    }

    // ============================================================
    // PLAYTIME MANAGER
    // ============================================================
    public static class PlaytimeManager {
        private final Map<String, Long> joinTimes = new ConcurrentHashMap<>();

        public void onJoin(String uuid) { joinTimes.put(uuid, System.currentTimeMillis()); }

        public void onLeave(String uuid) {
            Long joinTime = joinTimes.remove(uuid);
            if (joinTime == null) return;
            long minutes = (System.currentTimeMillis() - joinTime) / 60_000;
            if (minutes < 1) return;
            ASYNC.submit(() -> {
                try (PreparedStatement ps = database.conn.prepareStatement(
                    "INSERT INTO players(uuid,playtime) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET playtime=playtime+?")) {
                    ps.setString(1, uuid); ps.setLong(2, minutes); ps.setLong(3, minutes); ps.execute();
                } catch (SQLException e) { LOGGER.error("[Playtime] save failed", e); }
            });
        }

        public long getPlaytime(String uuid) {
            long stored = 0;
            try (PreparedStatement ps = database.conn.prepareStatement("SELECT playtime FROM players WHERE uuid=?")) {
                ps.setString(1, uuid); ResultSet rs = ps.executeQuery();
                if (rs.next()) stored = rs.getLong(1);
            } catch (SQLException e) { LOGGER.error("[Playtime] get failed", e); }
            Long joinTime = joinTimes.get(uuid);
            if (joinTime != null) stored += (System.currentTimeMillis() - joinTime) / 60_000;
            return stored;
        }
    }

    // ============================================================
    // SCOREBOARD MANAGER
    // ============================================================
    public static class ScoreboardManager {
        private int tickCount = 0;
        private static final int UPDATE_INTERVAL = 40;

        public void onServerStart(MinecraftServer server) {}

        public void onTick(MinecraftServer server) {
            tickCount++;
            if (tickCount % UPDATE_INTERVAL != 0) return;
            tickCount = 0;
            double tps = Math.min(20.0, 1000.0 / Math.max(1, server.getAverageTickTime()));
            String tpsColor = tps > 18 ? "\u00A7a" : tps > 15 ? "\u00A7e" : "\u00A7c";
            int playerCount = server.getPlayerManager().getCurrentPlayerCount();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                var mc = server.getScoreboard();
                String objName = "sm_sidebar";
                var existing = mc.getNullableObjective(objName);
                if (existing != null) mc.removeObjective(existing);
                var obj = mc.addObjective(objName,
                    net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                    Text.literal(colorize("&b&lServerManager")),
                    net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER,
                    false, null);
                String group = permissions.getGroup(player.getUuidAsString());
                double bal   = database.getBalance(player.getUuidAsString());
                int ping     = player.networkHandler.getLatency();
                String nick  = nicks.getNick(player.getUuidAsString());
                String displayName = nick != null ? colorize(nick) : player.getName().getString();
                setLine(mc, obj, colorize("&7&m        "), 12);
                setLine(mc, obj, colorize("&eName: &f") + displayName, 11);
                setLine(mc, obj, colorize("&eRank: &f") + group, 10);
                setLine(mc, obj, colorize("&7&m       "), 9);
                setLine(mc, obj, colorize("&eBalance: &a$") + String.format("%.0f", bal), 8);
                setLine(mc, obj, colorize("&ePing: &f") + ping + "ms", 7);
                setLine(mc, obj, colorize("&7&m      "), 6);
                setLine(mc, obj, colorize("&eTPS: ") + tpsColor + String.format("%.1f", tps), 5);
                setLine(mc, obj, colorize("&ePlayers: &f") + playerCount, 4);
                setLine(mc, obj, colorize("&7&m     "), 3);
                setLine(mc, obj, colorize("&bplay.yourserver.com"), 2);
                mc.setObjectiveSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR, obj);
            }
        }

        private void setLine(net.minecraft.scoreboard.Scoreboard mc,
                             net.minecraft.scoreboard.ScoreboardObjective obj, String text, int score) {
            mc.getOrCreateScore(net.minecraft.scoreboard.ScoreHolder.fromName(text), obj).setScore(score);
        }
    }
}
