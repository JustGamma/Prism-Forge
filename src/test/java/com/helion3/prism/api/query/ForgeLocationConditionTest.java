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
package com.helion3.prism.api.query;

import com.google.common.collect.Range;
import com.helion3.prism.forge.world.ForgeLocation;
import com.helion3.prism.util.DataQueries;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.extent.Extent;

import java.lang.ref.Reference;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForgeLocationConditionTest {
    @Test
    void exactLocationRetainsTheUuidAndAllSignedCoordinates() {
        UUID world = UUID.fromString("066733a1-6b08-4fb9-9cd8-26b5a2af1413");
        ConditionGroup group = ConditionGroup.from(new ForgeLocation(world, -42, 70, 33));
        List<Condition> conditions = group.getConditions();
        assertEquals(ConditionGroup.Operator.AND, group.getOperator());
        assertEquals(4, conditions.size());
        assertField(conditions.get(0), DataQueries.Location.then(DataQueries.WorldUuid).toString(), MatchRule.EQUALS, world.toString());
        assertField(conditions.get(1), "Location.X", MatchRule.EQUALS, -42);
        assertField(conditions.get(2), "Location.Y", MatchRule.EQUALS, 70);
        assertField(conditions.get(3), "Location.Z", MatchRule.EQUALS, 33);
        assertSameConditions(originalConditions(world, null), group);
    }

    @Test
    void radiusPreservesOriginalOpenBoundsAndDimensionIsolation() {
        UUID world = UUID.fromString("066733a1-6b08-4fb9-9cd8-26b5a2af1413");
        ConditionGroup group = ConditionGroup.from(new ForgeLocation(world, -42, 70, 33), 5);
        List<Condition> conditions = group.getConditions();
        assertEquals(4, conditions.size());
        assertField(conditions.get(0), "Location.WorldUuid", MatchRule.EQUALS, world.toString());
        assertField(conditions.get(1), "Location.X", MatchRule.BETWEEN, Range.open(-47, -37));
        assertField(conditions.get(2), "Location.Y", MatchRule.BETWEEN, Range.open(65, 75));
        assertField(conditions.get(3), "Location.Z", MatchRule.BETWEEN, Range.open(28, 38));
        assertFalse(((Range<Integer>) ((FieldCondition) conditions.get(1)).getValue()).contains(-47));
        assertSameConditions(originalConditions(world, 5), group);
    }

    private static ConditionGroup originalConditions(UUID worldId, Integer radius) {
        Extent extent = (Extent) Proxy.newProxyInstance(Extent.class.getClassLoader(), new Class<?>[]{Extent.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return worldId;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Location<Extent> location = new Location<>(extent, -42, 70, 33);
        ConditionGroup conditions = radius == null ? ConditionGroup.from(location) : ConditionGroup.from(location, radius);
        Reference.reachabilityFence(extent);
        return conditions;
    }

    private static void assertSameConditions(ConditionGroup original, ConditionGroup nativeGroup) {
        assertEquals(original.getOperator(), nativeGroup.getOperator());
        assertEquals(original.getConditions().size(), nativeGroup.getConditions().size());
        for (int i = 0; i < original.getConditions().size(); i++) {
            FieldCondition condition = (FieldCondition) original.getConditions().get(i);
            assertField(nativeGroup.getConditions().get(i), condition.getFieldName().toString(),
                    condition.getMatchRule(), condition.getValue());
        }
    }

    private static void assertField(Condition condition, String path, MatchRule matchRule, Object value) {
        FieldCondition field = (FieldCondition) condition;
        assertEquals(path, field.getFieldName().toString());
        assertEquals(matchRule, field.getMatchRule());
        assertEquals(value, field.getValue());
    }
}
