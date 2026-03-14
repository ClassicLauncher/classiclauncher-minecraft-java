package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts native libraries from JARs into the natives directory.
 */
public final class NativesExtractor {

	private static final int BUFFER_SIZE = 8192;

	private NativesExtractor() {
	}

	/**
	 * Deletes and recreates {@code nativesDir}, then extracts all entries from {@code nativeJars} that do not match any
	 * {@code excludePrefixes}.
	 *
	 * @param nativeJars
	 *            list of JAR files containing native libraries
	 * @param nativesDir
	 *            target directory (will be deleted and recreated)
	 * @param excludePrefixes
	 *            entry path prefixes to skip (e.g. {@code "META-INF/"})
	 * @throws IOException
	 *             if extraction fails
	 */
	public static void extract(List<File> nativeJars, File nativesDir, List<String> excludePrefixes)
			throws IOException {
		// Clean and recreate the natives directory
		deleteRecursively(nativesDir);
		nativesDir.mkdirs();

		for (File jar : nativeJars) {
			if (!jar.exists() || !jar.isFile()) continue;
			try (ZipFile zip = new ZipFile(jar)) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (entry.isDirectory()) continue;
					if (isExcluded(entry.getName(), excludePrefixes)) continue;

					File outFile = new File(nativesDir, entry.getName());
					outFile.getParentFile().mkdirs();

					try (InputStream in = zip.getInputStream(entry);
							FileOutputStream out = new FileOutputStream(outFile)) {
						byte[] buffer = new byte[BUFFER_SIZE];
						int read;
						while ((read = in.read(buffer)) != -1) {
							out.write(buffer, 0, read);
						}
					}
				}
			}
		}
	}

	private static boolean isExcluded(String entryName, List<String> excludePrefixes) {
		if (excludePrefixes == null) return false;
		for (String prefix : excludePrefixes) {
			if (entryName.startsWith(prefix)) return true;
		}
		return false;
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}

}
