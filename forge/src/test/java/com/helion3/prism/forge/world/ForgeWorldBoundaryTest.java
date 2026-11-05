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

import com.helion3.prism.api.parameters.ParameterRadius;
import com.helion3.prism.api.query.Query;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.forge.ForgeProfiles;
import com.helion3.prism.forge.ForgeServerTasks;
import com.helion3.prism.forge.command.ForgeCommandSource;
import com.helion3.prism.util.DataQueries;
import net.minecraft.command.ICommandSender;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ForgeWorldBoundaryTest {
    @Test
    void persistentIdentityRoundTripsAndRejectsMissingIdentity() {
        UUID id = UUID.randomUUID();
        NBTTagCompound input = new NBTTagCompound();
        input.setString("uuid", id.toString());
        ForgeWorlds.WorldIdentity identity = new ForgeWorlds.WorldIdentity("prism_world");
        identity.readFromNBT(input);
        NBTTagCompound saved = new NBTTagCompound();
        identity.writeToNBT(saved);
        assertEquals(id.toString(), saved.getString("uuid"));
        assertThrows(IllegalArgumentException.class,
                () -> new ForgeWorlds.WorldIdentity("prism_world").readFromNBT(new NBTTagCompound()));
        assertThrows(IllegalStateException.class,
                () -> new ForgeWorlds.WorldIdentity("prism_world").writeToNBT(new NBTTagCompound()));
    }

    @Test
    void locationContainerKeepsOriginalDetachedFields() {
        UUID id = UUID.randomUUID();
        var data = new ForgeLocation(id, -42, 70, -1).toContainer();
        assertEquals(id.toString(), data.getString(DataQueries.WorldUuid).orElseThrow());
        assertEquals(-42, data.getInt(DataQueries.X).orElseThrow());
        assertEquals(70, data.getInt(DataQueries.Y).orElseThrow());
        assertEquals(-1, data.getInt(DataQueries.Z).orElseThrow());
    }

    @Test
    void consoleRadiusDoesNotResolveANativeWorldOrAddPlayerDefaults() {
        ICommandSender sender = (ICommandSender) Proxy.newProxyInstance(ICommandSender.class.getClassLoader(),
                new Class<?>[]{ICommandSender.class}, (proxy, method, args) -> {
                    throw new AssertionError("Console radius must not access native coordinates: " + method);
                });
        QuerySession session = new QuerySession(ForgeCommandSource.wrap(sender));
        Query query = new Query();
        ParameterRadius radius = new ParameterRadius();
        assertTrue(radius.processDefault(session, query).isEmpty());
        assertTrue(radius.process(session, "r", "5", query).isEmpty());
        assertTrue(query.getConditions().isEmpty());
    }

    @Test
    void uuidAndEmptyProfileRequestsNeedNoRunningServer() {
        UUID id = UUID.randomUUID();
        assertEquals(id, ForgeProfiles.uniqueId(id.toString()).join());
        assertTrue(ForgeProfiles.names(List.of()).join().isEmpty());
    }

    @Test
    void stoppedSchedulerRejectsInsteadOfLeavingPendingFuture() {
        var task = ForgeServerTasks.submit(() -> "must not run");
        assertTrue(task.isCompletedExceptionally());
        CompletionException failure = assertThrows(CompletionException.class, task::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
