/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.storage.h2;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.configuration.Configuration;
import com.helion3.prism.queues.RecordingQueue;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.logging.log4j.LogManager;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class H2RecordPipelineTest {
    @TempDir
    Path directory;
    private Prism previousInstance;
    private H2StorageAdapter storage;
    private boolean connected;
    private final String worldId = "066733a1-6b08-4fb9-9cd8-26b5a2af1413";

    private static void setField(String name, Object target, Object value) throws Exception {
        Field field = Prism.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void prepareOriginalPluginStateWithoutASpongeGame() throws Exception {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        previousInstance = Prism.getInstance();
        Prism plugin = new Prism();
        Configuration configuration = new Configuration(directory.resolve("prism.conf"));
        // This test isolates storage. Scheduler and purge lifecycle are ported separately.
        configuration.getConfig().getStorageCategory().setShouldExpire(false);
        setField("instance", null, plugin);
        setField("configuration", plugin, configuration);
        setField("path", plugin, directory.resolve("prism.conf"));
        setField("logger", plugin, LogManager.getLogger("prism-h2-test"));
        RecordingQueue.getQueue().clear();
        storage = new H2StorageAdapter();
        connected = storage.connect();
        assertTrue(connected);
    }

    @AfterEach
    void closeDatabaseAndRestorePluginState() throws Exception {
        try {
            if (connected) storage.close();
        } finally {
            RecordingQueue.getQueue().clear();
            setField("instance", null, previousInstance);
        }
    }

    private DataContainer snapshotRecordData(int x, long energy) {
        DataContainer data = DataContainer.createNew();
        data.set(DataQueries.Location, DataContainer.createNew()
                .set(DataQueries.WorldUuid, worldId)
                .set(DataQueries.X, x).set(DataQueries.Y, 70).set(DataQueries.Z, -15));
        data.set(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "example:machine");
        data.set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQuery.of("energy")), energy);
        data.set(DataQueries.OriginalBlock.then(DataQueries.UnsafeData).then(DataQuery.of("bytes")), new byte[]{-1, 0, 1});
        data.set(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType), "minecraft:air");
        return data;
    }

    private void enqueue(int x, long energy) {
        PrismRecord.create()
                .source(Cause.of(EventContext.empty(), "environment"))
                .event(PrismEvents.BLOCK_BREAK)
                .dataContainer(snapshotRecordData(x, energy))
                .target("example:machine")
                .buildAndSave();
    }

    private void writeQueuedRecords() throws Exception {
        List<DataContainer> batch = new ArrayList<>();
        PrismRecord record;
        while ((record = RecordingQueue.getQueue().poll()) != null) {
            batch.add(record.getDataContainer());
        }
        storage.records().write(batch);
    }

    private QuerySession session(boolean grouped) {
        CommandSource source = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) return "storage-test";
                    throw new AssertionError("Storage unexpectedly called a command service: " + method);
                });
        QuerySession session = new QuerySession(source);
        if (!grouped) session.addFlag(Flag.NO_GROUP);
        session.newQuery();
        return session;
    }

    @Test
    void originalRecordFactoryQueueAndH2WriterPreserveSnapshotValues() throws Exception {
        enqueue(-42, 1234567890123L);
        assertEquals(1, RecordingQueue.getQueue().size());
        assertEquals("break", RecordingQueue.getQueue().peek().getEvent());
        writeQueuedRecords();

        List<Result> results = storage.records().query(session(false), false).get();
        assertEquals(1, results.size());
        Result result = results.get(0);
        assertEquals("Block Break", result.getEventName());
        assertEquals("broke", result.getEventVerb());
        assertEquals("environment", result.getSourceName());
        assertEquals("machine", result.getTargetName());
        assertEquals(worldId, result.data.getString(DataQueries.Location.then(DataQueries.WorldUuid)).get());
        assertEquals(-42, result.data.getInt(DataQueries.Location.then(DataQueries.X)).get());
        DataQuery nbt = DataQueries.OriginalBlock.then(DataQueries.UnsafeData);
        assertEquals(1234567890123L, result.data.getLong(nbt.then(DataQuery.of("energy"))).get());
        assertArrayEquals(new byte[]{-1, 0, 1}, (byte[]) result.data.get(nbt.then(DataQuery.of("bytes"))).get());
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void originalSqlConditionsSelectTheRequestedLocation() throws Exception {
        enqueue(-42, 100L);
        enqueue(50, 200L);
        writeQueuedRecords();
        QuerySession session = session(false);
        session.getQuery().addCondition(FieldCondition.of(DataQueries.Location.then(DataQueries.X), MatchRule.EQUALS, -42));
        List<Result> results = storage.records().query(session, false).get();
        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).data.getLong(DataQueries.OriginalBlock.then(DataQueries.UnsafeData)
                .then(DataQuery.of("energy"))).get());
    }

    @Test
    void originalGroupedQueryAggregatesMatchingEvents() throws Exception {
        enqueue(-42, 100L);
        enqueue(50, 200L);
        writeQueuedRecords();
        List<Result> results = storage.records().query(session(true), false).get();
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).data.getInt(DataQueries.Count).get());
        assertFalse(results.get(0).data.getView(DataQueries.Location).isPresent());
    }
}
