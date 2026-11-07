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

package com.helion3.prism;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.helion3.prism.api.filters.FilterList;
import com.helion3.prism.api.filters.FilterMode;
import com.helion3.prism.api.flags.FlagClean;
import com.helion3.prism.api.flags.FlagDrain;
import com.helion3.prism.api.flags.FlagExtended;
import com.helion3.prism.api.flags.FlagHandler;
import com.helion3.prism.api.flags.FlagNoGroup;
import com.helion3.prism.api.flags.FlagOrder;
import com.helion3.prism.api.parameters.ParameterBlock;
import com.helion3.prism.api.parameters.ParameterCause;
import com.helion3.prism.api.parameters.ParameterEventName;
import com.helion3.prism.api.parameters.ParameterHandler;
import com.helion3.prism.api.parameters.ParameterPlayer;
import com.helion3.prism.api.parameters.ParameterRadius;
import com.helion3.prism.api.parameters.ParameterTime;
import com.helion3.prism.api.records.ActionableResult;
import com.helion3.prism.api.storage.StorageAdapter;
import com.helion3.prism.forge.BuildInfo;
import com.helion3.prism.forge.MigrationStatusCommand;
import com.helion3.prism.forge.ForgeServerTasks;
import com.helion3.prism.forge.command.ForgeRootCommand;
import com.helion3.prism.forge.listeners.ForgeRequiredInteractListener;
import com.helion3.prism.configuration.Config;
import com.helion3.prism.configuration.Configuration;
import com.helion3.prism.queues.RecordingQueueManager;
import com.helion3.prism.storage.h2.H2StorageAdapter;
import com.helion3.prism.storage.mongodb.MongoStorageAdapter;
import com.helion3.prism.storage.mysql.MySQLStorageAdapter;
import com.helion3.prism.util.AsyncScheduler;
import com.helion3.prism.util.Reference;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Prism is an event logging + rollback/restore engine for Minecraft servers.
 *
 * @author viveleroi
 */
@Mod(
        modid = Reference.ID,
        name = Reference.NAME,
        version = BuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*",
        dependencies = "required-after:Forge@[10.13.4.1614]"
)
public final class Prism {

    private static Prism instance;

    private Logger logger;

    private Path path;

    private Configuration configuration;
    private StorageAdapter storageAdapter;
    private AsyncScheduler scheduler;
    private boolean storageReady;

    private final Set<UUID> activeWands = Sets.newHashSet();
    private final FilterList filterList = new FilterList(FilterMode.BLACKLIST);
    private final Set<FlagHandler> flagHandlers = Sets.newHashSet();
    private final Map<UUID, List<ActionableResult>> lastActionResults = Maps.newHashMap();
    private final Set<ParameterHandler> parameterHandlers = Sets.newHashSet();
    private final RecordingQueueManager recordingQueueManager = new RecordingQueueManager();

    @Mod.EventHandler
    public void onPreInitialization(FMLPreInitializationEvent event) throws IOException {
        initialize(event.getModConfigurationDirectory().toPath().resolve(Reference.ID).resolve("prism.conf"),
                event.getModLog());
        getConfiguration().loadConfiguration();
    }

    /** Supplies the state that Sponge previously injected, without a game-service implementation. */
    public void initialize(Path path, Logger logger) throws IOException {
        instance = this;
        this.path = path;
        this.logger = logger;
        Files.createDirectories(path.toAbsolutePath().getParent());
        configuration = new Configuration(getPath());
    }

    @Mod.EventHandler
    public void onInitialization(FMLInitializationEvent event) {
        // Register FlagHandlers
        registerFlagHandler(new FlagClean());
        registerFlagHandler(new FlagDrain());
        registerFlagHandler(new FlagExtended());
        registerFlagHandler(new FlagNoGroup());
        registerFlagHandler(new FlagOrder());

        // Register ParameterHandlers
        registerParameterHandler(new ParameterBlock());
        registerParameterHandler(new ParameterCause());
        registerParameterHandler(new ParameterEventName());
        registerParameterHandler(new ParameterPlayer());
        registerParameterHandler(new ParameterRadius());
        registerParameterHandler(new ParameterTime());

        // Inspection and its break/place cancellation rules use Forge's native event bus.
        MinecraftForge.EVENT_BUS.register(new ForgeRequiredInteractListener());
    }

    @Mod.EventHandler
    public void onPostInitialization(FMLPostInitializationEvent event) {
        getConfiguration().saveConfiguration();
    }

    @Mod.EventHandler
    public void onStartingServer(FMLServerStartingEvent event) {
        ForgeServerTasks.start();
        event.registerServerCommand(new ForgeRootCommand());
        event.registerServerCommand(new MigrationStatusCommand());
    }

    @Mod.EventHandler
    public void onStartedServer(FMLServerStartedEvent event) {
        scheduler = new AsyncScheduler(getLogger());
        String engine = getConfig().getStorageCategory().getEngine();
        try {
            if (StringUtils.equalsIgnoreCase(engine, "h2")) {
                storageAdapter = new H2StorageAdapter();
            } else if (StringUtils.equalsAnyIgnoreCase(engine, "mongo", "mongodb")) {
                storageAdapter = new MongoStorageAdapter();
            } else if (StringUtils.equalsIgnoreCase(engine, "mysql")) {
                storageAdapter = new MySQLStorageAdapter();
            } else {
                throw new Exception("Invalid storage engine configured.");
            }

            Preconditions.checkState(getStorageAdapter().connect());
            storageReady = true;

            // Initialize the recording queue manager
            getScheduler().repeat("PrismRecordingQueueManager", recordingQueueManager, 1, TimeUnit.SECONDS);
            getLogger().info("Prism storage started on Java {}. Direct player block break/place recording, lookup and inspection are available. Restoration and other event capture are not enabled.",
                    System.getProperty("java.version"));
        } catch (Exception ex) {
            getScheduler().shutdownAndWait();
            if (getStorageAdapter() != null) {
                getStorageAdapter().close();
            }
            storageAdapter = null;
            storageReady = false;
            getLogger().error("Encountered an error processing {}::onStartedServer", "Prism", ex);
        }
    }

    @Mod.EventHandler
    public void onStoppedServer(FMLServerStoppedEvent event) {
        try {
            // Cancel all scheduled tasks
            if (getScheduler() != null) {
                getScheduler().shutdownAndWait();
            }

            if (storageReady) {
                // Flush any pending records
                // If the scheduled task is still running this will block until it completes
                try {
                    recordingQueueManager.run();
                } finally {
                    // Shutdown storage only after all database tasks and the final flush complete.
                    getStorageAdapter().close();
                    storageReady = false;
                }
            }
        } finally {
            com.helion3.prism.forge.command.ForgeChatActions.clear();
            ForgeServerTasks.stop();
        }
    }

    public static Prism getInstance() {
        return instance;
    }

    public AsyncScheduler getScheduler() {
        return scheduler;
    }

    public boolean isStorageReady() {
        return storageReady;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getPath() {
        return path;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public Config getConfig() {
        Preconditions.checkState(getConfiguration() != null, "Prism has not been initialized!");
        return getConfiguration().getConfig();
    }

    public StorageAdapter getStorageAdapter() {
        return storageAdapter;
    }

    /**
     * Returns a list of players who have active inspection wands.
     *
     * @return A list of players' UUIDs who have an active inspection wand
     */
    public Set<UUID> getActiveWands() {
        return activeWands;
    }

    /**
     * Returns the blacklist manager.
     *
     * @return Blacklist
     */
    public FilterList getFilterList() {
        return filterList;
    }

    /**
     * Returns all currently registered flag handlers.
     *
     * @return List of {@link FlagHandler}
     */
    public Set<FlagHandler> getFlagHandlers() {
        return flagHandlers;
    }

    /**
     * Returns a specific handler for a given parameter
     *
     * @param flag {@link String} flag name
     * @return The {@link FlagHandler}, or empty if unsupported
     */
    public Optional<FlagHandler> getFlagHandler(String flag) {
        for (FlagHandler flagHandler : getFlagHandlers()) {
            if (flagHandler.handles(flag)) {
                return Optional.of(flagHandler);
            }
        }

        return Optional.empty();
    }

    /**
     * Register a flag handler.
     *
     * @param flagHandler {@link FlagHandler}
     * @return True if the {@link FlagHandler} was registered
     */
    public boolean registerFlagHandler(FlagHandler flagHandler) {
        Preconditions.checkNotNull(flagHandler);
        return getFlagHandlers().add(flagHandler);
    }

    /**
     * Get a map of players and their last available actionable results.
     *
     * @return A map of players' UUIDs to a list of their {@link ActionableResult}s
     */
    public Map<UUID, List<ActionableResult>> getLastActionResults() {
        return lastActionResults;
    }

    /**
     * Returns all currently registered parameter handlers.
     *
     * @return List of {@link ParameterHandler}
     */
    public Set<ParameterHandler> getParameterHandlers() {
        return parameterHandlers;
    }

    /**
     * Returns a specific handler for a given parameter
     *
     * @param alias {@link String} parameter name
     * @return The {@link ParameterHandler}, or empty if unsupported
     */
    public Optional<ParameterHandler> getParameterHandler(String alias) {
        for (ParameterHandler parameterHandler : getParameterHandlers()) {
            if (parameterHandler.handles(alias)) {
                return Optional.of(parameterHandler);
            }
        }

        return Optional.empty();
    }

    /**
     * Register a parameter handler.
     *
     * @param parameterHandler {@link ParameterHandler}
     * @return True if the {@link ParameterHandler} was registered
     */
    public boolean registerParameterHandler(ParameterHandler parameterHandler) {
        Preconditions.checkNotNull(parameterHandler);
        return getParameterHandlers().add(parameterHandler);
    }

}
