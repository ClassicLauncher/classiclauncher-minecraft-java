package net.classiclauncher.extension.minecraftjava;

import net.classiclauncher.extension.microsoftauth.MicrosoftAccountProvider;
import net.classiclauncher.extension.minecraftjava.game.MinecraftJava;
import net.classiclauncher.launcher.LauncherContext;
import net.classiclauncher.launcher.account.AccountProvider;
import net.classiclauncher.launcher.account.Accounts;
import net.classiclauncher.launcher.extension.LauncherExtension;
import net.classiclauncher.launcher.settings.Settings;

/**
 * Entry point for the Minecraft: Java Edition game extension.
 *
 * <h3>What this extension registers</h3>
 * <ul>
 * <li>{@link MinecraftJava#GAME} — set as the launcher's default game so the UI shows Minecraft: Java Edition on first
 * launch.</li>
 * <li>Adds {@link MinecraftJava#GAME} to the {@link MicrosoftAccountProvider}'s game list so the Microsoft login flow
 * supports Java Edition.</li>
 * </ul>
 *
 * <h3>Required extensions</h3> This extension requires the Microsoft Account extension ({@code microsoft-auth}) to be
 * installed. The {@code requiredExtensions} declaration in {@code manifest.yml} ensures that the Microsoft Account
 * extension is loaded first, so the provider is available when this extension's {@link Accounts#onReady} callback
 * fires.
 */
public class MinecraftJavaExtension implements LauncherExtension {

	/**
	 * Public no-arg constructor required by {@code JarClassFinder}.
	 */
	public MinecraftJavaExtension() {
	}

	@Override
	public void onLoad(Settings settings) {
		System.out.println("[MinecraftJavaExtension] onLoad called on: " + Thread.currentThread().getName());

		// Register Minecraft: Java Edition as the launcher's default game.
		LauncherContext.getInstance().setDefaultGame(MinecraftJava.GAME);
		System.out.println("[MinecraftJavaExtension] Default game set to: " + MinecraftJava.GAME.getDisplayName());

		// Add MinecraftJava.GAME to the Microsoft account provider's game list.
		// This callback fires after the microsoft-auth extension's callback (due to
		// requiredExtensions ordering), so the provider is already registered.
		Accounts.onReady(accounts -> {
			for (AccountProvider provider : accounts.getProviders()) {
				if (provider instanceof MicrosoftAccountProvider) {
					((MicrosoftAccountProvider) provider).addGame(MinecraftJava.GAME);
					System.out.println(
							"[MinecraftJavaExtension] Added MinecraftJava.GAME to " + provider.getDisplayName());
					break;
				}
			}
		});
	}

}
