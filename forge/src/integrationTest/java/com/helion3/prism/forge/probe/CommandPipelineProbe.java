/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.helion3.prism.forge.probe;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.ConditionGroup;
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.forge.ForgeProfiles;
import com.helion3.prism.forge.command.ForgeChatActions;
import com.helion3.prism.forge.command.ForgeCommandSource;
import com.helion3.prism.forge.command.ForgePermissions;
import com.helion3.prism.forge.world.ForgeLocation;
import com.helion3.prism.forge.world.ForgeWorlds;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.command.ICommandManager;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.text.Text;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Disposable server test mod: native commands and inspection, synthetic history, no block writes. */
@Mod(modid = "prismcommandprobe", name = "Prism native command pipeline probe", version = "1",
        acceptableRemoteVersions = "*", dependencies = "required-after:prism")
public final class CommandPipelineProbe {
    private enum Stage {
        SEED, PLAYER_LOOKUP, LOOKUP, NEAR, INSPECT_LEFT, INSPECT_RIGHT
    }

    private static final String RESULT_FILE = "prism-command-probe-result.properties";
    private static final Set<String> NODES = new HashSet<>(Arrays.asList(
            "prism.info", "prism.help", "prism.lookup", "prism.inspect",
            "prism.rollback", "prism.undo", "prism.extinguish"));
    private final String marker = "commandprobe" + UUID.randomUUID().toString().replace("-", "");
    private final String firstTarget = "prismcommandprobe:" + marker + "alpha";
    private final String secondTarget = "prismcommandprobe:" + marker + "beta";
    private final String playerTarget = "prismcommandprobe:" + marker + "player";
    private final GameProfile profile = new GameProfile(UUID.fromString("43c7fd92-7023-42f4-856a-f65aa2e78d17"), "PrismProfile");
    private final Properties results = new Properties();
    private final Set<String> checkedNodes = new HashSet<>();
    private ForgePermissions.Provider permissions;
    private ICommandManager commands;
    private ProbePlayer player;
    private WorldServer world;
    private Stage stage;
    private CompletableFuture<List<Result>> seededRecords;
    private long deadline;
    private long ticks;
    private long nextSeedQuery;
    private int x;
    private int y;
    private int z;
    private Block firstBlock;
    private Block secondBlock;
    private int firstMetadata;
    private int secondMetadata;
    private boolean denyPermissions;
    private boolean done;

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        try {
            Files.deleteIfExists(Paths.get(RESULT_FILE));
            check(Prism.getInstance().isStorageReady(), "Original storage lifecycle did not start");
            assertSpongeAbsent();
            world = DimensionManager.getWorld(0);
            check(world != null, "Overworld is unavailable");
            verifyWorldIdentities();
            FMLCommonHandler.instance().getMinecraftServerInstance().func_152358_ax().func_152649_a(profile);
            check(ForgeProfiles.uniqueId("prismprofile").join().equals(profile.getId()),
                    "Native name cache did not resolve the original player parameter");
            passed("native_cached_player_resolution");
            player = new ProbePlayer(world);
            player.commandThread = Thread.currentThread();
            permissions = (sender, node) -> {
                if (sender == player) {
                    checkedNodes.add(node);
                    return !denyPermissions && NODES.contains(node);
                }
                return sender.canCommandSenderUseCommand(4, node);
            };
            ForgePermissions.register(permissions);
            commands = FMLCommonHandler.instance().getMinecraftServerInstance().getCommandManager();

            player.setPosition(-42.25, 70.75, 33.5);
            ForgeLocation fractional = ForgeCommandSource.location(ForgeCommandSource.wrap(player)).get();
            check(fractional.getX() == -43 && fractional.getY() == 70 && fractional.getZ() == 33,
                    "Native player location must floor signed coordinates like the original Sponge Location");
            passed("signed_fractional_location");

            ChunkCoordinates spawn = world.getSpawnPoint();
            x = spawn.posX;
            y = 0;
            z = spawn.posZ;
            player.setPosition(x + 0.25, y + 0.75, z + 0.5);
            firstBlock = world.getBlock(x, y, z);
            secondBlock = world.getBlock(x + 1, y, z);
            firstMetadata = world.getBlockMetadata(x, y, z);
            secondMetadata = world.getBlockMetadata(x + 1, y, z);

            enqueue(firstTarget, x);
            enqueue(secondTarget, x + 1);
            enqueue(playerTarget, x);
            begin(Stage.SEED);
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == null || done) return;
        try {
            ticks++;
            check(System.nanoTime() < deadline, "Timed out in native command probe stage " + stage);
            switch (stage) {
                case SEED:
                    pollSeedRecords();
                    break;
                case LOOKUP:
                    if (player.contains(marker + "alpha")) {
                        check(!player.contains(marker + "beta"), "Original block parameter did not isolate the selected target");
                        check(player.hasHover(), "Native lookup result lost its original hover details");
                        passed("original_lookup_and_native_hover");
                        execute("/pr near");
                        begin(Stage.NEAR);
                    }
                    break;
                case PLAYER_LOOKUP:
                    if (player.contains(marker + "player")) {
                        check(player.contains("PrismProfile"), "Original result translation did not use the native profile cache");
                        check(!player.contains(marker + "alpha") && !player.contains(marker + "beta"),
                                "Original player parameter lost its UUID filter");
                        passed("original_player_filter_and_name_translation");
                        execute("/pr l b:" + firstTarget + " r:2147483647 -ng");
                        check(player.contains("Limiting radius to maximum of "
                                + Prism.getInstance().getConfig().getLimitCategory().getMaximumRadius()),
                                "Original maximum radius was not enforced through native permissions");
                        passed("original_radius_limit");
                        begin(Stage.LOOKUP);
                    }
                    break;
                case NEAR:
                    if (player.contains(marker + "alpha") && player.contains(marker + "beta")) {
                        passed("original_near");
                        execute("/pr i");
                        check(player.contains("Inspection wand enabled"), "Native inspection command did not enable the wand");
                        player.clearChat();
                        PlayerInteractEvent left = new PlayerInteractEvent(player,
                                PlayerInteractEvent.Action.LEFT_CLICK_BLOCK, x, y, z, ForgeDirection.UP.ordinal(), world);
                        check(MinecraftForge.EVENT_BUS.post(left) && left.isCanceled(),
                                "Native left-click inspection did not cancel the block interaction");
                        begin(Stage.INSPECT_LEFT);
                    }
                    break;
                case INSPECT_LEFT:
                    if (player.contains(marker + "alpha")) {
                        check(!player.contains(marker + "beta"), "Left-click inspection queried the adjacent position");
                        passed("native_left_inspection_and_exact_query");
                        player.clearChat();
                        PlayerInteractEvent right = new PlayerInteractEvent(player,
                                PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, x, y, z, ForgeDirection.EAST.ordinal(), world);
                        check(MinecraftForge.EVENT_BUS.post(right) && right.isCanceled(),
                                "Native right-click inspection did not cancel the block interaction");
                        begin(Stage.INSPECT_RIGHT);
                    }
                    break;
                case INSPECT_RIGHT:
                    if (player.contains(marker + "beta")) {
                        check(!player.contains(marker + "alpha"), "Right-click inspection did not query the clicked side");
                        passed("native_right_inspection_and_relative_query");
                        verifyEntityInspectionCancellation();
                        execute("/pr i");
                        check(player.contains("Inspection wand disabled"), "Native inspection command did not disable the wand");
                        finish();
                    }
                    break;
                default:
                    throw new AssertionError("Unexpected probe stage " + stage);
            }
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    private void pollSeedRecords() throws Exception {
        if (seededRecords == null) {
            if (ticks < nextSeedQuery) return;
            QuerySession session = new QuerySession(ForgeCommandSource.wrap(player));
            session.addFlag(Flag.NO_GROUP);
            ConditionGroup targets = new ConditionGroup(ConditionGroup.Operator.OR);
            targets.add(FieldCondition.of(DataQueries.Target, MatchRule.EQUALS, firstTarget));
            targets.add(FieldCondition.of(DataQueries.Target, MatchRule.EQUALS, secondTarget));
            session.newQuery().addCondition(targets);
            seededRecords = Prism.getInstance().getStorageAdapter().records().query(session, false);
        }
        if (!seededRecords.isDone()) return;
        List<Result> found = seededRecords.join();
        if (found.size() == 2) {
            passed("original_record_queue_and_database");
            verifyImmediateCommands();
            execute("/pr l p:PrismProfile r:5 -ng");
            begin(Stage.PLAYER_LOOKUP);
        } else {
            seededRecords = null;
            nextSeedQuery = ticks + 5;
        }
    }

    private void verifyImmediateCommands() throws Exception {
        execute("/prism");
        check(player.contains("Tracking so good the NSA stole our name"), "Original info command was not registered");
        passed("original_info");
        execute("/pr ?");
        check(player.contains("/pr [l|lookup]"), "Original help alias did not produce its retained help text");
        passed("original_help");
        verifyPagination();

        denyPermissions = true;
        execute("/pr l b:" + firstTarget);
        check(player.contains("permission"), "Original named lookup permission was not enforced");
        denyPermissions = false;
        passed("native_permission_denial");

        execute("/pr ext notaninteger");
        check(player.contains("Expected an integer"), "Original CommandSpec numeric argument error was lost");
        execute("/pr l nonexistent:value");
        check(player.contains("not a valid parameter"), "Original query parameter error was lost");
        passed("original_argument_errors");

        for (String command : Arrays.asList("/pr rb b:" + firstTarget, "/pr rs b:" + firstTarget,
                "/pr undo", "/pr ext 1")) {
            execute(command);
            check(player.contains("not available in this migration build yet"),
                    "World-writing command did not report the unavailable native backend: " + command);
        }
        passed("world_write_commands_unavailable");
    }

    private void verifyEntityInspectionCancellation() throws Exception {
        EntityInteractEvent interaction = new EntityInteractEvent(player, player);
        check(MinecraftForge.EVENT_BUS.post(interaction) && interaction.isCanceled(),
                "Native secondary entity interaction was not cancelled while inspecting");
        AttackEntityEvent attack = new AttackEntityEvent(player, player);
        check(MinecraftForge.EVENT_BUS.post(attack) && attack.isCanceled(),
                "Native primary entity interaction was not cancelled while inspecting");
        passed("native_entity_inspection_cancellation");
    }

    private void verifyPagination() throws Exception {
        List<Text> entries = new ArrayList<>();
        for (int number = 1; number <= 16; number++) entries.add(Text.of("page-entry-" + number + "."));
        player.clearChat();
        ForgeChatActions.sendPages(ForgeCommandSource.wrap(player).withPermission("prism.lookup"), entries);
        check(player.contains("page-entry-1.") && !player.contains("page-entry-16."),
                "First native page did not retain the original fifteen-entry limit");
        String next = player.clickCommand("[Next]");
        check(next != null, "Native lookup pagination has no next-page command");
        execute(next);
        check(player.contains("page-entry-16.") && !player.contains("page-entry-1."),
                "Native pagination callback did not show the second page");
        String previous = player.clickCommand("[Previous]");
        check(previous != null, "Native lookup pagination has no previous-page command");
        execute(previous);
        check(player.contains("page-entry-1.") && !player.contains("page-entry-16."),
                "Native pagination callback did not return to the first page");
        passed("native_pagination_callbacks");
    }

    private void execute(String command) {
        player.clearChat();
        commands.executeCommand(player, command);
    }

    private void enqueue(String target, int blockX) {
        DataContainer data = DataContainer.createNew().set(DataQueries.Location,
                ForgeLocation.of(world, blockX, y, z).toContainer());
        if (target.equals(playerTarget)) data.set(DataQueries.Player, profile.getId().toString());
        PrismRecord.create().source(Cause.of(EventContext.empty(), "environment"))
                .event(PrismEvents.BLOCK_BREAK).dataContainer(data).target(target).buildAndSave();
    }

    private void verifyWorldIdentities() throws Exception {
        Properties current = new Properties();
        Set<UUID> identities = new HashSet<>();
        for (int dimension : new int[]{0, -1, 1}) {
            WorldServer loaded = DimensionManager.getWorld(dimension);
            check(loaded != null, "Probe dimension is not loaded: " + dimension);
            UUID id = ForgeWorlds.id(loaded);
            check(identities.add(id), "Native dimensions share a Prism history identity");
            check(ForgeWorlds.find(id).orElse(null) == loaded, "Prism world lookup returned the wrong native dimension");
            current.setProperty(Integer.toString(dimension), id.toString());
        }
        java.nio.file.Path previous = Paths.get("prism-command-world-ids.properties");
        if (Files.exists(previous)) {
            Properties saved = new Properties();
            try (InputStream input = Files.newInputStream(previous)) {
                saved.load(input);
            }
            check(saved.equals(current), "Native history world identities changed across server restart");
            passed("world_identity_restart");
        }
        try (OutputStream output = Files.newOutputStream(previous)) {
            current.store(output, "Disposable command probe world identities");
        }
        passed("native_dimension_identity_isolation");
    }

    private void begin(Stage next) {
        stage = next;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    }

    private void finish() throws Exception {
        check(checkedNodes.containsAll(NODES), "Original named command permissions were not passed to the provider");
        check(!player.wrongThread, "Command chat or asynchronous lookup chat was delivered away from the server thread");
        check(world.getBlock(x, y, z) == firstBlock && world.getBlockMetadata(x, y, z) == firstMetadata,
                "First inspected block changed during the read-only probe");
        check(world.getBlock(x + 1, y, z) == secondBlock && world.getBlockMetadata(x + 1, y, z) == secondMetadata,
                "Adjacent inspected block changed during the read-only probe");
        assertSpongeAbsent();
        passed("named_permission_nodes_preserved");
        passed("chat_on_server_thread");
        passed("native_chat_json_round_trip");
        passed("no_block_changes");
        passed("sponge_game_absent");
        passed("complete");
        done = true;
        cleanup();
        System.out.println("PRISM_COMMAND_PROBE complete=PASS lookup=PASS near=PASS inspection=PASS permissions=PASS");
    }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) {
        if (!done) failed(new AssertionError("Server stopped before native command probe finished"));
        cleanup();
    }

    private void cleanup() {
        if (player != null && Prism.getInstance() != null) {
            Prism.getInstance().getActiveWands().remove(player.getUniqueID());
        }
        if (permissions != null) {
            ForgePermissions.unregister(permissions);
            permissions = null;
        }
        FMLCommonHandler.instance().bus().unregister(this);
    }

    private static void assertSpongeAbsent() {
        try {
            Sponge.getGame();
            throw new AssertionError("The native command test must not have a Sponge Game instance");
        } catch (IllegalStateException expected) {
            // Retained API value objects and parser run without Sponge's server engine.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void passed(String key) throws Exception {
        results.setProperty(key, "PASS");
        save();
    }

    private void failed(Throwable failure) {
        done = true;
        results.setProperty("failure", failure.toString());
        failure.printStackTrace();
        try {
            save();
        } catch (Exception saveFailure) {
            saveFailure.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void save() throws Exception {
        try (OutputStream output = Files.newOutputStream(Paths.get(RESULT_FILE))) {
            results.store(output, "Separate native Forge command test mod; synthetic history only");
        }
    }

    private static final class ProbePlayer extends FakePlayer {
        private final List<IChatComponent> messages = new ArrayList<>();
        private Thread commandThread;
        private boolean wrongThread;

        private ProbePlayer(WorldServer world) {
            super(world, new GameProfile(UUID.randomUUID(), "PrismCommandProbe"));
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            if (messages != null) {
                wrongThread |= commandThread != null && commandThread != Thread.currentThread();
                String json = IChatComponent.Serializer.func_150696_a(message);
                IChatComponent delivered = IChatComponent.Serializer.func_150699_a(json);
                check(delivered.getUnformattedText().equals(message.getUnformattedText()),
                        "Native chat payload lost text during the client JSON round trip");
                messages.add(delivered);
            }
        }

        private void clearChat() {
            messages.clear();
        }

        private boolean contains(String expected) {
            for (IChatComponent message : messages) {
                if (message.getUnformattedText().contains(expected)) return true;
            }
            return false;
        }

        private boolean hasHover() {
            for (IChatComponent message : messages) {
                if (hasHover(message)) return true;
            }
            return false;
        }

        private String clickCommand(String label) {
            for (IChatComponent message : messages) {
                String command = clickCommand(message, label);
                if (command != null) return command;
            }
            return null;
        }

        private static String clickCommand(IChatComponent component, String label) {
            ClickEvent action = component.getChatStyle().getChatClickEvent();
            if (action != null && action.getAction() == ClickEvent.Action.RUN_COMMAND
                    && component.getUnformattedText().contains(label)) return action.getValue();
            for (Object sibling : component.getSiblings()) {
                String command = clickCommand((IChatComponent) sibling, label);
                if (command != null) return command;
            }
            return null;
        }

        private static boolean hasHover(IChatComponent component) {
            if (component.getChatStyle().getChatHoverEvent() != null) return true;
            for (Object sibling : component.getSiblings()) {
                if (hasHover((IChatComponent) sibling)) return true;
            }
            return false;
        }
    }
}
