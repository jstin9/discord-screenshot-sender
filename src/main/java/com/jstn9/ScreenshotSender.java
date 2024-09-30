package com.jstn9;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

public class ScreenshotSender implements ClientModInitializer {
	public static final String MOD_ID = "screenshot-sender";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private boolean wasF2PressedLastTick = false;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Mod initialized");

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_F2) == GLFW.GLFW_PRESS) {
				if (!wasF2PressedLastTick) {
					LOGGER.info("F2 pressed. Taking screenshot and sending to Discord...");

					File screenshotDir = new File(MinecraftClient.getInstance().runDirectory, "screenshots");
					ScreenshotRecorder.saveScreenshot(screenshotDir, MinecraftClient.getInstance().getFramebuffer(), screenshotText -> {
						File lastScreenshot = getLastScreenshot(screenshotDir);
						if (lastScreenshot != null && lastScreenshot.exists()) {
							ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();
							if (sendScreenshotToDiscord(lastScreenshot)) {
								if (config.isDeleteScreenshotAfterSending()) {
									deleteScreenshot(lastScreenshot);
								}
							}
						}
					});

					wasF2PressedLastTick = true;
				}
			} else {
				wasF2PressedLastTick = false;
			}
		});
	}

	private File getLastScreenshot(File screenshotDir) {
		File[] screenshots = screenshotDir.listFiles((dir, name) -> name.endsWith(".png"));
		if (screenshots != null && screenshots.length > 0) {
			Arrays.sort(screenshots, Comparator.comparingLong(File::lastModified));
			return screenshots[screenshots.length - 1];
		}
		return null;
	}

	private boolean sendScreenshotToDiscord(File screenshot) {
		ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();
		if (config.getDiscordTokenURL() == null || config.getDiscordTokenURL().isEmpty()) {
			MinecraftClient.getInstance().player.sendMessage(Text.translatable("error.screenshot_sender.missing_token"), false);
			LOGGER.error("Discord token URL is empty. Cannot send screenshot.");
			return false;
		}
		return sendFileToDiscord(screenshot);
	}

	private void deleteScreenshot(File screenshot) {
		if (screenshot.delete()) {
			LOGGER.info("Screenshot deleted successfully.");
		} else {
			LOGGER.error("Failed to delete screenshot.");
		}
	}

	private boolean sendFileToDiscord(File file) {
		ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();
		String webhookUrl = config.getDiscordTokenURL();

		try {
			URI uri = URI.create(webhookUrl);
			if (uri.getScheme() == null) {
				MinecraftClient.getInstance().player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
				return false;
			}
		} catch (IllegalArgumentException e) {
			MinecraftClient.getInstance().player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
			return false;
		}

		try {
			String playerName = MinecraftClient.getInstance().getSession().getUsername();
			String avatarUrl = "https://mineskin.eu/helm/" + playerName + "/100.png";

			byte[] fileBytes = Files.readAllBytes(file.toPath());
			String boundary = "Boundary-" + System.currentTimeMillis();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

			outputStream.write(("--" + boundary + "\r\n").getBytes());
			outputStream.write(("Content-Disposition: form-data; name=\"username\"\r\n\r\n").getBytes());
			outputStream.write((playerName + "\r\n").getBytes());

			outputStream.write(("--" + boundary + "\r\n").getBytes());
			outputStream.write(("Content-Disposition: form-data; name=\"avatar_url\"\r\n\r\n").getBytes());
			outputStream.write((avatarUrl + "\r\n").getBytes());

			outputStream.write(("--" + boundary + "\r\n").getBytes());
			outputStream.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n").getBytes());
			outputStream.write("Content-Type: image/png\r\n\r\n".getBytes());
			outputStream.write(fileBytes);
			outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes());

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(new URI(webhookUrl))
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				MinecraftClient.getInstance().player.sendMessage(Text.translatable("success.screenshot_sender.screenshot_sent"), false);
				return true;
			} else {
				MinecraftClient.getInstance().player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
				return false;
			}

		} catch (IOException | InterruptedException | URISyntaxException e) {
			LOGGER.error("Error sending screenshot to Discord", e);
			return false;
		}
	}
}
