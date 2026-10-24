package fr.geeklegend.slimeworldtest.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.geeklegend.slimeworld.SlimeWorldManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Commande de test : /istest <sous-commande>
 * <p>
 * /istest loadworld <monde>             charge un monde du dossier serveur et t'y téléporte
 * /istest export <monde> <rayon> <id>   exporte une zone d'un monde normal (pour créer un template)
 * /istest create <id> <template>        crée une île depuis un template et t'y téléporte
 * /istest load <id>                     charge une île et t'y téléporte
 * /istest border <id> <taille>          change la taille de la bordure (sauvegardée avec l'île)
 * /istest save <id>                     sauvegarde sans décharger
 * /istest unload <id>                   sauvegarde et décharge
 * /istest delete <id>                   supprime définitivement
 * <p>
 * Chaque commande affiche le temps qu'elle a pris, pour mesurer les performances.
 */
public final class IslandCommand {

    private IslandCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(SlimeWorldManager islands) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("istest")
                .requires(source -> source.getSender().hasPermission("islandworlds.test"));

        root.then(Commands.literal("loadworld")
                .then(Commands.argument("monde", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "monde");
                            World world = Bukkit.getWorld(name);
                            if (world == null) {
                                world = new WorldCreator(name).generator(new VoidGenerator()).createWorld();
                            }
                            if (world == null) {
                                error(sender(ctx), "Impossible de charger le monde " + name);
                                return 0;
                            }
                            sender(ctx).sendMessage(Component.text("Monde " + name + " chargé", NamedTextColor.GREEN));
                            if (sender(ctx) instanceof Player player) {
                                player.teleportAsync(world.getSpawnLocation());
                            }
                            return Command.SINGLE_SUCCESS;
                        })));

        root.then(Commands.literal("export")
                .then(Commands.argument("monde", StringArgumentType.word())
                        .then(Commands.argument("rayon", IntegerArgumentType.integer(0, 16))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> {
                                            World world = Bukkit.getWorld(StringArgumentType.getString(ctx, "monde"));
                                            if (world == null) {
                                                error(sender(ctx), "Monde introuvable");
                                                return 0;
                                            }
                                            int radius = IntegerArgumentType.getInteger(ctx, "rayon");
                                            String id = StringArgumentType.getString(ctx, "id");
                                            track(sender(ctx), "Export de " + id, islands.exportWorld(world, radius, id));
                                            return Command.SINGLE_SUCCESS;
                                        })))));

        root.then(Commands.literal("exporthere")
                .then(Commands.argument("rayon", IntegerArgumentType.integer(0, 16))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    if (!(sender(ctx) instanceof Player player)) {
                                        error(sender(ctx), "Commande réservée aux joueurs");
                                        return 0;
                                    }
                                    int radius = IntegerArgumentType.getInteger(ctx, "rayon");
                                    String id = StringArgumentType.getString(ctx, "id");
                                    track(player, "Export de " + id, islands.exportAround(player.getLocation(), radius, id));
                                    return Command.SINGLE_SUCCESS;
                                }))));

        root.then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("template", StringArgumentType.word())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    String template = StringArgumentType.getString(ctx, "template");
                                    CompletableFuture<World> future = islands.create(id, template);
                                    track(sender(ctx), "Création de " + id, future);
                                    teleportWhenReady(sender(ctx), future);
                                    return Command.SINGLE_SUCCESS;
                                }))));

        root.then(Commands.literal("load")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            CompletableFuture<World> future = islands.load(id);
                            track(sender(ctx), "Chargement de " + id, future);
                            teleportWhenReady(sender(ctx), future);
                            return Command.SINGLE_SUCCESS;
                        })));

        root.then(Commands.literal("save")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            track(sender(ctx), "Sauvegarde de " + id, islands.save(id));
                            return Command.SINGLE_SUCCESS;
                        })));

        root.then(Commands.literal("border")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("taille", IntegerArgumentType.integer(1, 1024))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    int size = IntegerArgumentType.getInteger(ctx, "taille");
                                    track(sender(ctx), "Bordure de " + id, islands.setBorderSize(id, size));
                                    return Command.SINGLE_SUCCESS;
                                }))));

        root.then(Commands.literal("unload")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            track(sender(ctx), "Déchargement de " + id, islands.unload(id, fallback()));
                            return Command.SINGLE_SUCCESS;
                        })));

        root.then(Commands.literal("delete")
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            track(sender(ctx), "Suppression de " + id, islands.delete(id, fallback()));
                            return Command.SINGLE_SUCCESS;
                        })));

        return root.build();
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private static Location fallback() {
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /**
     * Affiche "en cours", puis le résultat avec la durée.
     */
    private static void track(CommandSender sender, String label, CompletableFuture<?> future) {
        long start = System.nanoTime();
        sender.sendMessage(Component.text(label + "…", NamedTextColor.GRAY));
        future.whenComplete((result, error) -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (error == null) {
                sender.sendMessage(Component.text(label + " : OK (" + ms + " ms)", NamedTextColor.GREEN));
            } else {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                error(sender, label + " : échec (" + ms + " ms) - " + cause.getMessage());
                cause.printStackTrace();
            }
        });
    }

    private static void teleportWhenReady(CommandSender sender, CompletableFuture<World> future) {
        if (!(sender instanceof Player player)) return;
        future.thenAccept(world -> player.teleportAsync(world.getSpawnLocation()));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static final class VoidGenerator extends org.bukkit.generator.ChunkGenerator {
    }
}