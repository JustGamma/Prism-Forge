/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
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
