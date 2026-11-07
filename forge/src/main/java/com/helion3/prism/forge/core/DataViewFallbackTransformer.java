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

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class DataViewFallbackTransformer implements IClassTransformer {
    private static final String TARGET = "org.spongepowered.api.data.MemoryDataView";
    private static final String MANAGER = "()Lorg/spongepowered/api/data/DataManager;";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        MethodNode setter = method(node, "set", "(Lorg/spongepowered/api/data/DataQuery;Ljava/lang/Object;)Lorg/spongepowered/api/data/DataView;");
        MethodNode collection = method(node, "setCollection", "(Ljava/lang/String;Ljava/util/Collection;)V");
        MethodInsnNode setterLookup = guardedLookup(setter);
        MethodInsnNode collectionLookup = guardedLookup(collection);
        if (setterLookup == null || collectionLookup == null) {
            LogManager.getLogger("Prism").warn("Sponge API container fallback patch was not applied: expected two guarded lookups were not found. Keeping the original container implementation.");
            return bytes;
        }
        setter.instructions.set(setterLookup, new InsnNode(Opcodes.ACONST_NULL));
        collection.instructions.set(collectionLookup, new InsnNode(Opcodes.ACONST_NULL));
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        LogManager.getLogger("Prism").info("Applied Sponge API container fallback patch to two guarded service lookups.");
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        MethodNode found = null;
        for (Object candidate : node.methods) {
            MethodNode method = (MethodNode) candidate;
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static MethodInsnNode guardedLookup(MethodNode method) {
        if (method == null) return null;
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !"org/spongepowered/api/Sponge".equals(call.owner)
                    || !"getDataManager".equals(call.name) || !MANAGER.equals(call.desc)) continue;
            if (found != null) return null;
            found = call;
        }
        if (found == null || !(nextCode(found) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) return null;
        int callIndex = method.instructions.indexOf(found);
        for (Object candidate : method.tryCatchBlocks) {
            TryCatchBlockNode handler = (TryCatchBlockNode) candidate;
            if (!"java/lang/Exception".equals(handler.type)
                    || method.instructions.indexOf(handler.start) > callIndex
                    || method.instructions.indexOf(handler.end) <= callIndex) continue;
            AbstractInsnNode caught = nextCode(handler.handler);
            AbstractInsnNode fallback = nextCode(caught);
            AbstractInsnNode assigned = nextCode(fallback);
            if (caught != null && caught.getOpcode() == Opcodes.ASTORE
                    && fallback != null && fallback.getOpcode() == Opcodes.ACONST_NULL
                    && assigned instanceof VarInsnNode fallbackStore && fallbackStore.getOpcode() == Opcodes.ASTORE
                    && fallbackStore.var == store.var) return found;
        }
        return null;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do {
            instruction = instruction.getNext();
        } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
