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
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.PrismRecordPreSaveEvent;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.forge.command.ForgeCommandSource;
import com.helion3.prism.forge.command.ForgePermissions;
import com.helion3.prism.forge.data.NbtTranslator;
import com.helion3.prism.forge.world.ForgeWorlds;
import com.helion3.prism.util.DataQueries;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataQuery;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Separate test mod: actual native mutations, BSON persistence, restart checks and local timing. */
@Mod(modid = "prismcaptureprobe", name = "Prism capture test probe", version = "1",
        acceptableRemoteVersions = "*", dependencies = "required-after:prism")
public final class CapturePipelineProbe {
    private static final String MACHINE = "prismcaptureprobe:fixture_machine";
    private static final UUID PLAYER = UUID.fromString("a3eb5eca-2dc9-4e67-a380-0610e50b4da1");
    private static final UUID BENCHMARK = UUID.fromString("07d77597-5e61-4fa9-8cc2-725af1d781a4");
    private static final Path RESULTS = Paths.get("prism-capture-probe.properties");
    private static final Path HISTORY = Paths.get("prism-capture-history.properties");
    private final Properties results = new Properties();
    private final List<Expected> expected = new ArrayList<>();
    private final Map<String, Saved> saved = new LinkedHashMap<>();
    private final String mode = System.getProperty("prism.capture.probe.mode", "write");
    private FixtureBlock machine;
    private Item multi;
    private Item handled;
    private Item throwing;
    private ProbePlayer player;
    private WorldServer world;
    private ForgePermissions.Provider permissions;
    private CompletableFuture<List<Result>> pending;
    private long startedAt;
    private long deadline;
    private int originX;
    private int originZ;
    private int cancelBreakX = Integer.MIN_VALUE;
    private int cancelPlaceX = Integer.MIN_VALUE;
    private int failedX = Integer.MIN_VALUE;
    private int stage;
    private boolean done;
    private boolean failed;

    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        machine = new FixtureBlock();
        machine.setBlockName("prism_capture_fixture").setHardness(1.0F);
        GameRegistry.registerBlock(machine, "fixture_machine");
        GameRegistry.registerTileEntity(FixtureTile.class, "prismcaptureprobe.fixture_tile");
        multi = new MultiItem(machine);
        GameRegistry.registerItem(multi, "multi_fixture");
        handled = new Item() {
            @Override
            public boolean onBlockStartBreak(ItemStack item, int x, int y, int z, EntityPlayer player) {
                player.worldObj.setBlockToAir(x, y, z);
                return true;
            }
        };
        GameRegistry.registerItem(handled, "handled_break");
        throwing = new Item() {
            @Override
            public boolean onBlockStartBreak(ItemStack item, int x, int y, int z, EntityPlayer player) {
                throw new IllegalStateException("intentional capture probe exception");
            }
        };
        GameRegistry.registerItem(throwing, "throwing_break");
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        machine.failure = x -> x == failedX;
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        try {
            Files.deleteIfExists(RESULTS);
            check(Prism.getInstance().isStorageReady(), "Storage is not ready");
            world = DimensionManager.getWorld(0);
            player = new ProbePlayer(world, PLAYER, "PrismCaptureTest");
            player.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
            FMLCommonHandler.instance().getMinecraftServerInstance().func_152358_ax().func_152649_a(player.getGameProfile());
            permissions = (sender, node) -> sender == player || sender.canCommandSenderUseCommand(4, node);
            ForgePermissions.register(permissions);
            originX = world.getSpawnPoint().posX + 8;
            originZ = world.getSpawnPoint().posZ + 8;
            player.setPosition(originX + 0.5, 71, originZ + 0.5);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            if (mode.equals("benchmark")) {
                benchmark();
                return;
            }
            if (mode.equals("read")) {
                loadHistory();
                stage = 1;
                return;
            }
            startedAt = System.currentTimeMillis();
            exerciseMutations();
            saveHistory();
            stage = 1;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private int x(int index) {
        return originX + index * 3;
    }

    private void remember(int x, int y, int z) {
        String key = x + "," + y + "," + z;
        if (!saved.containsKey(key)) saved.put(key, new Saved(world, x, y, z));
    }

    private void prepare(int index, Block block, int metadata) {
        int x = x(index);
        remember(x, 69, originZ);
        remember(x, 70, originZ);
        world.setBlock(x, 69, originZ, Blocks.stone, 0, 2);
        world.setBlock(x, 70, originZ, block, metadata, 2);
    }

    private boolean place(ItemStack stack, int index) {
        player.inventory.setInventorySlotContents(0, stack);
        return ForgeHooks.onPlaceItemIntoWorld(stack, player, world, x(index), 69, originZ, 1, 0.5F, 1.0F, 0.5F);
    }

    private boolean harvest(int index) {
        return player.theItemInWorldManager.tryHarvestBlock(x(index), 70, originZ);
    }

    private void expect(int index, String event, String before, int oldMeta, String after, int newMeta) {
        expected.add(new Expected(x(index), 70, originZ, event, before, oldMeta, after, newMeta));
    }

    private void exerciseMutations() throws Exception {
        prepare(0, Blocks.air, 0);
        check(place(new ItemStack(Blocks.stone), 0), "Stone placement failed");
        expect(0, "place", "minecraft:air", 0, "minecraft:stone", 0);
        player.inventory.setInventorySlotContents(0, null);
        check(harvest(0), "Stone break failed");
        expect(0, "break", "minecraft:stone", 0, "minecraft:air", 0);

        prepare(1, Blocks.air, 0);
        FixtureTile.writes = 0;
        check(place(new ItemStack(machine), 1), "Machine placement failed");
        check(FixtureTile.writes == 1, "Machine placement should add one final-state NBT serialization, got " + FixtureTile.writes);
        expect(1, "place", "minecraft:air", 0, MACHINE, 0);
        player.inventory.setInventorySlotContents(0, null);
        FixtureTile.writes = 0;
        check(harvest(1), "Machine break failed");
        check(FixtureTile.writes == 1, "Machine break should serialize its before-image only once, got " + FixtureTile.writes);
        expect(1, "break", MACHINE, 0, "minecraft:air", 0);
        passed("one_tile_serialization_per_machine_change");

        prepare(2, Blocks.stone, 0);
        cancelBreakX = x(2);
        check(!harvest(2) && world.getBlock(x(2), 70, originZ) == Blocks.stone, "Cancelled break changed the block");
        cancelBreakX = Integer.MIN_VALUE;
        prepare(3, Blocks.air, 0);
        cancelPlaceX = x(3);
        check(!place(new ItemStack(machine), 3) && world.isAirBlock(x(3), 70, originZ), "Cancelled placement was not reverted");
        cancelPlaceX = Integer.MIN_VALUE;

        prepare(4, machine, 5);
        failedX = x(4);
        player.inventory.setInventorySlotContents(0, null);
        check(!harvest(4) && world.getBlock(x(4), 70, originZ) == machine, "Failed removal changed the block");
        failedX = Integer.MIN_VALUE;

        prepare(5, Blocks.stone, 0);
        player.inventory.setInventorySlotContents(0, new ItemStack(handled));
        check(!harvest(5) && world.isAirBlock(x(5), 70, originZ), "Handled tool did not exercise the false-return mutation path");
        expect(5, "break", "minecraft:stone", 0, "minecraft:air", 0);

        prepare(6, Blocks.stone, 0);
        player.inventory.setInventorySlotContents(0, new ItemStack(throwing));
        boolean threw = false;
        try {
            harvest(6);
        } catch (IllegalStateException expected) {
            threw = expected.getMessage().contains("intentional capture probe");
        }
        check(threw, "Expected a handled test exception");
        // This committed write is outside a player operation, and must not inherit that player's context.
        world.setBlockToAir(x(6), 70, originZ);
        player.inventory.setInventorySlotContents(0, null);
        passed("exception_scope_cleanup");

        prepare(7, Blocks.air, 0);
        remember(x(7) + 1, 70, originZ);
        world.setBlock(x(7) + 1, 70, originZ, Blocks.air, 0, 2);
        check(place(new ItemStack(multi), 7), "Multi-block fixture placement failed");
        expect(7, "place", "minecraft:air", 0, MACHINE, 2);
        expected.add(new Expected(x(7) + 1, 70, originZ, "place", "minecraft:air", 0, MACHINE, 3));
        passed("multi_placement_and_duplicate_write_collapse");

        prepare(8, Blocks.stone, 0);
        Prism.getInstance().getActiveWands().add(PLAYER);
        check(!harvest(8) && world.getBlock(x(8), 70, originZ) == Blocks.stone, "Inspection did not cancel a direct native break");
        Prism.getInstance().getActiveWands().remove(PLAYER);

        prepare(9, machine, 0);
        Prism.getInstance().getConfig().getEventCategory().setBlockBreak(false);
        FixtureTile.writes = 0;
        check(harvest(9), "Disabled recording changed gameplay behavior");
        check(FixtureTile.writes == 0, "Disabled recording still serialized machine NBT");
        Prism.getInstance().getConfig().getEventCategory().setBlockBreak(true);
        passed("disabled_recording_avoids_nbt_work");
        passed("mutations_exercised");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void cancelledBreak(BlockEvent.BreakEvent event) {
        if (event.x == cancelBreakX) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void cancelledPlace(BlockEvent.PlaceEvent event) {
        if (event.x == cancelPlaceX) event.setCanceled(true);
    }

    @SubscribeEvent
    public void preSave(PrismRecordPreSaveEvent event) {
        if (event.getPrismRecord().getDataContainer().getString(DataQueries.Player).orElse("").equals(BENCHMARK.toString())) {
            event.setCanceled(true); // Benchmark capture/queue cost independently of database I/O.
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done || stage == 0) return;
        try {
            check(System.nanoTime() < deadline, "Timed out in capture test stage " + stage);
            if (stage == 1) {
                if (pending == null) pending = queryHistory();
                if (!pending.isDone()) return;
                List<Result> records = pending.join();
                pending = null;
                if (records.size() != expected.size()) return;
                verify(records);
                passed(mode.equals("read") ? "history_survives_server_restart" : "native_changes_persisted_in_mongodb");
                if (mode.equals("read")) {
                    finish();
                    return;
                }
                execute("pr l p:PrismCaptureTest -ng");
                stage = 2;
            } else if (stage == 2 && player.contains("fixture machine")) {
                check(player.contains("PrismCaptureTest"), "Lookup did not translate the actual player UUID");
                passed("original_lookup_displays_real_history");
                player.messages.clear();
                Prism.getInstance().getActiveWands().add(PLAYER);
                PlayerInteractEvent interaction = new PlayerInteractEvent(player, PlayerInteractEvent.Action.LEFT_CLICK_BLOCK,
                        x(1), 70, originZ, 1, world);
                MinecraftForge.EVENT_BUS.post(interaction);
                check(interaction.isCanceled(), "Real-history inspection did not prevent interaction");
                stage = 3;
            } else if (stage == 3 && player.contains("fixture machine")) {
                check(player.contains("PrismCaptureTest"), "Inspection did not resolve the actual player UUID");
                Prism.getInstance().getActiveWands().remove(PLAYER);
                passed("inspection_displays_real_history");
                cleanupBlocks();
                finish();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private CompletableFuture<List<Result>> queryHistory() {
        QuerySession session = new QuerySession(ForgeCommandSource.wrap(player));
        session.addFlag(Flag.NO_GROUP);
        session.newQuery().addCondition(FieldCondition.of(DataQueries.Player, MatchRule.EQUALS, PLAYER.toString()));
        session.getQuery().addCondition(FieldCondition.of(DataQueries.Created, MatchRule.GREATER_THAN_EQUAL, new Date(startedAt)));
        CompletableFuture<List<Result>> result = new CompletableFuture<>();
        Prism.getInstance().getScheduler().execute("PrismCaptureProbeQuery", () -> {
            try {
                Prism.getInstance().getStorageAdapter().records().query(session, false).whenComplete((rows, failure) -> {
                    if (failure == null) result.complete(rows); else result.completeExceptionally(failure);
                });
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void verify(List<Result> records) {
        Map<String, Expected> remaining = new LinkedHashMap<>();
        for (Expected record : expected) remaining.put(record.key(), record);
        String worldId = ForgeWorlds.id(world).toString();
        for (Result record : records) {
            var data = record.data;
            int x = data.getInt(DataQueries.Location.then(DataQueries.X)).orElseThrow();
            int y = data.getInt(DataQueries.Location.then(DataQueries.Y)).orElseThrow();
            int z = data.getInt(DataQueries.Location.then(DataQueries.Z)).orElseThrow();
            Expected match = remaining.remove(x + "," + y + "," + z + "," + record.getEventId());
            check(match != null, "Unexpected or duplicate record: " + data);
            check(worldId.equals(data.getString(DataQueries.Location.then(DataQueries.WorldUuid)).orElseThrow()), "Wrong native world UUID");
            check(PLAYER.toString().equals(record.getSourceName()), "Player attribution was lost");
            check(match.before.equals(data.getString(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType)).orElseThrow()), "Wrong before block");
            check(match.after.equals(data.getString(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType)).orElseThrow()), "Wrong after block");
            check(match.oldMeta == data.getInt(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQuery.of("Metadata"))).orElseThrow(), "Wrong before metadata");
            check(match.newMeta == data.getInt(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQuery.of("Metadata"))).orElseThrow(), "Wrong after metadata");
            for (var side : new org.spongepowered.api.data.DataQuery[]{DataQueries.OriginalBlock, DataQueries.ReplacementBlock}) {
                String block = data.getString(side.then(DataQueries.BlockState).then(DataQueries.BlockType)).orElseThrow();
                if (!MACHINE.equals(block)) continue;
                var view = data.getView(side.then(DataQueries.UnsafeData)).orElseThrow();
                check(!view.contains(DataQueries.X), "Snapshot coordinates were duplicated in tile NBT");
                NBTTagCompound nbt = NbtTranslator.getInstance().translate(view);
                check(nbt.getLong("energy") == 9007199254740993L, "Machine energy changed before persistence");
                check(nbt.getByteArray("blob").length == 4096 && nbt.getByteArray("blob")[0] == 7, "Machine byte array was not frozen");
                var item = nbt.getTagList("Items", 10).getCompoundTagAt(0);
                check("kept".equals(item.getCompoundTag("tag").getString("literal:key.with.dots")), "Nested NBT key changed");
                check(Arrays.equals(new byte[]{1, 2, 3}, item.getCompoundTag("tag").getByteArray("payload")), "Nested inventory bytes changed");
            }
        }
        check(remaining.isEmpty(), "Expected native changes are missing");
    }

    private void execute(String command) {
        player.messages.clear();
        FMLCommonHandler.instance().getMinecraftServerInstance().getCommandManager().executeCommand(player, command);
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        if (failed || !done || !mode.equals("write")) return;
        try {
            prepare(10, Blocks.stone, 0);
            player.inventory.setInventorySlotContents(0, null);
            check(harvest(10), "Shutdown capture failed");
            expect(10, "break", "minecraft:stone", 0, "minecraft:air", 0);
            saveHistory();
            cleanupBlocks();
            passed("real_change_queued_at_shutdown");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void saveHistory() throws Exception {
        Properties history = new Properties();
        history.setProperty("started", Long.toString(startedAt));
        history.setProperty("count", Integer.toString(expected.size()));
        for (int index = 0; index < expected.size(); index++) history.setProperty("row." + index, expected.get(index).encode());
        save(HISTORY, history);
    }

    private void loadHistory() throws Exception {
        Properties history = new Properties();
        try (InputStream input = Files.newInputStream(HISTORY)) {
            history.load(input);
        }
        startedAt = Long.parseLong(history.getProperty("started"));
        int count = Integer.parseInt(history.getProperty("count"));
        for (int index = 0; index < count; index++) expected.add(Expected.decode(history.getProperty("row." + index)));
    }

    private void cleanupBlocks() {
        List<Saved> positions = new ArrayList<>(saved.values());
        for (int index = positions.size() - 1; index >= 0; index--) positions.get(index).restore(world);
        saved.clear();
    }

    private void finish() throws Exception {
        try {
            Sponge.getGame();
            throw new AssertionError("A Sponge engine was unexpectedly installed");
        } catch (IllegalStateException expected) {
            passed("sponge_game_absent");
        }
        passed("complete");
        done = true;
        System.out.println("PRISM_CAPTURE_PROBE mode=" + mode + " complete=PASS");
    }

    private void passed(String key) throws Exception {
        results.setProperty(key, "PASS");
        save(RESULTS, results);
    }

    private void fail(Throwable failure) {
        failed = true;
        done = true;
        Prism.getInstance().getActiveWands().remove(PLAYER);
        failure.printStackTrace();
        results.setProperty("failure", failure.toString());
        try {
            cleanupBlocks();
            save(RESULTS, results);
        } catch (Exception saveFailure) {
            saveFailure.printStackTrace();
        }
    }

    private static void save(Path path, Properties properties) throws Exception {
        Path temporary = Paths.get(path + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "Disposable native capture test fixture");
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void benchmark() throws Exception {
        player = new ProbePlayer(world, BENCHMARK, "PrismTimingTest");
        player.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
        player.setPosition(originX + 0.5, 71, originZ + 0.5);
        prepare(0, Blocks.air, 0);
        var threads = ManagementFactory.getThreadMXBean();
        check(threads.isCurrentThreadCpuTimeSupported(), "Thread CPU timing is unavailable");
        if (!threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
        var allocations = (com.sun.management.ThreadMXBean) threads;
        if (!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        results.setProperty("capture_hooks_present", Boolean.toString(getClass().getResource(
                "/com/helion3/prism/forge/capture/ForgeBlockCapture.class") != null));
        // Warm both the native operations and Prism's capture path before measuring either build.
        for (int warmup = 0; warmup < 3; warmup++) {
            benchmarkOperations("inactive", 2000);
            benchmarkOperations("stone", 1000);
            benchmarkOperations("machine", 300);
        }
        for (String kind : List.of("inactive", "stone", "machine")) {
            int iterations = kind.equals("inactive") ? 10000 : kind.equals("stone") ? 1000 : 300;
            results.setProperty(kind + ".iterations_per_sample", Integer.toString(iterations));
            FixtureTile.writes = 0;
            for (int sample = 0; sample < 7; sample++) {
                long bytes = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long cpu = threads.getCurrentThreadCpuTime();
                long wall = System.nanoTime();
                benchmarkOperations(kind, iterations);
                long elapsed = System.nanoTime() - wall;
                long cpuElapsed = threads.getCurrentThreadCpuTime() - cpu;
                long allocated = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
                // Each iteration commits a place and a break; timings are for one changed block.
                String prefix = kind + ".sample." + sample;
                results.setProperty(prefix + ".cpu_ns_per_change", Long.toString(cpuElapsed / (2L * iterations)));
                results.setProperty(prefix + ".wall_ns_per_change", Long.toString(elapsed / (2L * iterations)));
                results.setProperty(prefix + ".bytes_per_change", Long.toString(allocated / (2L * iterations)));
            }
            results.setProperty(kind + ".tile_nbt_writes", Integer.toString(FixtureTile.writes));
        }
        cleanupBlocks();
        finish();
    }

    private void benchmarkOperations(String kind, int iterations) {
        Block block = kind.equals("machine") ? machine : Blocks.stone;
        for (int index = 0; index < iterations; index++) {
            if (kind.equals("inactive")) {
                world.setBlock(x(0), 70, originZ, block, 0, 2);
                world.setBlockToAir(x(0), 70, originZ);
            } else {
                check(place(new ItemStack(block), 0), "Benchmark placement failed");
                player.inventory.setInventorySlotContents(0, null);
                check(harvest(0), "Benchmark break failed");
            }
        }
    }

    public static final class FixtureTile extends TileEntity {
        static int writes;
        long energy = 9007199254740993L;
        byte[] blob = new byte[4096];
        NBTTagList items = new NBTTagList();

        public FixtureTile() {
            Arrays.fill(blob, (byte) 7);
            NBTTagCompound item = new NBTTagCompound();
            item.setByte("Slot", (byte) 0);
            item.setShort("id", (short) 1);
            item.setByte("Count", (byte) 3);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("literal:key.with.dots", "kept");
            tag.setByteArray("payload", new byte[]{1, 2, 3});
            item.setTag("tag", tag);
            items.appendTag(item);
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            writes++;
            tag.setLong("energy", energy);
            tag.setByteArray("blob", blob);
            tag.setTag("Items", items);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            energy = tag.getLong("energy");
            blob = tag.getByteArray("blob");
            items = tag.getTagList("Items", 10);
        }
    }

    public static final class FixtureBlock extends BlockContainer {
        java.util.function.IntPredicate failure = x -> false;

        public FixtureBlock() {
            super(Material.rock);
        }

        @Override
        public TileEntity createNewTileEntity(World world, int metadata) {
            return new FixtureTile();
        }

        @Override
        public void onBlockHarvested(World world, int x, int y, int z, int metadata, EntityPlayer player) {
            FixtureTile tile = (FixtureTile) world.getTileEntity(x, y, z);
            if (tile != null) {
                tile.energy = 0L;
                Arrays.fill(tile.blob, (byte) 0);
                tile.items.getCompoundTagAt(0).getCompoundTag("tag").setString("literal:key.with.dots", "cleared by harvest");
            }
        }

        @Override
        public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean harvest) {
            return !failure.test(x) && super.removedByPlayer(world, player, x, y, z, harvest);
        }
    }

    public static final class MultiItem extends Item {
        private final Block block;

        public MultiItem(Block block) {
            this.block = block;
        }

        @Override
        public boolean onItemUse(ItemStack item, EntityPlayer player, World world, int x, int y, int z,
                                 int side, float hitX, float hitY, float hitZ) {
            world.setBlock(x, y + 1, z, block, 1, 3);
            world.setBlock(x, y + 1, z, block, 2, 3);
            world.setBlock(x + 1, y + 1, z, block, 3, 3);
            return true;
        }
    }

    private static final class ProbePlayer extends FakePlayer {
        private final List<String> messages = new ArrayList<>();

        private ProbePlayer(WorldServer world, UUID id, String name) {
            super(world, new GameProfile(id, name));
            playerNetServerHandler = new NetHandlerPlayServer(FMLCommonHandler.instance().getMinecraftServerInstance(),
                    new NetworkManager(false), this) {
                @Override
                public void sendPacket(Packet packet) {
                    // The fixture exercises server operations without a connected client.
                }
            };
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            if (messages != null) messages.add(IChatComponent.Serializer.func_150699_a(
                    IChatComponent.Serializer.func_150696_a(message)).getUnformattedText());
        }

        private boolean contains(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }
    }

    private static final class Saved {
        private final int x;
        private final int y;
        private final int z;
        private final Block block;
        private final int metadata;
        private final NBTTagCompound tile;

        private Saved(World world, int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = world.getBlock(x, y, z);
            this.metadata = world.getBlockMetadata(x, y, z);
            TileEntity existing = world.getTileEntity(x, y, z);
            this.tile = existing == null ? null : new NBTTagCompound();
            if (existing != null) existing.writeToNBT(tile);
        }

        private void restore(World world) {
            world.setBlock(x, y, z, block, metadata, 2);
            if (tile != null && world.getTileEntity(x, y, z) != null) world.getTileEntity(x, y, z).readFromNBT(tile);
        }
    }

    private static final class Expected {
        private final int x;
        private final int y;
        private final int z;
        private final String event;
        private final String before;
        private final int oldMeta;
        private final String after;
        private final int newMeta;

        private Expected(int x, int y, int z, String event, String before, int oldMeta, String after, int newMeta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.event = event;
            this.before = before;
            this.oldMeta = oldMeta;
            this.after = after;
            this.newMeta = newMeta;
        }

        private String key() {
            return x + "," + y + "," + z + "," + event;
        }

        private String encode() {
            return x + "|" + y + "|" + z + "|" + event + "|" + before + "|" + oldMeta + "|" + after + "|" + newMeta;
        }

        private static Expected decode(String value) {
            String[] parts = value.split("\\|");
            return new Expected(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3],
                    parts[4], Integer.parseInt(parts[5]), parts[6], Integer.parseInt(parts[7]));
        }
    }
}
