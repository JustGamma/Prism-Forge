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

package com.helion3.prism.storage.h2;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.PrismRecordPreSaveEvent;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.queues.RecordingQueue;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.ListenerList;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ForgeRecordingPipelineTest {
    @TempDir Path directory;
    private Prism previousInstance;
    private Prism prism;
    private IEventListener listener;
    private int busId;
    private static final DataQuery MARKER = DataQuery.of("pre-save-marker");

    @BeforeEach
    void initializeOriginalApplication() throws Exception {
        assertThrows(IllegalStateException.class, Sponge::getGame);
        previousInstance = Prism.getInstance();
        prism = new Prism();
        prism.initialize(directory.resolve("prism.conf"), LogManager.getLogger("prism-pipeline-test"));
        prism.getConfig().getStorageCategory().setShouldExpire(false);
        RecordingQueue.getQueue().clear();
    }

    @AfterEach
    void closeAndRestoreState() throws Exception {
        try {
            prism.onStoppedServer(null);
        } finally {
            if (listener != null) ListenerList.unregisterAll(busId, listener);
            RecordingQueue.getQueue().clear();
            Field field = Prism.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, previousInstance);
        }
    }

    private PrismRecord record(String target) {
        DataContainer data = DataContainer.createNew().set(DataQueries.Location, DataContainer.createNew()
                .set(DataQueries.WorldUuid, "066733a1-6b08-4fb9-9cd8-26b5a2af1413")
                .set(DataQueries.X, -42).set(DataQueries.Y, 70).set(DataQueries.Z, 15));
        return PrismRecord.create().source(Cause.of(EventContext.empty(), "environment"))
                .event(PrismEvents.BLOCK_BREAK).dataContainer(data).target(target).build();
    }

    private void listen(Consumer<PrismRecordPreSaveEvent> handler) throws Exception {
        // Unit tests do not run FML's event transformer/Loader. Register through Forge's own
        // listener table; the separate real-server probe verifies @SubscribeEvent registration.
        Field field = EventBus.class.getDeclaredField("busID");
        field.setAccessible(true);
        busId = field.getInt(MinecraftForge.EVENT_BUS);
        listener = event -> handler.accept((PrismRecordPreSaveEvent) event);
        new PrismRecordPreSaveEvent(record("listener-registration"), Cause.of(EventContext.empty(), prism))
                .getListenerList().register(busId, EventPriority.NORMAL, listener);
    }

    private List<Result> query() throws Exception {
        CommandSource source = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected command service: " + method);
                });
        QuerySession session = new QuerySession(source);
        session.addFlag(Flag.NO_GROUP);
        session.newQuery();
        return prism.getStorageAdapter().records().query(session, false).get(5, TimeUnit.SECONDS);
    }

    private int persistedCount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:" + directory.resolve("prism"));
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM prism_records")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    @Test
    void originalScheduledQueueHonorsNativeCancellationAndRecordEdits() throws Exception {
        AtomicReference<Thread> eventThread = new AtomicReference<>();
        AtomicReference<PrismRecordPreSaveEvent> cancelled = new AtomicReference<>();
        listen(event -> {
            eventThread.set(Thread.currentThread());
            if (event.getPrismRecord().getDataContainer().getString(DataQueries.Target).get().equals("cancelled")) {
                event.setCanceled(true);
                cancelled.set(event);
            } else {
                event.getPrismRecord().getDataContainer().set(MARKER, "listener-edit");
            }
        });
        prism.onStartedServer(null);
        assertTrue(prism.isStorageReady());
        record("accepted").save();
        record("cancelled").save();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        List<Result> results;
        do {
            results = query();
            if (results.size() == 1 && results.get(0).data.getString(MARKER).isPresent()
                    && cancelled.get() != null) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(1, results.size());
        assertEquals("listener-edit", results.get(0).data.getString(MARKER).orElseThrow());
        assertNotNull(cancelled.get());
        assertTrue(cancelled.get().isCancelled());
        assertSame(prism, cancelled.get().getCause().root());
        assertTrue(eventThread.get().getName().startsWith("PrismAsync-"));
        assertThrows(IllegalStateException.class, Sponge::getGame);
    }

    @Test
    void finalLifecycleFlushPersistsRecordsAfterWorkersStopAndThenClosesPool() throws Exception {
        prism.onStartedServer(null);
        assertTrue(prism.isStorageReady());
        prism.getScheduler().shutdownAndWait();
        record("shutdown-flush").save();
        assertEquals(1, RecordingQueue.getQueue().size());
        prism.onStoppedServer(null);
        assertFalse(prism.isStorageReady());
        assertTrue(RecordingQueue.getQueue().isEmpty());
        assertThrows(SQLException.class, H2StorageAdapter::getConnection);
        assertEquals(1, persistedCount());
    }

    @Test
    void shutdownWaitsForAnActivePreSaveAndWriteBeforeClosingStorage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        listen(event -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer was not released");
            } catch (InterruptedException exception) {
                throw new AssertionError("Shutdown interrupted an active writer", exception);
            }
        });
        prism.onStartedServer(null);
        record("active-write").save();
        assertTrue(entered.await(8, TimeUnit.SECONDS));
        CountDownLatch stoppingStarted = new CountDownLatch(1);
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> {
            stoppingStarted.countDown();
            prism.onStoppedServer(null);
        });
        try {
            assertTrue(stoppingStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        stopping.get(5, TimeUnit.SECONDS);
        assertFalse(prism.isStorageReady());
        assertThrows(SQLException.class, H2StorageAdapter::getConnection);
        assertEquals(1, persistedCount());
    }

    @Test
    void invalidEngineLeavesStorageUnavailableAndStopsWorkers() {
        prism.getConfig().getStorageCategory().setEngine("not-an-engine");
        prism.onStartedServer(null);
        assertFalse(prism.isStorageReady());
        assertNull(prism.getStorageAdapter());
        assertThrows(RejectedExecutionException.class,
                () -> prism.getScheduler().execute("unexpected", () -> fail("Task must not execute")));
    }

    @Test
    void failedSchemaCreationClosesThePartiallyConnectedPool() {
        prism.getConfig().getStorageCategory().setTablePrefix("invalid prefix ");
        prism.onStartedServer(null);
        assertFalse(prism.isStorageReady());
        assertNull(prism.getStorageAdapter());
        assertThrows(SQLException.class, H2StorageAdapter::getConnection);
        assertThrows(RejectedExecutionException.class,
                () -> prism.getScheduler().execute("unexpected", () -> fail("Task must not execute")));
    }

    @Test
    void explicitH2DriverSelectionIgnoresACompetingJdbcProvider() throws Exception {
        List<Driver> h2Drivers = new ArrayList<>();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver.getClass().getName().equals("org.h2.Driver")) h2Drivers.add(driver);
        }
        for (Driver driver : h2Drivers) DriverManager.deregisterDriver(driver);
        Driver competing = new Driver() {
            public Connection connect(String url, Properties properties) throws SQLException {
                throw new SQLException("A different mod's driver was selected");
            }
            public boolean acceptsURL(String url) {
                return url.startsWith("jdbc:h2:");
            }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
                return new DriverPropertyInfo[0];
            }
            public int getMajorVersion() {
                return 0;
            }
            public int getMinorVersion() {
                return 0;
            }
            public boolean jdbcCompliant() {
                return false;
            }
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
        DriverManager.registerDriver(competing);
        try {
            prism.onStartedServer(null);
            assertTrue(prism.isStorageReady());
            try (Connection connection = H2StorageAdapter.getConnection()) {
                assertEquals("H2", connection.getMetaData().getDatabaseProductName());
            }
        } finally {
            DriverManager.deregisterDriver(competing);
            for (Driver driver : h2Drivers) DriverManager.registerDriver(driver);
        }
    }
}
