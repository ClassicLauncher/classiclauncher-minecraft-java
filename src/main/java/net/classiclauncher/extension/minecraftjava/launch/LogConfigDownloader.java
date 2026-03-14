package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.IOException;

/**
 * Downloads the log4j configuration file used by the Minecraft client.
 */
public final class LogConfigDownloader {

	private LogConfigDownloader() {
	}

	/**
	 * Downloads the log configuration file referenced by the version JSON's logging section.
	 *
	 * <p>
	 * Skips the download if the file is already present with a valid SHA-1.
	 *
	 * @param logging
	 *            the logging section from the version JSON (may be {@code null})
	 * @param assetsDir
	 *            the root assets directory; the file is placed under {@code <assetsDir>/log_configs/<id>}
	 * @return the local {@link File}, or {@code null} if {@code logging} is {@code null} or incomplete
	 * @throws IOException
	 *             if the file cannot be downloaded
	 */
	public static File downloadLogConfig(VersionJson.Logging logging, File assetsDir) throws IOException {
		if (logging == null || logging.client == null || logging.client.file == null) {
			return null;
		}
		VersionJson.LogFile logFile = logging.client.file;
		File dest = new File(assetsDir, "log_configs/" + logFile.id);

		if (!Sha1Verifier.verify(dest, logFile.sha1)) {
			MinecraftFileDownloader.download(logFile.url, dest, logFile.sha1, logFile.id, null);
		}

		return dest;
	}

}
