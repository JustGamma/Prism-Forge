/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
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
package com.helion3.prism.forge.data;


import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.spongepowered.api.data.DataQuery.of;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataSerializable;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslator;
import org.spongepowered.api.data.persistence.InvalidDataException;
import net.minecraftforge.common.util.Constants.NBT;
import cpw.mods.fml.relauncher.ReflectionHelper;
import java.lang.reflect.Field;

import java.util.List;
import java.util.Map;

/**
 * The original Sponge API-7.1-era NBT translator adapted to Minecraft 1.7.10.
 * Conversion decisions are retained; changes are mappings, tag constants, and
 * list access because 1.7.10 has no public generic list getter.
 * Source: SpongeForge 1.12.2-2768-7.1.0-BETA-3483 sources, MIT licensed.
 */
public final class NbtTranslator implements DataTranslator<NBTTagCompound> {

    private static final Field LIST_ENTRIES = ReflectionHelper.findField(
            NBTTagList.class, "tagList", "field_74747_a");

    @SuppressWarnings("unchecked")
    private static NBTBase getListEntry(NBTTagList list, int index) {
        try {
            return ((List<NBTBase>) LIST_ENTRIES.get(list)).get(index);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Cannot read the Minecraft NBT list", ex);
        }
    }

    private static final NbtTranslator instance = new NbtTranslator();
    private static final TypeToken<NBTTagCompound> TOKEN = TypeToken.of(NBTTagCompound.class);
    public static final String BOOLEAN_IDENTIFER = "$Boolean";

    public static NbtTranslator getInstance() {
        return instance;
    }

    private NbtTranslator() { } // #NOPE

    private static NBTTagCompound containerToCompound(final DataView container) {
        checkNotNull(container);
        NBTTagCompound compound = new NBTTagCompound();
        containerToCompound(container, compound);
        return compound;
    }

    private static void containerToCompound(final DataView container, final NBTTagCompound compound) {
        // We don't need to get deep values since all nested DataViews will be found
        // from the instance of checks.
        checkNotNull(container);
        checkNotNull(compound);
        for (Map.Entry<DataQuery, Object> entry : container.getValues(false).entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey().asString('.');
            if (value instanceof DataView) {
                NBTTagCompound inner = new NBTTagCompound();
                containerToCompound(container.getView(entry.getKey()).get(), inner);
                compound.setTag(key, inner);
            } else if (value instanceof Boolean) {
                compound.setTag(key + BOOLEAN_IDENTIFER, new NBTTagByte(((Boolean) value) ? (byte) 1 : 0));
            } else {
                compound.setTag(key, getBaseFromObject(value));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NBTBase getBaseFromObject(Object value) {
        checkNotNull(value);
        if (value instanceof Boolean) {
            return new NBTTagByte((Boolean) value ? (byte) 1 : 0);
        } else if (value instanceof Byte) {
            return new NBTTagByte((Byte) value);
        } else if (value instanceof Short) {
            return new NBTTagShort((Short) value);
        } else if (value instanceof Integer) {
            return new NBTTagInt((Integer) value);
        } else if (value instanceof Long) {
            return new NBTTagLong((Long) value);
        } else if (value instanceof Float) {
            return new NBTTagFloat((Float) value);
        } else if (value instanceof Double) {
            return new NBTTagDouble((Double) value);
        } else if (value instanceof String) {
            return new NBTTagString((String) value);
        } else if (value.getClass().isArray()) {
            if (value instanceof byte[]) {
                return new NBTTagByteArray((byte[]) value);
            } else if (value instanceof Byte[]) {
                byte[] array = new byte[((Byte[]) value).length];
                int counter = 0;
                for (Byte data : (Byte[]) value) {
                    array[counter++] = data;
                }
                return new NBTTagByteArray(array);
            } else if (value instanceof int[]) {
                return new NBTTagIntArray((int[]) value);
            } else if (value instanceof Integer[]) {
                int[] array = new int[((Integer[]) value).length];
                int counter = 0;
                for (Integer data : (Integer[]) value) {
                    array[counter++] = data;
                }
                return new NBTTagIntArray(array);
            }
        } else if (value instanceof List) {
            NBTTagList list = new NBTTagList();
            for (Object object : (List) value) {
                // Oh hey, we already have a translation already
                // since DataView only supports some primitive types anyways...
                list.appendTag(getBaseFromObject(object));
            }
            return list;
        } else if (value instanceof Map) {
            NBTTagCompound compound = new NBTTagCompound();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (entry.getKey() instanceof DataQuery) {
                    if (entry.getValue() instanceof Boolean) {
                        compound.setBoolean(((DataQuery) entry.getKey()).asString('.') + BOOLEAN_IDENTIFER, (Boolean) entry.getValue());
                    } else {
                        compound.setTag(((DataQuery) entry.getKey()).asString('.'), getBaseFromObject(entry.getValue()));
                    }
                } else if (entry.getKey() instanceof String) {
                    compound.setTag((String) entry.getKey(), getBaseFromObject(entry.getValue()));
                } else {
                    compound.setTag(entry.getKey().toString(), getBaseFromObject(entry.getValue()));
                }
            }
            return compound;
        } else if (value instanceof DataSerializable) {
            return containerToCompound(((DataSerializable) value).toContainer());
        } else if (value instanceof DataView) {
            return containerToCompound((DataView) value);
        }
        throw new IllegalArgumentException("Unable to translate object to NBTBase: " + value);
    }

    private static DataContainer getViewFromCompound(NBTTagCompound compound) {
        checkNotNull(compound);
        DataContainer container = DataContainer.createNew(DataView.SafetyMode.NO_DATA_CLONED);
        NbtTranslator.getInstance().addTo(compound, container);
        return container;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setInternal(NBTBase base, byte type, DataView view, String key) {
        checkNotNull(base);
        checkNotNull(view);
        checkNotNull(key);
        checkArgument(!key.isEmpty());
        checkArgument(type > NBT.TAG_END && type <= NBT.TAG_INT_ARRAY);
        switch (type) {
            case NBT.TAG_BYTE:
                if (key.contains(BOOLEAN_IDENTIFER)) {
                    view.set(of(key.replace(BOOLEAN_IDENTIFER, "")), (((NBTTagByte) base).func_150290_f() != 0));
                } else {
                    view.set(of(key), ((NBTTagByte) base).func_150290_f());
                }
                break;
            case NBT.TAG_SHORT:
                view.set(of(key), ((NBTTagShort) base).func_150289_e());
                break;
            case NBT.TAG_INT:
                view.set(of(key), ((NBTTagInt) base).func_150287_d());
                break;
            case NBT.TAG_LONG:
                view.set(of(key), ((NBTTagLong) base).func_150291_c());
                break;
            case NBT.TAG_FLOAT:
                view.set(of(key), ((NBTTagFloat) base).func_150288_h());
                break;
            case NBT.TAG_DOUBLE:
                view.set(of(key), ((NBTTagDouble) base).func_150286_g());
                break;
            case NBT.TAG_BYTE_ARRAY:
                view.set(of(key), ((NBTTagByteArray) base).func_150292_c());
                break;
            case NBT.TAG_STRING:
                view.set(of(key), ((NBTTagString) base).func_150285_a_());
                break;
            case NBT.TAG_LIST:
                NBTTagList list = (NBTTagList) base;
                byte listType = (byte) list.func_150303_d();
                int count = list.tagCount();
                List objectList = Lists.newArrayListWithCapacity(count);
                for (int i = 0; i < count; i++) {
                    objectList.add(fromTagBase(getListEntry(list, i), listType));
                }
                view.set(of(key), objectList);
                break;
            case NBT.TAG_COMPOUND:
                DataView internalView = view.createView(of(key));
                NBTTagCompound compound = (NBTTagCompound) base;
                for (String internalKey : (java.util.Set<String>) compound.func_150296_c()) {
                    NBTBase internalBase = compound.getTag(internalKey);
                    byte internalType = internalBase.getId();
                    // Basically.... more recursion.
                    // Reasoning: This avoids creating a new DataContainer which would
                    // then be copied in to the owning DataView anyways. We can internally
                    // set the actual data directly to the child view instead.
                    setInternal(internalBase, internalType, internalView, internalKey);
                }
                break;
            case NBT.TAG_INT_ARRAY:
                view.set(of(key), ((NBTTagIntArray) base).func_150302_c());
                break;
            default:
                throw new IllegalArgumentException("Unknown NBT type " + type);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fromTagBase(NBTBase base, byte type) {
        switch (type) {
            case NBT.TAG_BYTE:
                return ((NBTTagByte) base).func_150290_f();
            case NBT.TAG_SHORT:
                return (((NBTTagShort) base)).func_150289_e();
            case NBT.TAG_INT:
                return ((NBTTagInt) base).func_150287_d();
            case NBT.TAG_LONG:
                return ((NBTTagLong) base).func_150291_c();
            case NBT.TAG_FLOAT:
                return ((NBTTagFloat) base).func_150288_h();
            case NBT.TAG_DOUBLE:
                return ((NBTTagDouble) base).func_150286_g();
            case NBT.TAG_BYTE_ARRAY:
                return ((NBTTagByteArray) base).func_150292_c();
            case NBT.TAG_STRING:
                return ((NBTTagString) base).func_150285_a_();
            case NBT.TAG_LIST:
                NBTTagList list = (NBTTagList) base;
                byte listType = (byte) list.func_150303_d();
                int count = list.tagCount();
                List objectList = Lists.newArrayListWithCapacity(count);
                for (int i = 0; i < list.tagCount(); i++) {
                    objectList.add(fromTagBase(getListEntry(list, i), listType));
                }
                return objectList;
            case NBT.TAG_COMPOUND:
                return getViewFromCompound((NBTTagCompound) base);
            case NBT.TAG_INT_ARRAY:
                return ((NBTTagIntArray) base).func_150302_c();
            default :
                return null;
        }
    }

    public NBTTagCompound translateData(DataView container) {
        return NbtTranslator.containerToCompound(container);
    }

    public void translateContainerToData(NBTTagCompound node, DataView container) {
        NbtTranslator.containerToCompound(container, node);
    }

    public DataContainer translateFrom(NBTTagCompound node) {
        return NbtTranslator.getViewFromCompound(node);
    }

    @Override
    public TypeToken<NBTTagCompound> getToken() {
        return TOKEN;
    }

    @Override
    public NBTTagCompound translate(DataView view) throws InvalidDataException {
        return containerToCompound(view);
    }

    @Override
    public DataContainer translate(NBTTagCompound obj) throws InvalidDataException {
        return getViewFromCompound(obj);
    }

    @Override
    public DataView addTo(NBTTagCompound compound, DataView container) {
        for (String key : (java.util.Set<String>) compound.func_150296_c()) {
            NBTBase base = compound.getTag(key);
            byte type = base.getId();
            setInternal(base, type, container, key); // gotta love recursion
        }
        return container;
    }

    @Override
    public String getId() {
        return "sponge:nbt";
    }

    @Override
    public String getName() {
        return "NbtTagCompoundTranslator";
    }
}
