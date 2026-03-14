package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import net.classiclauncher.launcher.LauncherVersion;
import net.classiclauncher.launcher.api.HttpGameApi;
import net.classiclauncher.launcher.launch.LaunchProgress;

/**
 * Downloads files from Mojang's CDN with SHA-1 verification.
 *
 * <p>
 * Unlike the launcher's {@link HttpGameApi}, this downloader permits cross-host redirects because Mojang uses a CDN
 * that may redirect across domains. Mojang URLs are trusted inputs from their own version JSON.
 */
public final class MinecraftFileDownloader {

	private static final int MAX_REDIRECTS = 5;
	private static final int BUFFER_SIZE = 8192;
	private static final int CONNECT_TIMEOUT = 15_000;
	private static final int READ_TIMEOUT = 30_000;

	private MinecraftFileDownloader() {
	}

	/**
	 * Downloads a file from {@code url} to {@code destination}, reporting progress.
	 *
	 * <p>
	 * The file is first written to a {@code .tmp} sibling to avoid partial files. On completion the SHA-1 is verified
	 * (if {@code expectedSha1} is non-null), and the temp file is atomically renamed to {@code destination}.
	 *
	 * @param url
	 *            the source URL
	 * @param destination
	 *            the target file (parent directory must exist or be creatable)
	 * @param expectedSha1
	 *            expected SHA-1 hex digest, or {@code null} to skip verification
	 * @param displayName
	 *            short file name for progress reporting
	 * @param progress
	 *            progress callback (may be {@code null})
	 * @throws IOException
	 *             if the download fails, times out, or SHA-1 verification fails
	 */
	public static void download(String url, File destination, String expectedSha1, String displayName,
			LaunchProgress progress) throws IOException {
		destination.getParentFile().mkdirs();
		File tmp = new File(destination.getAbsolutePath() + ".tmp");

		HttpURLConnection conn = openConnection(url);
		try {
			long contentLength = conn.getContentLengthLong();
			try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
				byte[] buffer = new byte[BUFFER_SIZE];
				long downloaded = 0L;
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
					downloaded += read;
					if (progress != null) {
						progress.fileProgress(displayName, downloaded, contentLength);
					}
				}
			}
		} finally {
			conn.disconnect();
		}

		// SHA-1 verification
		if (expectedSha1 != null && !expectedSha1.isEmpty()) {
			if (!Sha1Verifier.verify(tmp, expectedSha1)) {
				tmp.delete();
				throw new IOException("SHA-1 mismatch for " + displayName + ": expected " + expectedSha1);
			}
		}

		// Atomic rename
		if (destination.exists() && !destination.delete()) {
			tmp.delete();
			throw new IOException("Cannot replace existing file: " + destination);
		}
		if (!tmp.renameTo(destination)) {
			tmp.delete();
			throw new IOException("Cannot rename temp file to: " + destination);
		}
	}

	private static HttpURLConnection openConnection(String urlStr) throws IOException {
		int redirects = 0;
		String currentUrl = urlStr;

		while (true) {
			URL url = new URL(currentUrl);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT);
			conn.setReadTimeout(READ_TIMEOUT);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("User-Agent", "GoodOlMine-Launcher/" + LauncherVersion.VERSION);
			conn.setRequestProperty("Accept", "*/*");
			conn.connect();

			int status = conn.getResponseCode();
			if (status == HttpURLConnection.HTTP_OK) {
				return conn;
			}

			if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
					|| status == 307 || status == 308) {
				if (redirects++ >= MAX_REDIRECTS) {
					conn.disconnect();
					throw new IOException("Too many redirects for: " + urlStr);
				}
				String location = conn.getHeaderField("Location");
				conn.disconnect();
				if (location == null || location.isEmpty()) {
					throw new IOException("Redirect with no Location header: " + urlStr);
				}
				// Allow cross-host redirects (Mojang CDN may redirect across domains)
				if (location.startsWith("/")) {
					URL base = new URL(currentUrl);
					location = base.getProtocol() + "://" + base.getHost()
							+ (base.getPort() != -1 ? ":" + base.getPort() : "") + location;
				}
				currentUrl = location;
				continue;
			}

			conn.disconnect();
			throw new IOException("HTTP " + status + " for: " + currentUrl);
		}
	}

}
