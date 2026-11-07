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

package com.helion3.prism.storage.mongodb;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.ConditionGroup;
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.query.Sort;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.configuration.Configuration;
import com.helion3.prism.forge.world.ForgeLocation;
import com.helion3.prism.forge.data.NbtTranslator;
import net.minecraft.nbt.NBTTagCompound;
import com.helion3.prism.queues.RecordingQueue;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import org.apache.logging.log4j.LogManager;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Requires only the isolated prism_fixture database and its disposable prism/fixture-only user. */
@Tag("mongodb")
class MongoRecordPipelineTest {
    @TempDir
    Path directory;
    private Prism previousInstance;
    private Prism prism;
    private MongoStorageAdapter storage;
    private final UUID world = UUID.fromString("066733a1-6b08-4fb9-9cd8-26b5a2af1413");
    private final String player = "43c7fd92-7023-42f4-856a-f65aa2e78d17";

    private static void field(String name, Object target, Object value) throws Exception {
        Field field = Prism.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void connectOriginalAdapterWithoutSponge() throws Exception {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        previousInstance = Prism.getInstance();
        prism = new Prism();
        Configuration configuration = new Configuration(directory.resolve("prism.conf"));
        var config = configuration.getConfig().getStorageCategory();
        config.setEngine("mongodb");
        config.setAddress(System.getProperty("prism.mongo.address", "127.0.0.1:27117"));
        config.setDatabase("prism_fixture");
        config.setUsername("prism");
        config.setPassword("fixture-only");
        config.setShouldExpire(false);
        field("instance", null, prism);
        field("configuration", prism, configuration);
        field("path", prism, directory.resolve("prism.conf"));
        field("logger", prism, LogManager.getLogger("prism-mongo-test"));
        RecordingQueue.getQueue().clear();
        storage = new MongoStorageAdapter();
        assertTrue(storage.connect(), "The live MongoDB fixture must be running and authenticated");
        MongoStorageAdapter.getCollection("records").deleteMany(new Document());
    }

    @AfterEach
    void closeAndRestore() throws Exception {
        try {
            if (storage != null) storage.close();
        } finally {
            RecordingQueue.getQueue().clear();
            field("instance", null, previousInstance);
        }
    }

    private void enqueue(UUID worldId, int x, boolean byPlayer, Date date) {
        DataContainer tile = DataContainer.createNew()
                .set(DataQuery.of("energy"), 9007199254740993L)
                .set(DataQuery.of("bytes"), new byte[]{-128, 0, 127})
                .set(DataQuery.of("ints"), new int[]{Integer.MIN_VALUE, 42, Integer.MAX_VALUE})
                .set(DataQuery.of("longs"), new long[]{Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE})
                .set(DataQuery.of("array-list"), List.of(new byte[]{4, 5}, new byte[]{6, 7}))
                .set(DataQuery.of("inventory"), List.of(DataContainer.createNew()
                        .set(DataQuery.of("slot"), 3)
                        .set(DataQuery.of("tag"), DataContainer.createNew()
                                .set(DataQuery.of("mod:key.with.dots"), "literal value")
                                .set(DataQuery.of("payload"), new byte[]{1, 2, 3}))));
        DataContainer data = DataContainer.createNew()
                .set(DataQueries.Location, new ForgeLocation(worldId, x, 70, -15).toContainer())
                .set(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "example:machine")
                .set(DataQueries.OriginalBlock.then(DataQuery.of("Metadata")), 7)
                .set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData), tile)
                .set(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "minecraft:air");
        if (byPlayer) data.set(DataQueries.Player, player);
        PrismRecord record = PrismRecord.create().source(Cause.of(EventContext.empty(), "environment"))
                .event(PrismEvents.BLOCK_BREAK).dataContainer(data).target("example:machine").build();
        record.save();
        // Deterministic history timestamps are fixture input, rather than a wait on wall time.
        record.getDataContainer().set(DataQueries.Created, date);
    }

    private void flush() throws Exception {
        List<DataContainer> batch = new ArrayList<>();
        PrismRecord record;
        while ((record = RecordingQueue.getQueue().poll()) != null) batch.add(record.getDataContainer());
        storage.records().write(batch);
    }

    private QuerySession session(boolean grouped) {
        CommandSource source = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("Storage requested a Sponge command service: " + method);
                });
        QuerySession session = new QuerySession(source);
        if (!grouped) session.addFlag(Flag.NO_GROUP);
        session.newQuery();
        return session;
    }

    private List<Result> query(QuerySession session) throws Exception {
        return storage.records().query(session, false).get(5, TimeUnit.SECONDS);
    }

    @Test
    void originalBsonPathPreservesMachineNbtAndArrayTypes() throws Exception {
        enqueue(world, -42, false, new Date());
        flush();
        Result result = query(session(false)).getFirst();
        assertEquals("environment", result.getSourceName());
        assertEquals("break", result.getEventId());
        assertEquals(world.toString(), result.data.getString(DataQueries.Location.then(DataQueries.WorldUuid)).orElseThrow());
        var tile = result.data.getView(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)).orElseThrow();
        assertEquals(9007199254740993L, tile.getLong(DataQuery.of("energy")).orElseThrow());
        assertArrayEquals(new byte[]{-128, 0, 127}, (byte[]) tile.get(DataQuery.of("bytes")).orElseThrow());
        assertArrayEquals(new int[]{Integer.MIN_VALUE, 42, Integer.MAX_VALUE}, (int[]) tile.get(DataQuery.of("ints")).orElseThrow());
        assertArrayEquals(new long[]{Long.MIN_VALUE, 9007199254740993L, Long.MAX_VALUE}, (long[]) tile.get(DataQuery.of("longs")).orElseThrow());
        List<?> arrays = (List<?>) tile.get(DataQuery.of("array-list")).orElseThrow();
        assertArrayEquals(new byte[]{4, 5}, (byte[]) arrays.get(0));
        assertArrayEquals(new byte[]{6, 7}, (byte[]) arrays.get(1));
        assertInstanceOf(Date.class, result.data.get(DataQueries.Created).orElseThrow());
    }

    @Test
    void plainMongoMapsAreAcceptedByTheRetainedNbtConsumer() throws Exception {
        enqueue(world, -42, false, new Date());
        RecordingQueue.getQueue().peek().getDataContainer().remove(DataQueries.OriginalBlock.then(DataQueries.UnsafeData));
        RecordingQueue.getQueue().peek().getDataContainer().set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData),
                DataContainer.createNew().set(DataQuery.of("inventory"), List.of(DataContainer.createNew()
                        .set(DataQuery.of("slot"), 3).set(DataQuery.of("item"), "example:item"))));
        flush();
        var view = query(session(false)).getFirst().data.getView(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)).orElseThrow();
        NBTTagCompound restored = NbtTranslator.getInstance().translate(view);
        var item = restored.getTagList("inventory", 10).getCompoundTagAt(0);
        assertEquals(3, item.getInteger("slot"));
        assertEquals("example:item", item.getString("item"));
    }

    @Test
    void nestedInventoryNbtRetainsArraysAndLiteralKeys() throws Exception {
        enqueue(world, -42, false, new Date());
        flush();
        var tile = query(session(false)).getFirst().data.getView(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)).orElseThrow();
        // Exercise the NBT restoration contract, rather than demanding a particular Java map/view representation.
        DataContainer inventory = DataContainer.createNew().set(DataQuery.of("inventory"), tile.get(DataQuery.of("inventory")).orElseThrow());
        NBTTagCompound restored = NbtTranslator.getInstance().translate(inventory);
        var item = restored.getTagList("inventory", 10).getCompoundTagAt(0);
        assertEquals(3, item.getInteger("slot"));
        var tag = item.getCompoundTag("tag");
        assertAll(
                () -> assertEquals("literal value", tag.getString("mod:key.with.dots")),
                () -> assertEquals(7, tag.getTag("payload").getId()),
                () -> assertArrayEquals(new byte[]{1, 2, 3}, tag.getByteArray("payload")));
    }

    @Test
    void groupingAndLocationAndPlayerConditionsUseOriginalQueries() throws Exception {
        enqueue(world, -42, true, new Date());
        enqueue(world, -41, true, new Date());
        enqueue(UUID.randomUUID(), -42, true, new Date());
        enqueue(world, 400, false, new Date());
        flush();
        QuerySession session = session(true);
        session.getQuery().addCondition(ConditionGroup.from(new ForgeLocation(world, -42, 70, -15), 5));
        session.getQuery().addCondition(FieldCondition.of(DataQueries.Player, MatchRule.EQUALS, player));
        List<Result> results = query(session);
        assertEquals(1, results.size());
        assertEquals(2, results.getFirst().data.getInt(DataQueries.Count).orElseThrow());
        assertEquals(player, results.getFirst().getSourceName());
        assertEquals("break", results.getFirst().getEventId());
    }

    @Test
    void groupedEnvironmentRecordsRetainTheirCause() throws Exception {
        enqueue(world, -42, false, new Date());
        flush();
        Result result = query(session(true)).getFirst();
        assertEquals("environment", result.getSourceName());
    }

    @Test
    void individualHistoryPreservesOrderAndTimeConditions() throws Exception {
        enqueue(world, -42, true, new Date(1000));
        enqueue(world, -41, true, new Date(9000));
        flush();
        QuerySession newest = session(false);
        assertEquals(-41, query(newest).getFirst().data.getInt(DataQueries.Location.then(DataQueries.X)).orElseThrow());
        QuerySession oldest = session(false);
        oldest.setSortBy(Sort.OLDEST_FIRST);
        assertEquals(-42, query(oldest).getFirst().data.getInt(DataQueries.Location.then(DataQueries.X)).orElseThrow());
        oldest.getQuery().addCondition(FieldCondition.of(DataQueries.Created, MatchRule.GREATER_THAN_EQUAL, new Date(5000)));
        assertEquals(1, query(oldest).size());
    }

    @Test
    void originalExpirationFieldAndIndexRemoveExpiredHistory() throws Exception {
        storage.close();
        prism.getConfig().getStorageCategory().setShouldExpire(true);
        prism.getConfig().getStorageCategory().setExpireRecords("1s");
        storage = new MongoStorageAdapter();
        assertTrue(storage.connect());
        enqueue(world, -42, false, new Date());
        flush();
        var records = MongoStorageAdapter.getCollection("records");
        assertInstanceOf(Date.class, records.find().first().get("Expires"));
        assertTrue(records.listIndexes().into(new ArrayList<>()).stream()
                .anyMatch(index -> index.containsKey("expireAfterSeconds") && index.get("key", Document.class).containsKey("Expires")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (records.countDocuments() > 0 && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(0, records.countDocuments(), "MongoDB's TTL monitor did not expire the fixture record");
    }
}
