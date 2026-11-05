/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.util;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataView;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DataUtilTest {

    @Test
    void originalJsonCodecPreservesNestedSnapshotDataAndPrimitiveArrayTypes() {
        DataContainer nbt = DataContainer.createNew();
        nbt.set(DataQuery.of("bytes"), new byte[]{Byte.MIN_VALUE, 0, Byte.MAX_VALUE});
        nbt.set(DataQuery.of("ints"), new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        nbt.set(DataQuery.of("longs"), new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE});
        nbt.set(DataQuery.of("energy"), 1234567890123L);
        nbt.set(DataQuery.of("Items"), Arrays.asList(DataContainer.createNew()
                .set(DataQuery.of("id"), "example:item")
                .set(DataQuery.of("Count"), 64)));

        DataContainer record = DataContainer.createNew();
        record.set(DataQueries.EventName, "break");
        record.set(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "example:machine");
        record.set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData), nbt);
        record.set(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "minecraft:air");
        record.set(DataQueries.Location, DataContainer.createNew()
                .set(DataQueries.WorldUuid, "fresh-world")
                .set(DataQueries.X, -42).set(DataQueries.Y, 70).set(DataQueries.Z, 15));

        JsonObject json = DataUtil.jsonFromDataView(record);
        DataView decoded = DataUtil.dataViewFromJson(json);
        DataView decodedNbt = decoded.getView(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)).orElseThrow();

        assertEquals(json, DataUtil.jsonFromDataView(decoded));
        assertArrayEquals(new byte[]{Byte.MIN_VALUE, 0, Byte.MAX_VALUE}, (byte[]) decodedNbt.get(DataQuery.of("bytes")).orElseThrow());
        assertArrayEquals(new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, (int[]) decodedNbt.get(DataQuery.of("ints")).orElseThrow());
        assertArrayEquals(new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE}, (long[]) decodedNbt.get(DataQuery.of("longs")).orElseThrow());
        assertEquals(1234567890123L, decodedNbt.getLong(DataQuery.of("energy")).orElseThrow());
        assertEquals("example:item", decodedNbt.getViewList(DataQuery.of("Items")).orElseThrow()
                .get(0).getString(DataQuery.of("id")).orElseThrow());
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalEscapingSeparatesLiteralPunctuationFromNestedPaths() {
        DataQuery literal = DataQuery.of("Location.X/with:punctuation");
        DataQuery nested = DataQuery.of("Location", "X/with:punctuation");
        assertNotEquals(DataUtil.escapeQuery(literal), DataUtil.escapeQuery(nested));
        assertEquals(literal, DataUtil.unescapeQuery(DataUtil.escapeQuery(literal)));
        assertEquals(nested, DataUtil.unescapeQuery(DataUtil.escapeQuery(nested)));

        DataContainer original = DataContainer.createNew();
        original.set(literal, "literal value");
        original.set(nested, "nested value");
        DataView decoded = DataUtil.dataViewFromJson(DataUtil.jsonFromDataView(original));
        assertEquals("literal value", decoded.getString(literal).orElseThrow());
        assertEquals("nested value", decoded.getString(nested).orElseThrow());
    }
}
