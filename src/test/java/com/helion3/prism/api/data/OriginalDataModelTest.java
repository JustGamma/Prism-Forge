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

package com.helion3.prism.api.data;

import com.google.common.collect.Range;
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.Query;
import com.helion3.prism.util.DataQueries;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class OriginalDataModelTest {

    @Test
    void originalDataContainerWorksWithoutASpongeGame() {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        DataContainer original = DataContainer.createNew();
        original.set(DataQueries.EventName, "break");
        original.set(DataQueries.Location.then(DataQueries.WorldUuid), "example-world");
        original.set(DataQueries.Location.then(DataQueries.X), -42);
        original.set(DataQueries.Location.then(DataQueries.Y), 80);
        original.set(DataQueries.Location.then(DataQueries.Z), 17);
        original.set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQuery.of("Items")),
                Arrays.asList(DataContainer.createNew().set(DataQuery.of("Count"), (byte) 64)));

        assertEquals("break", original.getString(DataQueries.EventName).orElseThrow());
        assertEquals(-42, original.getInt(DataQueries.Location.then(DataQueries.X)).orElseThrow());
        assertEquals("example-world", original.getView(DataQueries.Location).orElseThrow()
                .getString(DataQueries.WorldUuid).orElseThrow());
        assertEquals(1, original.getViewList(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)
                .then(DataQuery.of("Items"))).orElseThrow().size());
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalCopyKeepsSnapshotsIndependentIncludingPrimitiveArrays() {
        DataQuery nbt = DataQueries.OriginalBlock.then(DataQueries.UnsafeData);
        DataQuery energy = nbt.then(DataQuery.of("energy"));
        DataQuery bytes = nbt.then(DataQuery.of("bytes"));
        DataContainer original = DataContainer.createNew();
        original.set(energy, 12345L);
        original.set(bytes, new byte[]{1, 2, 3});
        DataContainer copy = original.copy();

        copy.set(energy, 67890L);
        byte[] copiedArray = (byte[]) copy.get(bytes).orElseThrow();
        copiedArray[0] = 99;

        assertEquals(12345L, original.getLong(energy).orElseThrow());
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) original.get(bytes).orElseThrow());
        assertEquals(67890L, copy.getLong(energy).orElseThrow());
    }

    @Test
    void originalQueryAndFieldConditionsKeepTheirPathsAndValues() {
        DataQuery path = DataQueries.Location.then(DataQueries.X);
        Range<Integer> radius = Range.open(-10, 10);
        FieldCondition condition = FieldCondition.of(path, radius);
        Query query = new Query();
        query.addCondition(condition);

        assertEquals("Location.X", path.toString());
        assertEquals(Arrays.asList("Location", "X"), path.getParts());
        assertSame(condition, query.getConditions().get(0));
        assertEquals(MatchRule.BETWEEN, condition.getMatchRule());
        assertSame(radius, condition.getValue());
        assertEquals(1000, query.getLimit());
        query.setLimit(25);
        assertEquals(25, query.getLimit());
    }
}
