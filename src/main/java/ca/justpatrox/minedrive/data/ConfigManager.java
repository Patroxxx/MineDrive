package ca.justpatrox.minedrive.data;

import ca.justpatrox.minedrive.MineDRIVE;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("minedrive.json");
    private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("minegit.json");
    private static Config currentConfig = null;

    public static void save(Config config) {
        config.migrateLegacyFieldsIfNeeded();
        currentConfig = config;
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }

    private static Config load() {
        Path pathToLoad = CONFIG_PATH;
        if (!CONFIG_PATH.toFile().exists() && LEGACY_CONFIG_PATH.toFile().exists()) {
            pathToLoad = LEGACY_CONFIG_PATH;
        }

        if (!pathToLoad.toFile().exists()) {
            MineDRIVE.LOGGER.info("No configuration file found: starting a new one!");
            return new Config();
        }

        try (FileReader reader = new FileReader(pathToLoad.toFile())) {
            Config cfg = GSON.fromJson(reader, Config.class);
            if (cfg == null) cfg = new Config();
            cfg.migrateLegacyFieldsIfNeeded();

            // Persist migrated config into the new filename.
            if (!pathToLoad.equals(CONFIG_PATH)) {
                save(cfg);
                try {
                    Files.deleteIfExists(LEGACY_CONFIG_PATH);
                } catch (IOException ignored) {}
            }

            return cfg;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    public static Config getCurrentConfig() {
        if (currentConfig != null) return currentConfig;
        currentConfig = load();
        return currentConfig;
    }
}
