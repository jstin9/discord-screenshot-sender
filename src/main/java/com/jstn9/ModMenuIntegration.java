package com.jstn9;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::createConfigScreen;
    }

    private Screen createConfigScreen(Screen parent) {
        ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("config.screenshot_sender.title"));

        ConfigCategory general = builder.getOrCreateCategory(Text.translatable("config.screenshot_sender.general"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        general.addEntry(entryBuilder
                .startStrField(Text.translatable("config.screenshot_sender.discord_token"), config.getDiscordTokenURL())
                .setDefaultValue(config.getDiscordTokenURL())
                .setSaveConsumer(newValue -> {
                    config.setDiscordTokenURL(newValue);
                })
                .setTooltip(Text.translatable("config.screenshot_sender.discord_token.tooltip"))
                .build()
        );

        general.addEntry(entryBuilder
                .startBooleanToggle(Text.translatable("config.screenshot_sender.delete_after_sending"), config.isDeleteScreenshotAfterSending())
                .setDefaultValue(false)
                .setSaveConsumer(config::setDeleteScreenshotAfterSending)
                .build()
        );

        builder.setSavingRunnable(config::saveConfig);

        return builder.build();
    }
}
