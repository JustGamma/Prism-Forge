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
package com.helion3.prism.forge.command;

import net.minecraft.command.ICommandSender;

import java.util.Objects;

/** Connects Prism's existing permission nodes to a native Forge permission provider. */
public final class ForgePermissions {
    @FunctionalInterface
    public interface Provider {
        boolean hasPermission(ICommandSender sender, String permission);
    }

    private static volatile Provider provider;

    private ForgePermissions() {
    }

    /**
     * Registers the server's permission provider. The provider resolves exact nodes,
     * wildcard nodes and group inheritance; Prism passes the original node unchanged.
     */
    public static synchronized void register(Provider permissionProvider) {
        Objects.requireNonNull(permissionProvider, "permissionProvider");
        if (provider != null && provider != permissionProvider) {
            throw new IllegalStateException("A Prism permission provider is already registered");
        }
        provider = permissionProvider;
    }

    public static synchronized void unregister(Provider permissionProvider) {
        if (provider == permissionProvider) {
            provider = null;
        }
    }

    /** Without a provider, use Forge's native operator level 4 check for each node. */
    public static boolean hasPermission(ICommandSender sender, String permission) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(permission, "permission");
        Provider current = provider;
        return current == null ? sender.canCommandSenderUseCommand(4, permission)
                : current.hasPermission(sender, permission);
    }
}
