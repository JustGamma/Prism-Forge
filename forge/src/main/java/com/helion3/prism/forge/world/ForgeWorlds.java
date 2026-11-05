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

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.Optional;
import java.util.UUID;

/** Keeps the original UUID query field stable across native dimension reloads and restarts. */
public final class ForgeWorlds {
    private static final String DATA_NAME = "prism_world";

    private ForgeWorlds() {}

    public static UUID id(World world) {
        if (world.isRemote) throw new IllegalArgumentException("Prism locations require a server world");
        WorldIdentity identity = (WorldIdentity) world.perWorldStorage.loadData(WorldIdentity.class, DATA_NAME);
        if (identity == null) {
            identity = new WorldIdentity(DATA_NAME);
            identity.id = UUID.randomUUID();
            identity.markDirty();
            world.perWorldStorage.setData(DATA_NAME, identity);
        }
        if (identity.id == null) throw new IllegalStateException("Invalid Prism world identity for dimension " + world.provider.dimensionId);
        return identity.id;
    }

    public static Optional<WorldServer> find(UUID id) {
        for (WorldServer world : DimensionManager.getWorlds()) {
            WorldIdentity identity = (WorldIdentity) world.perWorldStorage.loadData(WorldIdentity.class, DATA_NAME);
            if (identity != null && id.equals(identity.id)) return Optional.of(world);
        }
        return Optional.empty();
    }

    public static String name(UUID id) {
        return find(id).map(world -> world.getWorldInfo().getWorldName()
                + (world.provider.dimensionId == 0 ? "" : "/" + world.provider.getDimensionName())).orElse(id.toString());
    }

    public static final class WorldIdentity extends WorldSavedData {
        private UUID id;

        public WorldIdentity(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound data) {
            id = UUID.fromString(data.getString("uuid"));
        }

        @Override
        public void writeToNBT(NBTTagCompound data) {
            if (id == null) throw new IllegalStateException("Cannot save an invalid Prism world identity");
            data.setString("uuid", id.toString());
        }
    }
}
