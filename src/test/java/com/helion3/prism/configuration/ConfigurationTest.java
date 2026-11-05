/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
 */
package com.helion3.prism.configuration;

import com.helion3.prism.configuration.category.StorageCategory;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

    @TempDir
    Path directory;

    @Test
    void missingFileRetainsOriginalDefaultsAndSavesOriginalKeys() throws Exception {
        Path file = directory.resolve("prism.conf");
        Configuration configuration = new Configuration(file);
        configuration.loadConfiguration();

        Config config = configuration.getConfig();
        assertEquals("h2", config.getStorageCategory().getEngine());
        assertEquals("prism_", config.getStorageCategory().getTablePrefix());
        assertEquals("4w", config.getStorageCategory().getExpireRecords());
        assertEquals(2, config.getGeneralCategory().getSchemaVersion());
        assertEquals(5, config.getDefaultCategory().getRadius());
        assertTrue(config.getEventCategory().isBlockBreak());
        assertTrue(config.getEventCategory().isBlockPlace());
        assertFalse(config.getEventCategory().isItemInsert());

        configuration.saveConfiguration();
        assertTrue(Files.isRegularFile(file));
        ConfigurationNode saved = HoconConfigurationLoader.builder().setPath(file).build().load();
        assertEquals("h2", saved.getNode("storage", "engine").getString());
        assertEquals(2, saved.getNode("general", "schema-version").getInt());
        assertEquals(5, saved.getNode("default", "radius").getInt());
        assertFalse(saved.getNode("event", "block-grow").isVirtual());
        assertFalse(saved.getNode("limit", "maximum-actionable").isVirtual());
    }

    @Test
    void currentConfigurationRetainsValuesAcrossLoadSaveLoad() throws Exception {
        Path file = directory.resolve("prism.conf");
        Files.writeString(file, """
                general {
                    schema-version=2
                    date-format="yyyy-MM-dd HH:mm:ss"
                    debug=true
                    blacklist=["examplemod", "anothermod"]
                }
                default { enabled=false, radius=17, since="12h" }
                event { block-break=false, block-place=true, block-grow=false, item-insert=true }
                limit { maximum-actionable=4321, maximum-lookup=123, maximum-radius=78 }
                storage {
                    engine=mysql
                    address="db.example.invalid:3307"
                    database=history
                    mysql-driver=mariadb
                    username=test-user
                    password="test-fixture-only"
                    table-prefix="audit_"
                    should-expire=false
                    expire-records="8w"
                    maximum-pool-size=7
                    minimum-idle=3
                    purge-batch-limit=432
                }
                """);
        Configuration configuration = new Configuration(file);
        configuration.loadConfiguration();
        assertCurrentValues(configuration.getConfig());
        configuration.saveConfiguration();

        Configuration reloaded = new Configuration(file);
        reloaded.loadConfiguration();
        assertCurrentValues(reloaded.getConfig());
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "mongo", "mysql"})
    void legacyConfigurationUsesOriginalConversionForEachStorageEngine(String engine) throws Exception {
        Path file = directory.resolve("prism.conf");
        Files.writeString(file, """
                storage {
                    engine="%s"
                    expireRecords="6w"
                    maxPoolSize=8
                    minPoolSize=3
                    purgeBatchLimit=321
                }
                should-expire=true
                mysql-driver=mysql
                db {
                    name=history
                    h2 { tablePrefix="legacy_" }
                    mongo { host="db.example.invalid", port=27018, user=operator, pass="test-fixture-only" }
                    mysql { host="db.example.invalid", port=3308, user=operator, pass="test-fixture-only", tablePrefix="legacy_" }
                }
                defaults { enabled=false, radius=9, since="2d" }
                display { dateFormat="d/M/yy hh:mm:ss" }
                events { break=false, place=true, grow=false, decay=false, insert=true, join=true }
                limits { radius { max=88 } }
                query { actionable { limit=1234 }, lookup { limit=234 } }
                blacklist=["examplemod"]
                """.formatted(engine));

        Configuration configuration = new Configuration(file);
        configuration.loadConfiguration();
        Config config = configuration.getConfig();
        StorageCategory storage = config.getStorageCategory();
        assertEquals(engine, storage.getEngine());
        assertEquals("history", storage.getDatabase());
        assertEquals("6w", storage.getExpireRecords());
        assertEquals(8, storage.getMaximumPoolSize());
        assertEquals(3, storage.getMinimumIdle());
        assertEquals(321, storage.getPurgeBatchLimit());
        assertEquals(1, config.getGeneralCategory().getSchemaVersion());
        assertEquals("d/M/yy hh:mm:ss a", config.getGeneralCategory().getDateFormat());
        assertEquals(Arrays.asList("examplemod"), config.getGeneralCategory().getBlacklist());
        assertFalse(config.getDefaultCategory().isEnabled());
        assertEquals(9, config.getDefaultCategory().getRadius());
        assertFalse(config.getEventCategory().isBlockBreak());
        assertTrue(config.getEventCategory().isItemInsert());
        assertTrue(config.getEventCategory().isPlayerJoin());
        assertEquals(88, config.getLimitCategory().getMaximumRadius());
        assertEquals(1234, config.getLimitCategory().getMaximumActionable());
        assertEquals(234, config.getLimitCategory().getMaximumLookup());
        if (engine.equals("h2")) {
            assertEquals("legacy_", storage.getTablePrefix());
        } else {
            assertEquals(engine.equals("mongo") ? "db.example.invalid:27018" : "db.example.invalid:3308", storage.getAddress());
            assertEquals("operator", storage.getUsername());
            assertEquals("test-fixture-only", storage.getPassword());
        }

        configuration.saveConfiguration();
        Configuration reloaded = new Configuration(file);
        reloaded.loadConfiguration();
        assertEquals(engine, reloaded.getConfig().getStorageCategory().getEngine());
        assertEquals("history", reloaded.getConfig().getStorageCategory().getDatabase());
        assertEquals(1, reloaded.getConfig().getGeneralCategory().getSchemaVersion());
        assertTrue(reloaded.getConfig().getEventCategory().isItemInsert());
    }

    private void assertCurrentValues(Config config) {
        assertEquals(2, config.getGeneralCategory().getSchemaVersion());
        assertEquals("yyyy-MM-dd HH:mm:ss", config.getGeneralCategory().getDateFormat());
        assertTrue(config.getGeneralCategory().isDebug());
        assertEquals(Arrays.asList("examplemod", "anothermod"), config.getGeneralCategory().getBlacklist());
        assertFalse(config.getDefaultCategory().isEnabled());
        assertEquals(17, config.getDefaultCategory().getRadius());
        assertEquals("12h", config.getDefaultCategory().getSince());
        assertFalse(config.getEventCategory().isBlockBreak());
        assertTrue(config.getEventCategory().isBlockPlace());
        assertFalse(config.getEventCategory().isBlockGrow());
        assertTrue(config.getEventCategory().isItemInsert());
        assertEquals(4321, config.getLimitCategory().getMaximumActionable());
        assertEquals(123, config.getLimitCategory().getMaximumLookup());
        assertEquals(78, config.getLimitCategory().getMaximumRadius());
        StorageCategory storage = config.getStorageCategory();
        assertEquals("mysql", storage.getEngine());
        assertEquals("db.example.invalid:3307", storage.getAddress());
        assertEquals("history", storage.getDatabase());
        assertEquals("mariadb", storage.getMysqlDriver());
        assertEquals("test-user", storage.getUsername());
        assertEquals("test-fixture-only", storage.getPassword());
        assertEquals("audit_", storage.getTablePrefix());
        assertFalse(storage.isShouldExpire());
        assertEquals("8w", storage.getExpireRecords());
        assertEquals(7, storage.getMaximumPoolSize());
        assertEquals(3, storage.getMinimumIdle());
        assertEquals(432, storage.getPurgeBatchLimit());
    }
}
