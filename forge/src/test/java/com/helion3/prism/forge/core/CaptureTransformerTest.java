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

package com.helion3.prism.forge.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class CaptureTransformerTest {
    @Test
    void transformedRealSdkClassesPassBytecodeVerification() throws Exception {
        for (String name : new String[]{"net.minecraft.world.World", "net.minecraft.server.management.ItemInWorldManager",
                "net.minecraftforge.common.ForgeHooks"}) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                assertNotNull(stream, name);
                byte[] transformed = new CaptureTransformer().transform(name, name, stream.readAllBytes());
                StringWriter errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(transformed), getClass().getClassLoader(), false, new PrintWriter(errors));
                assertEquals("", errors.toString(), name);
            }
        }
    }
}
