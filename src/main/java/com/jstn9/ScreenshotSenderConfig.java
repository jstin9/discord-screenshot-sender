package com.jstn9;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ScreenshotSenderConfig {
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(),
            "discord-screenshot-sender.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ScreenshotSenderConfig instance;

    private String discordTokenURL = "";
    private boolean deleteScreenshotAfterSending = false;

    private ScreenshotSenderConfig() {
    }

    public static ScreenshotSenderConfig getInstance() {
        if (instance == null) {
            instance = loadConfig();
        }
        return instance;
    }

    private static ScreenshotSenderConfig loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, ScreenshotSenderConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new ScreenshotSenderConfig();
    }

    public void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getDiscordTokenURL() {
        return discordTokenURL;
    }

    public void setDiscordTokenURL(String discordTokenURL) {
        this.discordTokenURL = discordTokenURL;
        saveConfig();
    }

    public boolean isDeleteScreenshotAfterSending() {
        return deleteScreenshotAfterSending;
    }

    public void setDeleteScreenshotAfterSending(boolean deleteScreenshotAfterSending) {
        this.deleteScreenshotAfterSending = deleteScreenshotAfterSending;
        saveConfig();
    }
}
