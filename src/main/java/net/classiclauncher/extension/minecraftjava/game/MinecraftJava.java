package net.classiclauncher.extension.minecraftjava.game;

import net.classiclauncher.extension.microsoftauth.TiledBackgroundRenderer;
import net.classiclauncher.extension.minecraftjava.api.MinecraftJavaVersionApi;
import net.classiclauncher.extension.minecraftjava.launch.MinecraftJavaLaunchStrategy;
import net.classiclauncher.launcher.game.ExecutableType;
import net.classiclauncher.launcher.game.Game;
import net.classiclauncher.launcher.settings.LauncherStyle;
import net.classiclauncher.launcher.settings.Settings;
import net.classiclauncher.launcher.ui.BackgroundRenderer;

/**
 * Pre-built {@link Game} descriptor for Minecraft: Java Edition.
 *
 * <p>
 * Usage — register the default game on your {@code LauncherContext}:
 *
 * <pre>{@code
 * LauncherContext.getInstance().setDefaultGame(MinecraftJava.GAME);
 * }</pre>
 *
 * <p>
 * To customise the API factory (e.g. an archival version source):
 *
 * <pre>{@code
 *
 * Game myGame = MinecraftJava.builder().apiFactory(MyVersionApi::new).build();
 * }</pre>
 */
public final class MinecraftJava {

	/**
	 * Stable game ID used in configs and asset paths.
	 */
	public static final String GAME_ID = "minecraft_java";

	/**
	 * Immutable {@link Game} descriptor for Minecraft: Java Edition. Launched via JAR, supports version selection, game
	 * directory, resolution, and crash reporting. Versions are fetched from the Mojang version manifest via
	 * {@link MinecraftJavaVersionApi}.
	 */
	public static final Game GAME = builder().apiFactory(MinecraftJavaVersionApi::new)
			.launchStrategyFactory(MinecraftJavaLaunchStrategy::new)
			.backgroundRendererFactory(MinecraftJava::createBackground).onSelected(MinecraftJava::onSelected).build();

	/**
	 * Returns a pre-configured {@link Game.Builder} for Minecraft: Java Edition with all capability flags and version
	 * filters already set. Callers can override individual settings (e.g. {@code apiFactory}) before calling
	 * {@link Game.Builder#build()}.
	 *
	 * @return a builder pre-populated with all standard Minecraft: Java Edition settings
	 */
	public static Game.Builder builder() {
		return Game.builder(GAME_ID, "Minecraft: Java Edition", ExecutableType.JAR).versionSelectionEnabled(true)
				.gameDirSupported(true).resolutionSupported(true).autoCrashReportSupported(true)
				.versionFilter("snapshot", "Enable snapshots").versionFilter("old_beta", "Enable Beta versions")
				.versionFilter("old_alpha", "Enable Alpha versions");
	}

	private static BackgroundRenderer createBackground(LauncherStyle style) {
		switch (style) {
			case ALPHA :
				return new TiledBackgroundRenderer("/assets/background.png", 4, 0f, 1.0f);
			case V1_1 :
				return new TiledBackgroundRenderer("/assets/background.png", 3, 0.25f, 1.3f);
			default :
				return null;
		}
	}

	public static void onSelected(LauncherStyle style) {
		Settings.getInstance().getLauncher().setUpdateNotesUrl("https://mcupdate.tumblr.com/");
	}

	private MinecraftJava() {
	}

}
