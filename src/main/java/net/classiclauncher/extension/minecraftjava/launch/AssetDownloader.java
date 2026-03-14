package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.classiclauncher.launcher.launch.LaunchProgress;

/**
 * Downloads and verifies Minecraft asset files.
 *
 * <p>
 * Assets are stored under {@code <assetsDir>/objects/<hash[0:2]>/<hash>} and indexed by
 * {@code <assetsDir>/indexes/<id>.json}.
 */
public final class AssetDownloader {

	private static final String ASSET_BASE_URL = "https://resources.download.minecraft.net";
	private static final Gson GSON = new Gson();

	private AssetDownloader() {
	}

	/**
	 * Downloads the asset index and all referenced asset objects.
	 *
	 * @param assetIndex
	 *            the asset index descriptor from the version JSON
	 * @param assetsDir
	 *            the root assets directory
	 * @param progress
	 *            progress callback ({@link LaunchProgress#setTotalFiles} is called within this method with the number
	 *            of assets)
	 * @throws IOException
	 *             if the index or a required asset cannot be downloaded
	 */
	public static void downloadAssets(VersionJson.AssetIndex assetIndex, File assetsDir, LaunchProgress progress)
			throws IOException {
		if (assetIndex == null) return;

		// Download the index JSON
		File indexFile = new File(assetsDir, "indexes/" + assetIndex.id + ".json");
		if (!Sha1Verifier.verify(indexFile, assetIndex.sha1)) {
			progress.log("Downloading asset index: " + assetIndex.id);
			MinecraftFileDownloader.download(assetIndex.url, indexFile, assetIndex.sha1, assetIndex.id + ".json",
					progress);
		}

		// Parse the index
		String indexJson = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
		JsonObject root = GSON.fromJson(indexJson, JsonObject.class);
		JsonObject objects = root != null ? root.getAsJsonObject("objects") : null;
		if (objects == null) {
			progress.log("Asset index has no 'objects' — skipping asset download.");
			return;
		}

		Set<Map.Entry<String, JsonElement>> entries = objects.entrySet();
		progress.setTotalFiles(entries.size());

		for (Map.Entry<String, JsonElement> entry : entries) {
			JsonObject asset = entry.getValue().getAsJsonObject();
			String hash = asset.get("hash").getAsString();
			String prefix = hash.substring(0, 2);

			File objectFile = new File(assetsDir, "objects/" + prefix + "/" + hash);
			if (Sha1Verifier.verify(objectFile, hash)) {
				progress.fileCompleted();
				continue;
			}

			String assetUrl = ASSET_BASE_URL + "/" + prefix + "/" + hash;
			MinecraftFileDownloader.download(assetUrl, objectFile, hash, entry.getKey(), progress);
			progress.fileCompleted();
		}
	}

}
