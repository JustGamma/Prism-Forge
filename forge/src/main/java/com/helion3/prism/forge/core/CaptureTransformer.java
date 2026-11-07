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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Preserves existing methods and frames, inserting observations around native player mutations. */
public final class CaptureTransformer implements IClassTransformer, Opcodes {
    private static final String CAPTURE = "com/helion3/prism/forge/capture/ForgeBlockCapture";
    private static final String WORLD = "net/minecraft/world/World";
    private static final String SNAPSHOT = "net/minecraftforge/common/util/BlockSnapshot";
    private static final String BLOCK_EVENT = "net/minecraftforge/event/world/BlockEvent$";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !("net.minecraft.world.World".equals(transformedName)
                || "net.minecraft.server.management.ItemInWorldManager".equals(transformedName)
                || "net.minecraftforge.common.ForgeHooks".equals(transformedName))) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        for (MethodNode method : (Iterable<MethodNode>) node.methods) {
            if (WORLD.equals(node.name) && ("setBlock".equals(method.name) || "func_147465_d".equals(method.name))
                    && "(IIILnet/minecraft/block/Block;II)Z".equals(method.desc)) {
                world(method);
                changed++;
            } else if (transformedName.endsWith("ItemInWorldManager")
                    && ("tryHarvestBlock".equals(method.name) || "func_73084_b".equals(method.name))
                    && "(III)Z".equals(method.desc)) {
                harvest(node, method);
                changed++;
            } else if (transformedName.endsWith("ForgeHooks") && "onPlaceItemIntoWorld".equals(method.name)) {
                place(method);
                changed++;
            }
        }
        if (changed != 1) throw new IllegalStateException("Prism could not identify exactly one native capture target in " + transformedName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, CAPTURE, name, descriptor, false);
    }

    private static void world(MethodNode method) {
        InsnList begin = new InsnList();
        for (int index = 0; index <= 5; index++) begin.add(new VarInsnNode(index == 0 || index == 4 ? ALOAD : ILOAD, index));
        begin.add(call("beforeWrite", "(L" + WORLD + ";IIILnet/minecraft/block/Block;I)V"));
        method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == IRETURN) {
                InsnList after = new InsnList();
                after.add(new InsnNode(DUP));
                after.add(new VarInsnNode(ALOAD, 0));
                for (int index = 1; index <= 3; index++) after.add(new VarInsnNode(ILOAD, index));
                after.add(call("afterWrite", "(ZL" + WORLD + ";III)V"));
                method.instructions.insertBefore(instruction, after);
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode invocation = (MethodInsnNode) instruction;
                if (SNAPSHOT.equals(invocation.owner) && "getBlockSnapshot".equals(invocation.name)) {
                    InsnList after = new InsnList();
                    after.add(new InsnNode(DUP));
                    after.add(call("placementSnapshot", "(L" + SNAPSHOT + ";)V"));
                    method.instructions.insert(instruction, after);
                }
            }
        }
    }

    private static void harvest(ClassNode owner, MethodNode method) {
        FieldNode player = field(owner, "Lnet/minecraft/entity/player/EntityPlayerMP;");
        FieldNode world = field(owner, "Lnet/minecraft/world/World;");
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(ALOAD, 0));
        begin.add(new FieldInsnNode(GETFIELD, owner.name, player.name, player.desc));
        begin.add(new VarInsnNode(ALOAD, 0));
        begin.add(new FieldInsnNode(GETFIELD, owner.name, world.name, world.desc));
        for (int index = 1; index <= 3; index++) begin.add(new VarInsnNode(ILOAD, index));
        begin.add(call("beginBreak", "(Lnet/minecraft/entity/player/EntityPlayerMP;L" + WORLD + ";III)V"));
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode && "onBlockBreakEvent".equals(((MethodInsnNode) instruction).name)) {
                InsnList decision = new InsnList();
                decision.add(new InsnNode(DUP));
                decision.add(call("breakDecision", "(L" + BLOCK_EVENT + "BreakEvent;)V"));
                method.instructions.insert(instruction, decision);
            }
        }
        operation(method, begin);
    }

    private static FieldNode field(ClassNode owner, String descriptor) {
        FieldNode found = null;
        for (FieldNode field : (Iterable<FieldNode>) owner.fields) {
            if (!descriptor.equals(field.desc)) continue;
            if (found != null) throw new IllegalStateException("Ambiguous Prism capture field in " + owner.name);
            found = field;
        }
        if (found == null) throw new IllegalStateException("Missing Prism capture field in " + owner.name + ": " + descriptor);
        return found;
    }

    private static void place(MethodNode method) {
        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(ALOAD, 1));
        begin.add(new VarInsnNode(ALOAD, 2));
        begin.add(call("beginPlace", "(Lnet/minecraft/entity/player/EntityPlayer;L" + WORLD + ";)V"));
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode invocation = (MethodInsnNode) instruction;
            if ("onPlayerBlockPlace".equals(invocation.name) || "onPlayerMultiBlockPlace".equals(invocation.name)) {
                InsnList decision = new InsnList();
                decision.add(new InsnNode(DUP));
                // MultiPlaceEvent is accepted through its PlaceEvent superclass by the helper.
                decision.add(call("placeDecision", "(L" + BLOCK_EVENT + "PlaceEvent;)V"));
                method.instructions.insert(instruction, decision);
            } else if ("clear".equals(invocation.name)) {
                AbstractInsnNode previous = instruction.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (previous instanceof FieldInsnNode && "capturedBlockSnapshots".equals(((FieldInsnNode) previous).name)) {
                    InsnList before = new InsnList();
                    before.add(new InsnNode(DUP));
                    before.add(call("placementSnapshots", "(Ljava/util/List;)V"));
                    method.instructions.insertBefore(instruction, before);
                }
            }
        }
        operation(method, begin);
    }

    private static void operation(MethodNode method, InsnList begin) {
        LabelNode start = new LabelNode();
        begin.add(start);
        method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == IRETURN) method.instructions.insertBefore(instruction, call("finish", "()V"));
        }
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(end);
        method.instructions.add(handler);
        // No added locals or frame recomputation/class loading is needed by this catch/finally path.
        method.instructions.add(new FrameNode(F_FULL, 0, new Object[0], 1, new Object[]{"java/lang/Throwable"}));
        method.instructions.add(call("abort", "()V"));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }
}
