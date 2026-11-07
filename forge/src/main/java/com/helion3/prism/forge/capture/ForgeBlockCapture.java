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

package com.helion3.prism.forge.capture;

import com.helion3.prism.Prism;
import com.helion3.prism.forge.ForgeServerTasks;
import com.helion3.prism.forge.world.ForgeBlockSnapshot;
import com.helion3.prism.forge.world.ForgeLocation;
import com.helion3.prism.listeners.ChangeBlockListener;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures only player operation scopes; database writes remain in the original queue. */
public final class ForgeBlockCapture {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final ChangeBlockListener RECORDER = new ChangeBlockListener();
    // The common world-generation/machine-update path avoids even a ThreadLocal lookup.
    private static volatile boolean active;

    private ForgeBlockCapture() {
    }

    public static void beginBreak(EntityPlayerMP player, World world, int x, int y, int z) {
        begin(player, world, true, x, y, z);
    }

    public static void beginPlace(EntityPlayer player, World world) {
        begin(player, world, false, 0, 0, 0);
    }

    private static void begin(EntityPlayer player, World world, boolean breaking, int x, int y, int z) {
        Frame previous = CURRENT.get();
        Prism prism = Prism.getInstance();
        boolean enabled = ForgeServerTasks.isServerThread() && prism != null && prism.isStorageReady()
                && player != null && world != null && !world.isRemote
                && !prism.getActiveWands().contains(player.getUniqueID())
                && prism.getFilterList().allowsSource(player)
                && (breaking ? prism.getConfig().getEventCategory().isBlockBreak()
                : prism.getConfig().getEventCategory().isBlockPlace());
        if (!enabled && previous == null) return;
        Frame frame = new Frame(previous, player, world, breaking, enabled, x, y, z);
        CURRENT.set(frame);
        active = frame.anyActive;
    }

    /** The decision is observed after the complete native event dispatch. */
    public static void breakDecision(BlockEvent.BreakEvent event) {
        if (!active) return;
        Frame frame = CURRENT.get();
        if (frame == null || !frame.enabled || !frame.breaking) return;
        frame.cancelled = event.isCanceled();
        frame.armed = !frame.cancelled;
        if (!frame.armed) return;
        try {
            // Harvest callbacks can empty a machine before its eventual World.setBlock call.
            frame.original(frame.x, frame.y, frame.z, ForgeBlockSnapshot.capture(frame.world, frame.x, frame.y, frame.z));
        } catch (RuntimeException failure) {
            failed(frame, failure);
        }
    }

    public static void placeDecision(BlockEvent.PlaceEvent event) {
        if (!active) return;
        Frame frame = CURRENT.get();
        if (frame != null && frame.enabled && !frame.breaking) frame.cancelled = event.isCanceled();
    }

    /** Freeze the snapshot Forge already captured, before the corresponding write mutates the world. */
    public static void placementSnapshot(BlockSnapshot snapshot) {
        if (!active) return;
        Frame frame = CURRENT.get();
        if (frame == null || !frame.enabled || frame.cancelled || !frame.armed || snapshot.world != frame.world) return;
        try {
            long key = position(snapshot.x, snapshot.y, snapshot.z);
            if (!frame.entries.containsKey(key)) frame.original(snapshot.x, snapshot.y, snapshot.z, ForgeBlockSnapshot.from(snapshot));
        } catch (RuntimeException failure) {
            failed(frame, failure);
        }
    }

    /** Only accepted snapshots remain in Forge's list when it clears that list. */
    public static void placementSnapshots(List<BlockSnapshot> snapshots) {
        if (!active || snapshots.isEmpty()) return;
        Frame frame = CURRENT.get();
        if (frame == null || !frame.enabled || frame.breaking || frame.cancelled) return;
        try {
            for (BlockSnapshot snapshot : snapshots) {
                if (snapshot.world != frame.world) continue;
                long key = position(snapshot.x, snapshot.y, snapshot.z);
                Entry entry = frame.entries.get(key);
                if (entry == null) entry = frame.original(snapshot.x, snapshot.y, snapshot.z, ForgeBlockSnapshot.from(snapshot));
                entry.changed = true;
            }
        } catch (RuntimeException failure) {
            failed(frame, failure);
        }
    }

    public static void beforeWrite(World world, int x, int y, int z, Block replacement, int metadata) {
        if (!active) return;
        Frame frame = CURRENT.get();
        if (!acceptBreakWrite(frame, world) || world.captureBlockSnapshots || !inBounds(x, y, z)) return;
        long key = position(x, y, z);
        if (frame.entries.containsKey(key)) return;
        if (world.getBlock(x, y, z) == replacement && world.getBlockMetadata(x, y, z) == metadata) return;
        try {
            frame.original(x, y, z, ForgeBlockSnapshot.capture(world, x, y, z));
        } catch (RuntimeException failure) {
            failed(frame, failure);
        }
    }

    public static void afterWrite(boolean success, World world, int x, int y, int z) {
        if (!active || !success) return;
        Frame frame = CURRENT.get();
        if (!acceptBreakWrite(frame, world)) return;
        Entry entry = frame.entries.get(position(x, y, z));
        if (entry != null) entry.changed = true;
    }

    private static boolean acceptBreakWrite(Frame frame, World world) {
        return frame != null && frame.enabled && frame.breaking && frame.armed && !frame.cancelled
                && frame.world == world && !world.restoringBlockSnapshots;
    }

    private static boolean inBounds(int x, int y, int z) {
        return x >= -30000000 && x < 30000000 && z >= -30000000 && z < 30000000 && y >= 0 && y < 256;
    }

    private static long position(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | (y & 0xfffL);
    }

    /** Observe final world state only after Forge has accepted or reverted the operation. */
    public static void finish() {
        Frame frame = CURRENT.get();
        if (frame == null) return;
        pop(frame);
        if (!frame.enabled || frame.cancelled) return;
        try {
            Cause cause = Cause.of(EventContext.empty(), frame.player);
            for (Entry entry : frame.entries.values()) {
                if (!entry.changed) continue;
                ForgeBlockSnapshot replacement = ForgeBlockSnapshot.capture(frame.world, entry.x, entry.y, entry.z);
                if (entry.original.sameState(replacement)) continue;
                RECORDER.onNativeChange(cause, ForgeLocation.of(frame.world, entry.x, entry.y, entry.z),
                        entry.original, replacement, frame.breaking);
            }
        } catch (RuntimeException failure) {
            failed(frame, failure);
        }
    }

    /** Called from an injected catch/finally path, so failed operations cannot leak their player context. */
    public static void abort() {
        Frame frame = CURRENT.get();
        if (frame != null) pop(frame);
    }

    private static void pop(Frame frame) {
        if (frame.previous == null) CURRENT.remove(); else CURRENT.set(frame.previous);
        active = frame.previous != null && frame.previous.anyActive;
    }

    private static void failed(Frame frame, RuntimeException failure) {
        frame.cancelled = true;
        LogManager.getLogger("Prism").error("Could not capture a native block operation at {}, {}, {}", frame.x, frame.y, frame.z, failure);
    }

    private static final class Frame {
        private final Frame previous;
        private final EntityPlayer player;
        private final World world;
        private final boolean breaking;
        private final boolean enabled;
        private final boolean anyActive;
        private final int x;
        private final int y;
        private final int z;
        private final Map<Long, Entry> entries = new LinkedHashMap<>();
        private boolean armed;
        private boolean cancelled;

        private Frame(Frame previous, EntityPlayer player, World world, boolean breaking, boolean enabled, int x, int y, int z) {
            this.previous = previous;
            this.player = player;
            this.world = world;
            this.breaking = breaking;
            this.enabled = enabled;
            this.anyActive = enabled || previous != null && previous.anyActive;
            this.armed = !breaking;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private Entry original(int x, int y, int z, ForgeBlockSnapshot snapshot) {
            Entry entry = new Entry(x, y, z, snapshot);
            entries.put(position(x, y, z), entry);
            return entry;
        }
    }

    private static final class Entry {
        private final int x;
        private final int y;
        private final int z;
        private final ForgeBlockSnapshot original;
        private boolean changed;

        private Entry(int x, int y, int z, ForgeBlockSnapshot original) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.original = original;
        }
    }
}
