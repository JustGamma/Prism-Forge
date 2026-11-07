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

package com.helion3.prism.forge.world;

import com.helion3.prism.Prism;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.configuration.Configuration;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForgeBlockSnapshotTest {
    @TempDir
    Path directory;

    private NBTTagCompound machine() {
        NBTTagCompound item = new NBTTagCompound();
        item.setString("id", "example:item");
        item.setByteArray("payload", new byte[]{1, 2, 3});
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(item);
        NBTTagCompound machine = new NBTTagCompound();
        machine.setString("id", "example:machine_tile");
        machine.setInteger("x", -42);
        machine.setInteger("y", 70);
        machine.setInteger("z", 33);
        machine.setLong("energy", 9007199254740993L);
        machine.setTag("Items", inventory);
        return machine;
    }

    @Test
    void frozenSnapshotsAndReturnedContainersDoNotShareMutableTileNbt() {
        NBTTagCompound nbt = machine();
        ForgeBlockSnapshot snapshot = new ForgeBlockSnapshot("example:machine", 7, nbt);
        nbt.setLong("energy", 0L);
        nbt.getTagList("Items", 10).getCompoundTagAt(0).getByteArray("payload")[0] = 99;
        var first = snapshot.toContainer();
        var tile = first.getView(DataQueries.UnsafeData).orElseThrow();
        assertEquals(9007199254740993L, tile.getLong(DataQuery.of("energy")).orElseThrow());
        var item = tile.getViewList(DataQuery.of("Items")).orElseThrow().getFirst();
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) item.get(DataQuery.of("payload")).orElseThrow());
        tile.set(DataQuery.of("energy"), 3L);
        assertEquals(9007199254740993L, snapshot.toContainer().getLong(DataQueries.UnsafeData.then(DataQuery.of("energy"))).orElseThrow());
        assertEquals(7, first.getInt(DataQueries.BlockState.then(DataQueries.Metadata)).orElseThrow());
        assertTrue(snapshot.sameState(new ForgeBlockSnapshot("example:machine", 7, machine())));
        assertFalse(snapshot.sameState(new ForgeBlockSnapshot("example:machine", 6, machine())));
    }

    @Test
    void nativeInputsUseOriginalRecordFieldsAndCoordinateCleanup() throws Exception {
        Field instance = Prism.class.getDeclaredField("instance");
        Field config = Prism.class.getDeclaredField("configuration");
        instance.setAccessible(true);
        config.setAccessible(true);
        Prism previous = Prism.getInstance();
        try {
            Prism prism = new Prism();
            instance.set(null, prism);
            config.set(prism, new Configuration(directory.resolve("prism.conf")));
            UUID id = UUID.randomUUID();
            PrismRecord record = PrismRecord.create().source(Cause.of(EventContext.empty(), "environment"))
                    .blockOriginal(new ForgeBlockSnapshot("example:machine", 7, machine()))
                    .blockReplacement(new ForgeBlockSnapshot("minecraft:air", 0, null))
                    .location(new ForgeLocation(id, -42, 70, 33)).event(PrismEvents.BLOCK_BREAK)
                    .target("example:machine").build();
            var data = record.getDataContainer();
            assertEquals(id.toString(), data.getString(DataQueries.Location.then(DataQueries.WorldUuid)).orElseThrow());
            assertEquals(-42, data.getInt(DataQueries.Location.then(DataQueries.X)).orElseThrow());
            assertEquals("example:machine", data.getString(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType)).orElseThrow());
            assertEquals(7, data.getInt(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.Metadata)).orElseThrow());
            assertFalse(data.contains(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQueries.X)));
            assertFalse(data.contains(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQueries.Y)));
            assertFalse(data.contains(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQueries.Z)));
            assertEquals("minecraft:air", data.getString(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType)).orElseThrow());
        } finally {
            instance.set(null, previous);
        }
    }
}
