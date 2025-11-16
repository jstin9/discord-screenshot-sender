package com.jstn9;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
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
import java.util.concurrent.CompletableFuture;

public class ScreenshotSender implements ClientModInitializer {
	public static final String MOD_ID = "screenshot-sender";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private boolean wasF2PressedLastTick = false;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Mod initialized");

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean isF2Pressed = GLFW.glfwGetKey(
					MinecraftClient.getInstance().getWindow().getHandle(),
					GLFW.GLFW_KEY_F2
			) == GLFW.GLFW_PRESS;

			if (isF2Pressed && !wasF2PressedLastTick) {
				LOGGER.info("F2 pressed. Waiting for Minecraft to create screenshot...");

				File screenshotDir = new File(MinecraftClient.getInstance().runDirectory, "screenshots");
				long timestamp = System.currentTimeMillis();

				CompletableFuture.runAsync(() -> {
					File screenshot = waitForScreenshotFile(screenshotDir, timestamp);

					if (screenshot == null) {
						LOGGER.warn("Timed out waiting for screenshot file after F2 press");
						return;
					}

					if (!isValidScreenshotFile(screenshot)) {
						LOGGER.warn("Screenshot file is invalid even after it appeared: {}", screenshot.getPath());
						return;
					}

					ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();

					if (sendScreenshotToDiscord(screenshot)) {
						if (config.isDeleteScreenshotAfterSending()) {
							deleteScreenshot(screenshot);
						}
					}
				});
			}

			wasF2PressedLastTick = isF2Pressed;
		});
	}

	private File waitForScreenshotFile(File screenshotDir, long timestamp) {
		long deadline = System.currentTimeMillis() + (long) 5000;
		File lastScreenshot;
		long lastSize = -1L;

		while (System.currentTimeMillis() < deadline) {
			lastScreenshot = getLastScreenshotAfterTimestamp(screenshotDir, timestamp);

			if (lastScreenshot != null && lastScreenshot.exists()) {
				long size = lastScreenshot.length();

				if (size > 0) {
					if (size == lastSize) {
						LOGGER.info("Screenshot file is stable: {} ({} bytes)", lastScreenshot.getName(), size);
						return lastScreenshot;
					}

					LOGGER.debug("Screenshot file is still growing: {} ({} bytes)", lastScreenshot.getName(), size);
					lastSize = size;
				}
			}

			try {
				Thread.sleep(150);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.warn("Interrupted while waiting for screenshot file", e);
				return null;
			}
		}

		LOGGER.warn("Timeout while waiting for screenshot file in directory: {}", screenshotDir.getPath());
		return null;
	}


	private File getLastScreenshotAfterTimestamp(File screenshotDir, long timestamp) {
		File[] screenshots = screenshotDir.listFiles((dir, name) ->
			name.endsWith(".png") && new File(dir, name).lastModified() >= timestamp);

		if (screenshots != null && screenshots.length > 0) {
			Arrays.sort(screenshots, Comparator.comparingLong(File::lastModified));
			return screenshots[screenshots.length - 1];
		}
		return null;
	}

	private boolean isValidScreenshotFile(File screenshot) {
		if (!screenshot.exists()) {
			LOGGER.warn("Screenshot file does not exist: {}", screenshot.getPath());
			return false;
		}

		long fileSize = screenshot.length();
		if (fileSize == 0) {
			LOGGER.warn("Screenshot file is empty (0 bytes): {}", screenshot.getPath());
			return false;
		}

		if (fileSize < 1024) {
			LOGGER.warn("Screenshot file is suspiciously small ({} bytes): {}", fileSize, screenshot.getPath());
			return false;
		}

		try {
			byte[] fileBytes = Files.readAllBytes(screenshot.toPath());
			if (fileBytes.length == 0) {
				LOGGER.warn("Screenshot file read as 0 bytes: {}", screenshot.getPath());
				return false;
			}

			if (fileBytes.length < 8 ||
				fileBytes[0] != (byte) 0x89 || fileBytes[1] != 0x50 ||
				fileBytes[2] != 0x4E || fileBytes[3] != 0x47) {
				LOGGER.warn("Screenshot file is not a valid PNG: {}", screenshot.getPath());
				return false;
			}

		} catch (IOException e) {
			LOGGER.error("Error reading screenshot file: {}", screenshot.getPath(), e);
			return false;
		}

		LOGGER.info("Screenshot file is valid: {} ({} bytes)", screenshot.getPath(), fileSize);
		return true;
	}

	private boolean sendScreenshotToDiscord(File screenshot) {
		ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();
		if (config.getDiscordTokenURL() == null || config.getDiscordTokenURL().isEmpty()) {
			MinecraftClient.getInstance().execute(() -> {
				MinecraftClient mcClient = MinecraftClient.getInstance();
				if (mcClient.player != null) {
					mcClient.player.sendMessage(Text.translatable("error.screenshot_sender.missing_token"), false);
				}
			});
			LOGGER.error("Discord token URL is empty. Cannot send screenshot.");
			return false;
		}
		return sendFileToDiscord(screenshot);
	}

	private void deleteScreenshot(File screenshot) {
		try {
			if (screenshot.exists() && screenshot.delete()) {
				LOGGER.info("Screenshot deleted successfully: {}", screenshot.getName());
			} else {
				LOGGER.error("Failed to delete screenshot: {}", screenshot.getName());
			}
		} catch (Exception e) {
			LOGGER.error("Error deleting screenshot: {}", screenshot.getName(), e);
		}
	}

	private boolean sendFileToDiscord(File file) {
		ScreenshotSenderConfig config = ScreenshotSenderConfig.getInstance();
		String webhookUrl = config.getDiscordTokenURL();

		try {
			URI uri = URI.create(webhookUrl);
			if (uri.getScheme() == null) {
				MinecraftClient.getInstance().execute(() -> {
					MinecraftClient mcClient = MinecraftClient.getInstance();
					if (mcClient.player != null) {
						mcClient.player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
					}
				});
				return false;
			}
		} catch (IllegalArgumentException e) {
			MinecraftClient.getInstance().execute(() -> {
				MinecraftClient mcClient = MinecraftClient.getInstance();
				if (mcClient.player != null) {
					mcClient.player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
				}
			});
			return false;
		}

		try {
			String playerName = MinecraftClient.getInstance().getSession().getUsername();
			String avatarUrl = "https://mineskin.eu/helm/" + playerName + "/100.png";

			byte[] fileBytes = Files.readAllBytes(file.toPath());

			if (fileBytes.length == 0) {
				LOGGER.error("File bytes are empty when trying to send to Discord");
				return false;
			}

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

			try (HttpClient httpClient = HttpClient.newHttpClient()) {
				HttpRequest request = HttpRequest.newBuilder()
						.uri(new URI(webhookUrl))
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.POST(HttpRequest.BodyPublishers.ofByteArray(outputStream.toByteArray()))
						.build();

				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() == 200) {
					MinecraftClient.getInstance().execute(() -> {
						MinecraftClient mcClient = MinecraftClient.getInstance();
						if (mcClient.player != null) {
							mcClient.player.sendMessage(Text.translatable("success.screenshot_sender.screenshot_sent"), false);
						}
					});
					return true;
				} else {
					LOGGER.error("Discord webhook returned status code: {} with body: {}", response.statusCode(), response.body());
					MinecraftClient.getInstance().execute(() -> {
						MinecraftClient mcClient = MinecraftClient.getInstance();
						if (mcClient.player != null) {
							mcClient.player.sendMessage(Text.translatable("error.screenshot_sender.invalid_token"), false);
						}
					});
					return false;
				}
			}

		} catch (IOException | InterruptedException | URISyntaxException e) {
			LOGGER.error("Error sending screenshot to Discord", e);
			return false;
		}
	}
}
