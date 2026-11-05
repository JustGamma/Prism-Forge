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

package com.helion3.prism.forge;

import com.helion3.prism.Prism;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerProfileCache;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Native profile service boundary; cache reads/writes stay on the server thread, HTTP does not. */
public final class ForgeProfiles {
    private static final Field NAMES = ReflectionHelper.findField(PlayerProfileCache.class, "field_152661_c");

    private ForgeProfiles() {}

    public static CompletableFuture<UUID> uniqueId(String name) {
        try {
            return CompletableFuture.completedFuture(UUID.fromString(name));
        } catch (IllegalArgumentException ignored) {
            // The original parameter also accepts player names.
        }
        return ForgeServerTasks.submit(() -> cachedName(name)).thenCompose(cached -> {
            if (cached != null) return CompletableFuture.completedFuture(cached);
            return lookupName(name);
        }).thenCompose(ForgeProfiles::remember).thenApply(GameProfile::getId);
    }

    private static GameProfile cachedName(String name) throws IllegalAccessException {
        // The public name getter can make a blocking HTTP request on an expired cache miss.
        // Read the existing cache here; missing entries are resolved asynchronously below.
        Map<?, ?> names = (Map<?, ?>) NAMES.get(server().func_152358_ax());
        Object entry = names.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) return null;
        Field expiry = ReflectionHelper.findField(entry.getClass(), "field_152673_c");
        if (((Date) expiry.get(entry)).getTime() <= System.currentTimeMillis()) return null;
        Field profile = ReflectionHelper.findField(entry.getClass(), "field_152672_b");
        return (GameProfile) profile.get(entry);
    }

    private static CompletableFuture<GameProfile> lookupName(String name) {
        MinecraftServer server = server();
        CompletableFuture<GameProfile> result = new CompletableFuture<>();
        try {
            Prism.getInstance().getScheduler().execute("PrismProfileLookup", () -> {
                try {
                    String normalized = name.toLowerCase(Locale.ROOT);
                    server.func_152359_aw().findProfilesByNames(new String[]{normalized}, Agent.MINECRAFT,
                            new ProfileLookupCallback() {
                                @Override
                                public void onProfileLookupSucceeded(GameProfile profile) {
                                    result.complete(profile);
                                }

                                @Override
                                public void onProfileLookupFailed(GameProfile profile, Exception failure) {
                                    if (!server.isServerInOnlineMode()) {
                                        UUID id = EntityPlayer.func_146094_a(new GameProfile(null, normalized));
                                        result.complete(new GameProfile(id, normalized));
                                    } else {
                                        result.completeExceptionally(new IllegalArgumentException("Unknown player: " + name, failure));
                                    }
                                }
                            });
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static CompletableFuture<GameProfile> remember(GameProfile profile) {
        return ForgeServerTasks.submit(() -> {
            if (profile.getId() == null) throw new IllegalArgumentException("Profile has no UUID");
            if (profile.getName() != null && !profile.getName().isEmpty()) server().func_152358_ax().func_152649_a(profile);
            return profile;
        });
    }

    public static CompletableFuture<Map<UUID, String>> names(Collection<UUID> ids) {
        if (ids.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        List<UUID> requested = new ArrayList<>(ids);
        return ForgeServerTasks.submit(() -> {
            Map<UUID, String> names = new ConcurrentHashMap<>();
            for (UUID id : requested) {
                GameProfile profile = server().func_152358_ax().func_152652_a(id);
                if (profile != null && profile.getName() != null) names.put(id, profile.getName());
            }
            return names;
        }).thenCompose(names -> {
            List<CompletableFuture<?>> lookups = new ArrayList<>();
            for (UUID id : requested) {
                if (names.containsKey(id)) continue;
                lookups.add(async(() -> server().func_147130_as().fillProfileProperties(new GameProfile(id, null), false))
                        .thenCompose(profile -> profile == null || profile.getName() == null
                                ? CompletableFuture.completedFuture(profile) : remember(profile))
                        .handle((profile, failure) -> {
                            if (failure == null && profile != null && profile.getName() != null) {
                                names.put(id, profile.getName());
                            }
                            return null;
                        }));
            }
            return CompletableFuture.allOf(lookups.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> names);
        });
    }

    private static <T> CompletableFuture<T> async(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Prism.getInstance().getScheduler().execute("PrismProfileLookup", () -> {
                try {
                    result.complete(action.call());
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static MinecraftServer server() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) throw new IllegalStateException("Minecraft server is not running");
        return server;
    }
}
