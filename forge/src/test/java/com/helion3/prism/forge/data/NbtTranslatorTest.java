/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.forge.data;

import com.helion3.prism.util.DataUtil;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

import static org.junit.jupiter.api.Assertions.*;

class NbtTranslatorTest {
    private final NbtTranslator translator = NbtTranslator.getInstance();

    private void assertRoundTrip(NBTTagCompound original) {
        NBTTagCompound before = (NBTTagCompound) original.copy();
        NBTTagCompound restored = translator.translate(translator.translate(original));
        assertEquals(before, original, "Reading NBT must not mutate the input");
        assertEquals(original, restored);
        assertNotSame(original, restored);
    }

    @Test
    void preservesAllScalarTypesAndNativeArrayTypes() {
        NBTTagCompound data = new NBTTagCompound();
        data.setByte("byte", Byte.MIN_VALUE);
        data.setShort("short", Short.MAX_VALUE);
        data.setInteger("int", Integer.MIN_VALUE);
        data.setLong("long", Long.MAX_VALUE);
        data.setFloat("float", 1.25F);
        data.setDouble("double", -2.5D);
        data.setString("string", "example:machine");
        data.setByteArray("bytes", new byte[]{-1, 0, 1});
        data.setIntArray("ints", new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        assertRoundTrip(data);
    }

    @Test
    void preservesNestedCompoundsAndLiteralPunctuationInKeys() {
        NBTTagCompound inner = new NBTTagCompound();
        inner.setLong("energy.with.dots/and:colon", 1234567890123L);
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("machine", inner);
        data.setTag("empty", new NBTTagCompound());
        assertRoundTrip(data);
    }

    @Test
    void readsStringListsUsingThe1710BackingList() {
        NBTTagList strings = new NBTTagList();
        strings.appendTag(new NBTTagString("one"));
        strings.appendTag(new NBTTagString("two"));
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("list", strings);
        assertRoundTrip(data);
        assertEquals(2, strings.tagCount());
    }

    @Test
    void preservesInventoryCompoundLists() {
        NBTTagCompound item = new NBTTagCompound();
        item.setShort("id", (short) 123);
        item.setByte("Count", (byte) 64);
        item.setShort("Damage", (short) 7);
        NBTTagCompound itemTag = new NBTTagCompound();
        itemTag.setString("custom.name", "example");
        item.setTag("tag", itemTag);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(item);
        NBTTagCompound machine = new NBTTagCompound();
        machine.setTag("Items", inventory);
        assertRoundTrip(machine);
    }

    @Test
    void handlesNestedListsAndArraysInLists() {
        NBTTagList integers = new NBTTagList();
        integers.appendTag(new NBTTagInt(1));
        integers.appendTag(new NBTTagInt(2));
        NBTTagList nested = new NBTTagList();
        nested.appendTag(integers);
        NBTTagList arrays = new NBTTagList();
        arrays.appendTag(new NBTTagByteArray(new byte[]{1, 2}));
        arrays.appendTag(new NBTTagByteArray(new byte[]{3, 4}));
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("nested", nested);
        data.setTag("arrays", arrays);
        data.setTag("empty", new NBTTagList());
        assertRoundTrip(data);
    }

    @Test
    void retainsTheOriginalBooleanMarkerBehaviourWithoutASpongeGame() {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        DataContainer view = DataContainer.createNew();
        view.set(DataQuery.of("enabled"), true);
        NBTTagCompound nativeData = translator.translate(view);
        assertTrue(nativeData.getBoolean("enabled$Boolean"));
        assertTrue(translator.translate(nativeData).getBoolean(DataQuery.of("enabled")).get());
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalPrismJsonCodecRoundTripsMachineValuesAndInventoryContents() {
        NBTTagCompound item = new NBTTagCompound();
        item.setShort("id", (short) 123);
        item.setByte("Count", (byte) 64);
        item.setShort("Damage", (short) 7);
        NBTTagList items = new NBTTagList();
        items.appendTag(item);
        NBTTagCompound machine = new NBTTagCompound();
        machine.setLong("energy", 1234567890123L);
        machine.setTag("Items", items);
        machine.setByteArray("bytes", new byte[]{-1, 0, 1});
        machine.setIntArray("ints", new int[]{-2, 0, 2});

        DataContainer view = translator.translate(machine);
        NBTTagCompound restored = translator.translate(DataUtil.dataViewFromJson(DataUtil.jsonFromDataView(view)));

        // The original JSON codec normalizes numeric widths; check Minecraft's value reads.
        assertEquals(1234567890123L, restored.getLong("energy"));
        assertArrayEquals(machine.getByteArray("bytes"), restored.getByteArray("bytes"));
        assertArrayEquals(machine.getIntArray("ints"), restored.getIntArray("ints"));
        NBTTagCompound restoredItem = restored.getTagList("Items", 10).getCompoundTagAt(0);
        assertEquals(123, restoredItem.getShort("id"));
        assertEquals(64, restoredItem.getByte("Count"));
        assertEquals(7, restoredItem.getShort("Damage"));
    }
}
