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

import com.google.common.cache.LoadingCache;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.SafeTextSerializer;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.util.generator.dummy.DummyObjectProvider;

import java.lang.reflect.Field;

/** Installs only the plain text service needed by the retained API command parser. */
public final class ForgeTextBootstrap {
    private static boolean initialized;

    private ForgeTextBootstrap() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            // API 7.1 has no standalone catalog bootstrap. Populate its existing
            // factory cache before TextSerializers initializes; do not replace
            // final constants, modify the API jar or create a Sponge Game.
            Field factoriesField = DummyObjectProvider.class.getDeclaredField("factories");
            factoriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            LoadingCache<Class<?>, Class<?>> factories =
                    (LoadingCache<Class<?>, Class<?>>) factoriesField.get(null);
            factories.put(SafeTextSerializer.class, ForgePlainTextSerializer.class);
            if (!(TextSerializers.PLAIN instanceof ForgePlainTextSerializer)
                    || !"Prism plain bootstrap".equals(Text.of("Prism plain bootstrap").toPlain())) {
                throw new IllegalStateException("The API plain serializer was initialized before the Forge host binding");
            }
            initialized = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException("Cannot initialize Prism's pinned Sponge API 7.1 plain text service", ex);
        }
    }
}
