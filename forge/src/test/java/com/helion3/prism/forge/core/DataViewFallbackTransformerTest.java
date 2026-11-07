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

import com.google.gson.JsonObject;
import com.helion3.prism.forge.data.NbtTranslator;
import com.helion3.prism.util.DataUtil;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataView;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataViewFallbackTransformerTest {
    private static final String VIEW = "org.spongepowered.api.data.MemoryDataView";
    private static final String CONTAINER = "org.spongepowered.api.data.MemoryDataContainer";
    @TempDir
    Path directory;

    private static byte[] original(String name) throws Exception {
        try (InputStream input = DataViewFallbackTransformerTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static String text(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        StringWriter output = new StringWriter();
        text.print(new PrintWriter(output));
        return output.toString();
    }

    @Test
    void changesExactlyTheTwoGuardedInvocationsAndPreservesOtherMethodsAndFrames() throws Exception {
        byte[] bytes = original(VIEW);
        byte[] patched = new DataViewFallbackTransformer().transform(VIEW, VIEW, bytes);
        assertNotSame(bytes, patched);
        ClassNode before = read(bytes);
        ClassNode after = read(patched);
        assertEquals(before.methods.size(), after.methods.size());
        int changed = 0;
        for (int index = 0; index < before.methods.size(); index++) {
            MethodNode oldMethod = before.methods.get(index);
            MethodNode newMethod = after.methods.get(index);
            String oldText = text(oldMethod);
            String newText = text(newMethod);
            if (oldText.equals(newText)) continue;
            changed++;
            assertTrue(oldMethod.name.equals("set") || oldMethod.name.equals("setCollection"));
            assertEquals(oldText.replace("INVOKESTATIC org/spongepowered/api/Sponge.getDataManager ()Lorg/spongepowered/api/data/DataManager;", "ACONST_NULL"), newText);
        }
        assertEquals(2, changed);
        StringWriter errors = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(patched), getClass().getClassLoader(), false, new PrintWriter(errors));
        assertEquals("", errors.toString());
    }

    @Test
    void unrelatedClassesAndMissingInputAreUntouched() throws Exception {
        DataViewFallbackTransformer transformer = new DataViewFallbackTransformer();
        byte[] bytes = original(CONTAINER);
        assertSame(bytes, transformer.transform(CONTAINER, CONTAINER, bytes));
        assertNull(transformer.transform(VIEW, VIEW, null));
    }

    @Test
    void unexpectedMethodOrFallbackShapeKeepsAllOriginalBytes() throws Exception {
        for (boolean changeHandler : new boolean[]{false, true}) {
            ClassNode node = read(original(VIEW));
            for (Object candidate : node.methods) {
                MethodNode method = (MethodNode) candidate;
                if (!method.name.equals("setCollection")) continue;
                if (changeHandler) {
                    method.tryCatchBlocks.getFirst().type = "java/lang/RuntimeException";
                } else {
                    method.name = "differentCollectionImplementation";
                }
            }
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] unexpected = writer.toByteArray();
            assertSame(unexpected, new DataViewFallbackTransformer().transform(VIEW, VIEW, unexpected));
        }
    }

    private static Object snapshot(Object value) {
        if (value instanceof DataView view) {
            Map<Object, Object> values = new LinkedHashMap<>();
            view.getValues(false).forEach((key, entry) -> values.put(key.getParts(), snapshot(entry)));
            return List.of("view", values);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> values = new LinkedHashMap<>();
            map.forEach((key, entry) -> values.put(key, snapshot(entry)));
            return List.of("map", values);
        }
        if (value instanceof Iterable<?> entries) {
            List<Object> values = new ArrayList<>();
            entries.forEach(entry -> values.add(snapshot(entry)));
            return List.of("list", values);
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) values.add(snapshot(Array.get(value, index)));
            return List.of(value.getClass().getName(), values);
        }
        return List.of(value.getClass().getName(), value instanceof Date date ? date.getTime() : value);
    }

    private static List<Object> exercise(Containers classes, DataView.SafetyMode mode) {
        DataContainer root = classes.create(mode);
        DataContainer tile = classes.create(mode).set(DataQuery.of("energy"), 9007199254740993L);
        byte[] bytes = new byte[]{-128, 0, 127};
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("literal:key.with.dots", List.of(bytes, tile));
        List<Object> list = new ArrayList<>(List.of(tile, map, new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}));
        root.set(DataQuery.of("byte"), (byte) -1).set(DataQuery.of("short"), (short) 32767)
                .set(DataQuery.of("long"), Long.MIN_VALUE).set(DataQuery.of("float"), 1.25F)
                .set(DataQuery.of("double"), -2.5D).set(DataQuery.of("boolean"), true)
                .set(DataQuery.of("date"), new Date(1234567)).set(DataQuery.of("bytes"), bytes)
                .set(DataQuery.of("longs"), new long[]{Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE})
                .set(DataQuery.of("map"), map).set(DataQuery.of("inventory"), list)
                .set(DataQuery.of("nested", "value"), "kept");
        List<Object> states = new ArrayList<>();
        states.add(snapshot(root));
        bytes[0] = 23;
        tile.set(DataQuery.of("energy"), 0L);
        map.put("new input key", "added");
        list.clear();
        states.add(snapshot(root));
        DataContainer copy = root.copy(mode);
        root.createView(DataQuery.of("another")).set(DataQuery.of("value"), List.of("one", "two"));
        ((byte[]) root.get(DataQuery.of("bytes")).orElseThrow())[1] = 42;
        root.remove(DataQuery.of("nested", "value"));
        states.add(snapshot(root));
        states.add(snapshot(copy));
        return states;
    }

    @ParameterizedTest
    @EnumSource(DataView.SafetyMode.class)
    void valuesAndInputReadAndCopyOwnershipMatchTheUntouchedLibrary(DataView.SafetyMode mode) {
        assertEquals(exercise(new Containers(false), mode), exercise(new Containers(true), mode));
    }

    @Test
    void machineNbtAndOriginalJsonHaveIdenticalOutput() {
        NBTTagCompound item = new NBTTagCompound();
        item.setShort("id", (short) 123);
        item.setByte("Count", (byte) 64);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("literal:key.with.dots", "kept");
        payload.setByteArray("bytes", new byte[]{-1, 0, 1});
        item.setTag("tag", payload);
        NBTTagList items = new NBTTagList();
        items.appendTag(item);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("Items", items);
        nbt.setLong("energy", 9007199254740993L);
        nbt.setIntArray("ints", new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE});
        JsonObject originalJson = null;
        for (boolean patched : new boolean[]{false, true}) {
            DataContainer root = new Containers(patched).create(DataView.SafetyMode.ALL_DATA_CLONED);
            root.set(DataQuery.of("snapshot"), NbtTranslator.getInstance().translate(nbt));
            DataView tile = root.getView(DataQuery.of("snapshot")).orElseThrow();
            assertEquals(nbt, NbtTranslator.getInstance().translate(tile));
            JsonObject json = DataUtil.jsonFromDataView(root);
            if (patched) assertEquals(originalJson, json); else originalJson = json;
        }
    }

    @Test
    void removesLookupExceptionsWithoutInstallingSpongeServicesOrChangingOtherFailures() throws Exception {
        assertThrows(IllegalStateException.class, Sponge::getDataManager);
        assertThrows(IllegalStateException.class, Sponge::getGame);
        assertTrue(lookupFailures(false) > 0, "Untouched API must reproduce the caught service-lookup exceptions");
        assertEquals(0, lookupFailures(true), "Adapted container writes must not create service-lookup exceptions");
        for (boolean patched : new boolean[]{false, true}) {
            DataContainer root = new Containers(patched).create(DataView.SafetyMode.ALL_DATA_CLONED);
            root.createView(DataQuery.of("unsupported"));
            assertThrows(IllegalStateException.class, () -> root.getObject(DataQuery.of("unsupported"), DataView.class));
            assertThrows(NullPointerException.class, () -> root.set(DataQuery.of("invalid"), null));
        }
        assertThrows(IllegalStateException.class, Sponge::getDataManager);
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    private long lookupFailures(boolean patched) throws Exception {
        DataContainer root = new Containers(patched).create(DataView.SafetyMode.ALL_DATA_CLONED);
        Path path = directory.resolve(patched ? "adapted.jfr" : "original.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JavaExceptionThrow").withStackTrace();
            recording.start();
            for (int index = 0; index < 10; index++) {
                root.set(DataQuery.of("value"), index);
                root.set(DataQuery.of("items"), List.of(index));
            }
            recording.stop();
            recording.dump(path);
        }
        return RecordingFile.readAllEvents(path).stream().filter(event -> {
            if (!event.getEventType().getName().equals("jdk.JavaExceptionThrow") || event.getStackTrace() == null
                    || event.getThread().getJavaThreadId() != Thread.currentThread().threadId()) return false;
            return event.getStackTrace().getFrames().stream().anyMatch(frame ->
                    frame.getMethod().getType().getName().equals("org.spongepowered.api.Sponge")
                            && frame.getMethod().getName().equals("getDataManager"));
        }).count();
    }

    private static final class Containers extends ClassLoader {
        private final boolean patched;

        private Containers(boolean patched) {
            super(DataViewFallbackTransformerTest.class.getClassLoader());
            this.patched = patched;
        }

        private DataContainer create(DataView.SafetyMode mode) {
            try {
                return (DataContainer) loadClass(CONTAINER).getConstructor(DataView.SafetyMode.class).newInstance(mode);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(VIEW) && !name.equals(CONTAINER)) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    byte[] bytes = original(name);
                    if (patched) bytes = new DataViewFallbackTransformer().transform(name, name, bytes);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (Exception failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
