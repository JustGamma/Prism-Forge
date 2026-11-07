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

import com.helion3.prism.forge.data.NbtTranslator;
import com.helion3.prism.util.DataQueries;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataView;

import java.util.Objects;

/** Detached native snapshot input for the original record builder. */
public final class ForgeBlockSnapshot {
    private final String blockId;
    private final int metadata;
    private final NBTTagCompound tileData;

    public ForgeBlockSnapshot(String blockId, int metadata, NBTTagCompound tileData) {
        this.blockId = Objects.requireNonNull(blockId, "blockId");
        this.metadata = metadata;
        this.tileData = tileData == null ? null : (NBTTagCompound) tileData.copy();
    }

    public static ForgeBlockSnapshot capture(World world, int x, int y, int z) {
        return from(net.minecraftforge.common.util.BlockSnapshot.getBlockSnapshot(world, x, y, z));
    }

    public static ForgeBlockSnapshot from(net.minecraftforge.common.util.BlockSnapshot snapshot) {
        NBTTagCompound serialized = new NBTTagCompound();
        snapshot.writeToNBT(serialized);
        return new ForgeBlockSnapshot(snapshot.blockIdentifier.toString(), snapshot.meta,
                serialized.getBoolean("hasTE") ? serialized.getCompoundTag("tileEntity") : null);
    }

    public String getBlockId() {
        return blockId;
    }

    public int getMetadata() {
        return metadata;
    }

    public boolean sameState(ForgeBlockSnapshot other) {
        return metadata == other.metadata && blockId.equals(other.blockId) && Objects.equals(tileData, other.tileData);
    }

    public DataContainer toContainer() {
        DataContainer data = DataContainer.createNew(DataView.SafetyMode.NO_DATA_CLONED);
        data.set(DataQueries.ContentVersion, 1);
        data.set(DataQueries.BlockState, DataContainer.createNew(DataView.SafetyMode.NO_DATA_CLONED)
                .set(DataQueries.ContentVersion, 1).set(DataQueries.BlockType, blockId)
                .set(DataQueries.Metadata, metadata));
        if (tileData != null) {
            // The returned record owns its NBT data; it cannot mutate this frozen snapshot.
            data.set(DataQueries.UnsafeData, NbtTranslator.getInstance().translate((NBTTagCompound) tileData.copy()));
        }
        return data;
    }
}
