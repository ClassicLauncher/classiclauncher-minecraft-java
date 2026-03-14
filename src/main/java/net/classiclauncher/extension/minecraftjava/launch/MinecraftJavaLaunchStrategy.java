package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import net.classiclauncher.extension.microsoftauth.MinecraftAccount;
import net.classiclauncher.launcher.LauncherContext;
import net.classiclauncher.launcher.LauncherVersion;
import net.classiclauncher.launcher.account.Account;
import net.classiclauncher.launcher.account.AccountProvider;
import net.classiclauncher.launcher.api.GameApi;
import net.classiclauncher.launcher.launch.LaunchContext;
import net.classiclauncher.launcher.launch.LaunchProgress;
import net.classiclauncher.launcher.launch.LaunchStrategy;
import net.classiclauncher.launcher.platform.Platform;
import net.classiclauncher.launcher.profile.Profile;
import net.classiclauncher.launcher.settings.Settings;
import net.classiclauncher.launcher.version.Version;

/**
 * Full Minecraft Java Edition launch strategy.
 *
 * <p>
 * Pipeline:
 * <ol>
 * <li>Resolve version ID (from profile or "latest" matching filters)</li>
 * <li>Download version JSON if needed</li>
 * <li>Parse version JSON</li>
 * <li>Download client JAR (SHA-1 verified)</li>
 * <li>Resolve and download libraries</li>
 * <li>Extract natives</li>
 * <li>Download assets</li>
 * <li>Download log config</li>
 * <li>Ensure valid access token</li>
 * <li>Build launch command with variable substitution</li>
 * </ol>
 */
public class MinecraftJavaLaunchStrategy implements LaunchStrategy {

	// State set by prepare(), read by buildCommand()
	private String resolvedVersionId;
	private VersionJson versionJson;
	private File clientJar;
	private List<File> classpathJars;
	private File nativesDir;
	private File assetsDir;
	private File logConfigFile;
	private String accessToken;

	@Override
	public void prepare(LaunchContext ctx, LaunchProgress progress) throws Exception {
		Profile profile = ctx.getProfile();
		File gameDataDir = ctx.getGameDataDir();

		// ── 1. Resolve version ID ─────────────────────────────────────────────
		resolvedVersionId = profile.getVersionId();
		if (resolvedVersionId == null || resolvedVersionId.isEmpty()) {
			resolvedVersionId = resolveLatestVersion(ctx, progress);
		}
		progress.log("Preparing Minecraft Java Edition " + resolvedVersionId + "...");

		// ── 2. Download version JSON if needed ────────────────────────────────
		File versionDir = new File(gameDataDir, "versions/" + resolvedVersionId);
		versionDir.mkdirs();
		File jsonFile = new File(versionDir, resolvedVersionId + ".json");

		if (!jsonFile.exists()) {
			String versionUrl = findVersionUrl(ctx, resolvedVersionId);
			if (versionUrl != null) {
				progress.log("Downloading version JSON...");
				MinecraftFileDownloader.download(versionUrl, jsonFile, null, resolvedVersionId + ".json", progress);
			} else if (!jsonFile.exists()) {
				throw new IOException(
						"Version JSON not found locally and no remote URL available for: " + resolvedVersionId);
			}
		}

		// ── 3. Parse version JSON ─────────────────────────────────────────────
		String jsonContent = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
		versionJson = VersionJsonParser.parse(jsonContent);

		// ── 4. Count total files for progress bar ─────────────────────────────
		int totalFiles = countTotalFiles(versionJson);
		progress.setTotalFiles(totalFiles);

		// ── 5. Download / verify client JAR ───────────────────────────────────
		clientJar = new File(versionDir, resolvedVersionId + ".jar");
		if (versionJson.downloads != null && versionJson.downloads.client != null) {
			VersionJson.Download clientDownload = versionJson.downloads.client;
			if (!Sha1Verifier.verify(clientJar, clientDownload.sha1)) {
				if (clientDownload.url != null && !clientDownload.url.isEmpty()) {
					progress.log("Downloading client JAR...");
					MinecraftFileDownloader.download(clientDownload.url, clientJar, clientDownload.sha1,
							resolvedVersionId + ".jar", progress);
				}
			}
		}
		progress.fileCompleted();

		// ── 6. Resolve and download libraries ─────────────────────────────────
		File librariesDir = new File(gameDataDir, "libraries");
		MinecraftLibraryResolver.ResolvedLibraries resolved = MinecraftLibraryResolver
				.resolveAndDownload(versionJson.libraries, librariesDir, progress);
		classpathJars = resolved.classpath;

		// ── 7. Extract natives ────────────────────────────────────────────────
		nativesDir = new File(versionDir, "natives");
		if (!resolved.nativeJars.isEmpty()) {
			progress.log("Extracting natives...");
			NativesExtractor.extract(resolved.nativeJars, nativesDir, Arrays.asList("META-INF/"));
		} else {
			nativesDir.mkdirs();
		}

		// ── 8. Download assets ────────────────────────────────────────────────
		assetsDir = new File(gameDataDir, "assets");
		assetsDir.mkdirs();
		if (versionJson.assetIndex != null) {
			progress.log("Downloading assets...");
			AssetDownloader.downloadAssets(versionJson.assetIndex, assetsDir, progress);
		}

		// ── 9. Download log config ────────────────────────────────────────────
		if (versionJson.logging != null) {
			logConfigFile = LogConfigDownloader.downloadLogConfig(versionJson.logging, assetsDir);
		}

		// ── 10. Ensure valid access token ─────────────────────────────────────
		Account account = ctx.getAccount();
		if (!(account instanceof MinecraftAccount)) {
			throw new IllegalStateException("Account type " + account.getClass().getSimpleName()
					+ " does not implement MinecraftAccount. Cannot obtain Minecraft access token.");
		}

		MinecraftAccount minecraftAccount = (MinecraftAccount) account;
		accessToken = minecraftAccount.ensureAccessToken();

		progress.log("All files ready. Launching...");
	}

	@Override
	public List<String> buildCommand(LaunchContext ctx) throws Exception {
		Profile profile = ctx.getProfile();
		MinecraftAccount minecraftAccount = (MinecraftAccount) ctx.getAccount();

		// Java executable
		String javaExe = (ctx.getJre() != null) ? ctx.getJre().getExecutablePath() : "java";

		// Build variable substitution map
		String classpathStr = buildClasspathString();
		Map<String, String> vars = new HashMap<>();
		vars.put("natives_directory", nativesDir.getAbsolutePath());
		vars.put("launcher_name", LauncherContext.getInstance().getName());
		vars.put("launcher_version", LauncherVersion.VERSION);
		vars.put("classpath", classpathStr);
		vars.put("auth_player_name", minecraftAccount.getMinecraftUsername());
		vars.put("auth_uuid", minecraftAccount.getMinecraftUUID());
		vars.put("auth_access_token", accessToken);
		vars.put("auth_xuid", minecraftAccount.getXuid());
		vars.put("clientid", minecraftAccount.getClientId());
		vars.put("user_type", minecraftAccount.getUserType());
		// Legacy variables used by pre-1.13 minecraftArguments strings
		vars.put("auth_type", minecraftAccount.getUserType());
		vars.put("user_properties", "{}");
		vars.put("version_name", resolvedVersionId);
		vars.put("version_type", versionJson.type != null ? versionJson.type : "release");
		vars.put("game_directory", ctx.getGameDirectory().getAbsolutePath());
		vars.put("assets_root", assetsDir.getAbsolutePath());
		vars.put("game_assets", assetsDir.getAbsolutePath());
		vars.put("assets_index_name", versionJson.assets != null ? versionJson.assets : resolvedVersionId);
		if (profile.getResolutionWidth() != null) {
			vars.put("resolution_width", profile.getResolutionWidth().toString());
		}
		if (profile.getResolutionHeight() != null) {
			vars.put("resolution_height", profile.getResolutionHeight().toString());
		}
		if (logConfigFile != null) {
			vars.put("path", logConfigFile.getAbsolutePath());
		}

		boolean hasCustomResolution = profile.getResolutionWidth() != null && profile.getResolutionHeight() != null;

		List<String> command = new ArrayList<>();
		command.add(javaExe);

		// ── JVM arguments ─────────────────────────────────────────────────────
		if (versionJson.arguments != null && versionJson.arguments.jvm != null) {
			command.addAll(ArgumentEvaluator.evaluate(versionJson.arguments.jvm, vars, hasCustomResolution));
		} else {
			// Legacy JVM args (pre-1.13)
			command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
			command.add("-cp");
			command.add(classpathStr);
		}

		// Prepend log config argument if present (insert after java executable, before other JVM args)
		if (logConfigFile != null && versionJson.logging != null && versionJson.logging.client != null
				&& versionJson.logging.client.argument != null) {
			String logArg = ArgumentEvaluator.substituteVars(versionJson.logging.client.argument, vars);
			command.add(1, logArg);
		}

		// Extra JVM arguments from profile
		String profileJvmArgs = profile.getJvmArguments();
		if (profileJvmArgs != null && !profileJvmArgs.isEmpty()) {
			for (String arg : profileJvmArgs.split("\\s+")) {
				if (!arg.isEmpty()) command.add(arg);
			}
		}

		// macOS specific JVM flag — must come before the main class
		if (Platform.current() == Platform.MACOS) {
			if (!command.contains("-XstartOnFirstThread")) {
				command.add("-XstartOnFirstThread");
			}
		}

		// ── Main class ────────────────────────────────────────────────────────
		command.add(versionJson.mainClass);

		// ── Game arguments ────────────────────────────────────────────────────
		if (versionJson.arguments != null && versionJson.arguments.game != null) {
			command.addAll(ArgumentEvaluator.evaluate(versionJson.arguments.game, vars, hasCustomResolution));
		} else if (versionJson.minecraftArguments != null && !versionJson.minecraftArguments.isEmpty()) {
			// Legacy: split on whitespace and substitute
			String[] parts = versionJson.minecraftArguments.split("\\s+");
			for (String part : parts) {
				command.add(ArgumentEvaluator.substituteVars(part, vars));
			}
		}

		return command;
	}

	// ── Private helpers ───────────────────────────────────────────────────────

	private String resolveLatestVersion(LaunchContext ctx, LaunchProgress progress) throws IOException {
		progress.log("Resolving latest version...");
		Profile profile = ctx.getProfile();

		GameApi api = getApiForContext(ctx);

		List<Version> versions = api.getAvailableVersions();
		for (Version v : versions) {
			String typeId = v.getType() != null ? v.getType().getId() : "";
			if ("release".equals(typeId)) return v.getVersion();
			if (profile.isEnableSnapshots() && "snapshot".equals(typeId)) return v.getVersion();
			if (profile.isEnableBetaVersions() && "old_beta".equals(typeId)) return v.getVersion();
			if (profile.isEnableAlphaVersions() && "old_alpha".equals(typeId)) return v.getVersion();
		}

		if (!versions.isEmpty()) return versions.get(0).getVersion();
		throw new IOException("No Minecraft versions available.");
	}

	private String findVersionUrl(LaunchContext ctx, String versionId) {
		GameApi api = getApiForContext(ctx);
		Optional<Version> version = api.getVersion(versionId);
		return version.map(Version::getUrl).orElse(null);
	}

	/**
	 * Obtains the {@link GameApi} for the current launch context. Prefers the provider's API so that custom API
	 * overrides in extensions are respected; falls back to the game's own factory if no provider is matched.
	 */
	private GameApi getApiForContext(LaunchContext ctx) {
		Account account = ctx.getAccount();
		Optional<AccountProvider> optProvider = Settings.getInstance().getAccounts().getProvider(account.getType());
		if (optProvider.isPresent()) {
			return optProvider.get().getApiForGame(ctx.getGame());
		}
		return ctx.getGame().createApi();
	}

	private int countTotalFiles(VersionJson json) {
		int count = 1; // client JAR

		if (json.libraries != null) {
			count += json.libraries.size();
		}

		if (json.logging != null && json.logging.client != null && json.logging.client.file != null) {
			count += 1;
		}

		// Asset count is set separately inside AssetDownloader via setTotalFiles
		return count;
	}

	private String buildClasspathString() {
		StringBuilder sb = new StringBuilder();
		for (File jar : classpathJars) {
			if (sb.length() > 0) sb.append(File.pathSeparator);
			sb.append(jar.getAbsolutePath());
		}
		if (sb.length() > 0) sb.append(File.pathSeparator);
		sb.append(clientJar.getAbsolutePath());
		return sb.toString();
	}

}
