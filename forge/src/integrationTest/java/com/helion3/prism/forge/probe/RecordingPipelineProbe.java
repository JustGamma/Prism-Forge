/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.forge.probe;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.FieldCondition;
import com.helion3.prism.api.query.MatchRule;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.PrismRecordPreSaveEvent;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.queues.RecordingQueue;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.PrismEvents;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Test mod only: synthetic records, real Forge bus, original queue/writer/query, no world changes. */
@Mod(modid = "prismrecordingprobe", name = "Prism recording pipeline probe", version = "1",
        acceptableRemoteVersions = "*", dependencies = "required-after:prism")
public final class RecordingPipelineProbe {
    private final String prefix = "prism-probe:" + UUID.randomUUID();
    private final Properties results = new Properties();
    private volatile boolean scheduledPassed;
    private volatile boolean cancellationObserved;
    private volatile String shutdownEventThread;
    private String stoppingThread;
    private static final DataQuery MARKER = DataQuery.of("pre-save-marker");

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void preSave(PrismRecordPreSaveEvent event) {
        String target = event.getPrismRecord().getDataContainer().getString(DataQueries.Target).orElse("");
        if (!target.startsWith(prefix)) return;
        check(event.getCause().root() == Prism.getInstance(), "Native cause root must identify Prism");
        if (target.equals(prefix + ":cancelled")) {
            event.setCancelled(true);
            check(event.isCanceled(), "Cancellation methods must share state");
            cancellationObserved = true;
        } else {
            event.getPrismRecord().getDataContainer().set(MARKER, "listener-edit");
            if (target.equals(prefix + ":shutdown")) {
                shutdownEventThread = Thread.currentThread().getName();
            } else {
                check(Thread.currentThread().getName().startsWith("PrismAsync-"), "Scheduled pre-save must be asynchronous");
            }
        }
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        try {
            check(Prism.getInstance().isStorageReady(), "Original storage lifecycle did not start");
            try {
                Sponge.getGame();
                throw new AssertionError("A Sponge game instance must not be present");
            } catch (IllegalStateException expected) {
                passed("sponge_game_absent");
            }
            enqueue("scheduled");
            enqueue("cancelled");
            Thread verifier = new Thread(this::verifyScheduledWrite, "PrismRecordingProbe");
            verifier.setDaemon(true);
            verifier.start();
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    private void verifyScheduledWrite() {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                List<Result> records = query("scheduled");
                if (records.size() == 1 && records.get(0).data.getString(MARKER).isPresent()
                        && cancellationObserved && RecordingQueue.getQueue().isEmpty()) {
                    check(records.get(0).data.getString(MARKER).get().equals("listener-edit"), "Event edit lost in SQL/JSON");
                    check(query("cancelled").isEmpty(), "Cancelled record was persisted");
                    passed("scheduled_write_and_original_query");
                    passed("native_pre_save_cancellation");
                    passed("pre_save_edit_preserved");
                    scheduledPassed = true;
                    System.out.println("PRISM_RECORDING_PROBE scheduled=PASS cancellation=PASS mutation=PASS");
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Timed out waiting for original scheduled queue and H2 query");
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    private void enqueue(String kind) {
        DataContainer data = DataContainer.createNew().set(DataQueries.Location, DataContainer.createNew()
                .set(DataQueries.WorldUuid, "066733a1-6b08-4fb9-9cd8-26b5a2af1413")
                .set(DataQueries.X, -42).set(DataQueries.Y, 70).set(DataQueries.Z, 15));
        PrismRecord.create().source(Cause.of(EventContext.empty(), "environment"))
                .event(PrismEvents.BLOCK_BREAK).dataContainer(data).target(prefix + ":" + kind).buildAndSave();
    }

    private List<Result> query(String kind) throws Exception {
        CommandSource source = (CommandSource) Proxy.newProxyInstance(CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected command service: " + method);
                });
        QuerySession session = new QuerySession(source);
        session.addFlag(Flag.NO_GROUP);
        session.newQuery().addCondition(FieldCondition.of(DataQueries.Target, MatchRule.EQUALS, prefix + ":" + kind));
        return Prism.getInstance().getStorageAdapter().records().query(session, false).get(5, TimeUnit.SECONDS);
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        try {
            check(scheduledPassed, "Scheduled pipeline checks did not finish");
            // Deterministically leave one pending record for Prism's actual stopped lifecycle.
            Prism.getInstance().getScheduler().shutdownAndWait();
            stoppingThread = Thread.currentThread().getName();
            enqueue("shutdown");
            check(RecordingQueue.getQueue().size() == 1, "Expected exactly one pending shutdown record");
            passed("pending_record_at_shutdown");
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    @Mod.EventHandler
    public void stopped(FMLServerStoppedEvent event) {
        try {
            check(!Prism.getInstance().isStorageReady(), "Prism did not finish its storage lifecycle");
            check(RecordingQueue.getQueue().isEmpty(), "Shutdown did not drain the original queue");
            check(stoppingThread != null && stoppingThread.equals(shutdownEventThread), "Final flush did not run during shutdown");
            String database = Prism.getInstance().getPath().getParent()
                    .resolve(Prism.getInstance().getConfig().getStorageCategory().getDatabase()).toString();
            String tablePrefix = Prism.getInstance().getConfig().getStorageCategory().getTablePrefix();
            try (Connection connection = DriverManager.getConnection("jdbc:h2:" + database);
                 PreparedStatement statement = connection.prepareStatement("SELECT e.json FROM " + tablePrefix
                         + "records r JOIN " + tablePrefix + "extra e ON e.record_id=r.id WHERE r.target=?")) {
                statement.setString(1, prefix + ":shutdown");
                try (ResultSet rows = statement.executeQuery()) {
                    check(rows.next(), "Final record did not survive database close/reopen");
                    check(rows.getString(1).contains("listener-edit"), "Final pre-save edit was lost");
                    check(!rows.next(), "Final record was written more than once");
                }
            }
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                check(!thread.isAlive() || !thread.getName().startsWith("PrismAsync-"), "Prism worker survived shutdown");
            }
            passed("shutdown_flush_and_database_reopen");
            passed("workers_stopped");
            System.out.println("PRISM_RECORDING_PROBE shutdown=PASS reopen=PASS workers=PASS");
        } catch (Throwable failure) {
            failed(failure);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(this);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private synchronized void passed(String key) throws Exception {
        results.setProperty(key, "PASS");
        save();
    }

    private synchronized void failed(Throwable failure) {
        results.setProperty("failure", failure.toString());
        failure.printStackTrace();
        try {
            save();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void save() throws Exception {
        try (OutputStream output = Files.newOutputStream(Paths.get("prism-recording-probe-result.properties"))) {
            results.store(output, "Separate Forge test mod; synthetic records only");
        }
    }
}
