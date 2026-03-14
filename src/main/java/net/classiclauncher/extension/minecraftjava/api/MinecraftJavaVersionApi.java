package net.classiclauncher.extension.minecraftjava.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.classiclauncher.extension.minecraftjava.game.MinecraftJava;
import net.classiclauncher.launcher.LauncherContext;
import net.classiclauncher.launcher.api.GameApi;
import net.classiclauncher.launcher.api.HttpGameApi;
import net.classiclauncher.launcher.version.Version;
import net.classiclauncher.launcher.version.VersionType;

/**
 * {@link GameApi} implementation that fetches Minecraft: Java Edition versions from Mojang's public version manifest
 * endpoint.
 *
 * <p>
 * Manifest URL: {@code https://launchermeta.mojang.com/mc/game/version_manifest_v2.json}
 *
 * <p>
 * The manifest JSON has the form:
 *
 * <pre>{@code
 * {
 *   "latest": {"release": "...", "snapshot": "..."},
 *   "versions": [
 *     {"id": "1.21.4", "type": "release", "url": "...", "releaseTime": "2024-12-03T10:12:57+00:00"},
 *     {"id": "24w45a", "type": "snapshot", "url": "..."},
 *     {"id": "b1.8.1", "type": "old_beta",  "url": "..."},
 *     {"id": "a1.0.1", "type": "old_alpha", "url": "..."}
 *   ]
 * }
 * }</pre>
 *
 * <p>
 * Inherits production-ready HTTP behaviour from {@link HttpGameApi}: retries with exponential back-off, redirect
 * following, path-traversal prevention, and response-size cap.
 */
public class MinecraftJavaVersionApi extends HttpGameApi {

	private static final Logger log = LogManager.getLogger(MinecraftJavaVersionApi.class);

	private static final String MANIFEST_PATH = "/mc/game/version_manifest_v2.json";

	private static final Map<String, String> TYPE_DISPLAY_NAMES;

	static {
		Map<String, String> names = new HashMap<>();
		names.put("release", "Release");
		names.put("snapshot", "Snapshot");
		names.put("old_beta", "Beta");
		names.put("old_alpha", "Alpha");
		TYPE_DISPLAY_NAMES = Collections.unmodifiableMap(names);
	}

	/**
	 * Cached list populated on first call to {@link #getAvailableVersions()}.
	 */
	private volatile List<Version> cachedVersions = null;

	/**
	 * Lock object protecting the lazy-init of {@link #cachedVersions}.
	 */
	private final Object cacheLock = new Object();

	/**
	 * Game data directory for scanning locally-installed versions.
	 */
	private final File gameDataDir;

	/**
	 * Creates the API client pointing at Mojang's launcher metadata host. The game data directory is resolved from the
	 * current {@link LauncherContext}.
	 */
	public MinecraftJavaVersionApi() {
		super("https://launchermeta.mojang.com");
		this.gameDataDir = LauncherContext.getInstance().resolve("games", MinecraftJava.GAME_ID);
	}

	// ── GameApi ───────────────────────────────────────────────────────────────

	/**
	 * Fetches all available Minecraft: Java Edition versions from Mojang's version manifest.
	 *
	 * <p>
	 * The result is cached after the first successful fetch. On {@link IOException}, an error is logged to
	 * {@code stderr} and an empty list is returned — the caller should retry later.
	 *
	 * @return unmodifiable list of versions in manifest order (newest first), or an empty list if the manifest cannot
	 *         be fetched
	 */
	@Override
	public List<Version> getAvailableVersions() {
		if (cachedVersions != null) return cachedVersions;
		synchronized (cacheLock) {
			if (cachedVersions != null) return cachedVersions;
			List<Version> versions = new ArrayList<>();
			try {
				String json = fetchText(MANIFEST_PATH);
				versions = parseManifest(json);
			} catch (IOException e) {
				log.error("[MinecraftJavaVersionApi] Failed to fetch version manifest: {}", e.getMessage());
			}

			// Add locally-discovered versions not present in the remote manifest
			addLocalVersions(versions);

			cachedVersions = Collections.unmodifiableList(versions);
		}
		return cachedVersions;
	}

	/**
	 * Looks up a single version by its stable identifier (e.g. {@code "1.20.4"}, {@code "b1.7.3"}).
	 *
	 * @param id
	 *            the version identifier; must not be {@code null}
	 * @return an {@link Optional} containing the matching {@link Version}, or empty if not found
	 */
	@Override
	public Optional<Version> getVersion(String id) {
		if (id == null) return Optional.empty();
		for (Version v : getAvailableVersions()) {
			if (id.equals(v.getVersion())) return Optional.of(v);
		}
		return Optional.empty();
	}

	// ── Parsing ───────────────────────────────────────────────────────────────

	/**
	 * Parses the version manifest JSON without an external JSON library, using only the standard Java runtime. The
	 * manifest structure is well-defined and stable, making hand-rolled parsing practical and dependency-free.
	 *
	 * @param json
	 *            the raw manifest JSON string
	 * @return mutable list of {@link Version} objects in manifest order
	 * @throws IOException
	 *             if the JSON is malformed beyond recovery
	 */
	private static List<Version> parseManifest(String json) throws IOException {
		if (json == null || json.trim().isEmpty()) {
			throw new IOException("Version manifest is empty");
		}

		// Locate the "versions" array
		int versionsStart = json.indexOf("\"versions\"");
		if (versionsStart < 0) {
			throw new IOException("Version manifest missing 'versions' field");
		}
		int arrayStart = json.indexOf('[', versionsStart);
		if (arrayStart < 0) {
			throw new IOException("Version manifest 'versions' field is not an array");
		}
		int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');
		if (arrayEnd < 0) {
			throw new IOException("Unterminated 'versions' array in manifest");
		}

		String versionsArray = json.substring(arrayStart + 1, arrayEnd);
		List<Version> versions = new ArrayList<>();

		// Split on top-level object boundaries: iterate through { ... } objects
		int pos = 0;
		while (pos < versionsArray.length()) {
			int objStart = versionsArray.indexOf('{', pos);
			if (objStart < 0) break;
			int objEnd = findMatchingBracket(versionsArray, objStart, '{', '}');
			if (objEnd < 0) break;

			String obj = versionsArray.substring(objStart + 1, objEnd);
			Version v = parseVersionObject(obj);
			if (v != null) versions.add(v);

			pos = objEnd + 1;
		}

		return versions;
	}

	/**
	 * Parses a single version JSON object (the content between the outer braces). Returns {@code null} if the required
	 * {@code id} field is absent.
	 *
	 * @param obj
	 *            the JSON object body (without surrounding braces)
	 */
	private static Version parseVersionObject(String obj) {
		String id = extractStringField(obj, "id");
		String type = extractStringField(obj, "type");
		String releaseTime = extractStringField(obj, "releaseTime");
		String url = extractStringField(obj, "url");

		if (id == null || id.isEmpty()) return null;

		String typeName = TYPE_DISPLAY_NAMES.getOrDefault(type != null ? type : "", type != null ? type : "Unknown");
		VersionType versionType = VersionType.builder().id(type != null ? type : "unknown").name(typeName).build();

		long timestamp = 0L;
		if (releaseTime != null && !releaseTime.isEmpty()) {
			timestamp = parseIso8601(releaseTime);
		}

		return Version.builder().version(id).type(versionType).releaseTimestamp(timestamp).url(url).build();
	}

	/**
	 * Extracts a string value for a given JSON field name from a flat JSON object body.
	 *
	 * <p>
	 * Handles standard JSON string values (no embedded escaped quotes in the value). This is sufficient for the Mojang
	 * manifest fields ({@code id}, {@code type}, {@code releaseTime}).
	 *
	 * @param json
	 *            the JSON object body (without surrounding braces)
	 * @param fieldName
	 *            the field to find
	 * @return the string value, or {@code null} if the field is absent
	 */
	private static String extractStringField(String json, String fieldName) {
		String key = "\"" + fieldName + "\"";
		int keyPos = json.indexOf(key);
		if (keyPos < 0) return null;

		int colonPos = json.indexOf(':', keyPos + key.length());
		if (colonPos < 0) return null;

		int quoteStart = json.indexOf('"', colonPos + 1);
		if (quoteStart < 0) return null;

		int quoteEnd = findClosingQuote(json, quoteStart + 1);
		if (quoteEnd < 0) return null;

		return json.substring(quoteStart + 1, quoteEnd);
	}

	/**
	 * Finds the position of the closing quote for a JSON string starting at {@code startPos}, correctly handling
	 * {@code \"} escape sequences.
	 *
	 * @param s
	 *            the JSON string
	 * @param startPos
	 *            the position immediately after the opening quote
	 * @return the index of the closing {@code "}, or {@code -1} if not found
	 */
	private static int findClosingQuote(String s, int startPos) {
		for (int i = startPos; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++; // skip escaped character
			} else if (c == '"') {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Finds the position of the matching closing bracket for a bracket at {@code openPos}.
	 *
	 * @param s
	 *            the string to search
	 * @param openPos
	 *            position of the opening bracket
	 * @param open
	 *            the opening bracket character
	 * @param close
	 *            the closing bracket character
	 * @return the index of the matching closing bracket, or {@code -1} if not found
	 */
	private static int findMatchingBracket(String s, int openPos, char open, char close) {
		int depth = 0;
		boolean inString = false;
		for (int i = openPos; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++; // skip escaped character inside string
				} else if (c == '"') {
					inString = false;
				}
			} else {
				if (c == '"') {
					inString = true;
				} else if (c == open) {
					depth++;
				} else if (c == close) {
					depth--;
					if (depth == 0) return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Scans the local versions directory and appends any locally-installed versions that are not already present in the
	 * provided list. A local version is considered valid if it has both a {@code <id>.json} and a {@code <id>.jar} file
	 * in its directory, and the JSON's {@code id} field matches the directory name.
	 *
	 * @param versions
	 *            mutable list to append locally-discovered versions to
	 */
	private void addLocalVersions(List<Version> versions) {
		File versionsDir = new File(gameDataDir, "versions");
		if (!versionsDir.exists() || !versionsDir.isDirectory()) return;

		File[] dirs = versionsDir.listFiles(File::isDirectory);
		if (dirs == null) return;

		for (File dir : dirs) {
			String id = dir.getName();
			File jsonFile = new File(dir, id + ".json");
			File jarFile = new File(dir, id + ".jar");

			if (!jsonFile.exists()) {
				log.debug("[Local version {}] Missing JSON file — skipped", id);
				continue;
			}
			if (!jarFile.exists()) {
				log.debug("[Local version {}] Missing JAR file — skipped", id);
				continue;
			}

			// Check if already in the remote list
			boolean alreadyPresent = false;
			for (Version v : versions) {
				if (id.equals(v.getVersion())) {
					alreadyPresent = true;
					break;
				}
			}
			if (alreadyPresent) continue;

			try {
				String jsonContent = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
				String type = extractTypeFromJson(jsonContent, id);
				if (type == null) continue;

				String typeName = TYPE_DISPLAY_NAMES.getOrDefault(type, type);
				VersionType versionType = VersionType.builder().id(type).name(typeName).build();

				versions.add(Version.builder().version(id).type(versionType).releaseTimestamp(0L).url(null).build());
				log.debug("[Local version {}] Added locally-discovered version (type: {})", id, type);
			} catch (Exception e) {
				log.debug("[Local version {}] Invalid JSON — skipped: {}", id, e.getMessage());
			}
		}
	}

	/**
	 * Extracts the {@code type} field from a version JSON string, validating that the {@code id} field matches the
	 * expected directory name.
	 *
	 * @param json
	 *            the version JSON content
	 * @param expectedId
	 *            the expected version ID (directory name)
	 * @return the type string, or {@code null} if validation fails
	 */
	private String extractTypeFromJson(String json, String expectedId) {
		String id = extractStringField(json, "id");
		if (!expectedId.equals(id)) {
			log.debug("[Local version {}] JSON id field '{}' does not match directory name — skipped", expectedId, id);
			return null;
		}
		String type = extractStringField(json, "type");
		return type != null && !type.isEmpty() ? type : "release";
	}

	/**
	 * Parses an ISO 8601 date-time string (e.g. {@code "2024-12-03T10:12:57+00:00"}) to epoch milliseconds.
	 *
	 * <p>
	 * Uses {@link Instant#parse} which requires the {@code Z} suffix for UTC. For offset-based strings (e.g.
	 * {@code +00:00}), the offset is normalised to {@code Z} before parsing.
	 *
	 * @param iso8601
	 *            the ISO 8601 date-time string
	 * @return epoch milliseconds, or {@code 0L} on parse failure
	 */
	private static long parseIso8601(String iso8601) {
		if (iso8601 == null || iso8601.isEmpty()) return 0L;
		try {
			// Normalise "+00:00" and "-00:00" to "Z" for Instant.parse compatibility
			String normalised = iso8601.trim();
			if (normalised.endsWith("+00:00") || normalised.endsWith("-00:00")) {
				normalised = normalised.substring(0, normalised.length() - 6) + "Z";
			}
			return Instant.parse(normalised).toEpochMilli();
		} catch (Exception e) {
			// Non-fatal: timestamp is optional metadata
			return 0L;
		}
	}

}
