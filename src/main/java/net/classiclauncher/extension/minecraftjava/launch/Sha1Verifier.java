package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies files against their expected SHA-1 hash.
 */
public final class Sha1Verifier {

	private static final int BUFFER_SIZE = 8192;

	private Sha1Verifier() {
	}

	/**
	 * Returns {@code true} if the file exists and its SHA-1 hash matches {@code expectedSha1}. Returns {@code false}
	 * if:
	 * <ul>
	 * <li>The file does not exist.</li>
	 * <li>{@code expectedSha1} is {@code null} or empty.</li>
	 * <li>The computed hash does not match.</li>
	 * </ul>
	 *
	 * @param file
	 *            the file to verify
	 * @param expectedSha1
	 *            the expected lowercase hex SHA-1 hash
	 * @return {@code true} if the file is present and hash matches
	 */
	public static boolean verify(File file, String expectedSha1) {
		if (file == null || !file.exists() || !file.isFile()) return false;
		if (expectedSha1 == null || expectedSha1.isEmpty()) return false;

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] buffer = new byte[BUFFER_SIZE];
			try (FileInputStream fis = new FileInputStream(file)) {
				int read;
				while ((read = fis.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			String actualHex = toHex(digest.digest());
			return expectedSha1.equalsIgnoreCase(actualHex);
		} catch (NoSuchAlgorithmException | IOException e) {
			return false;
		}
	}

	/**
	 * Converts a byte array to its lowercase hexadecimal representation.
	 */
	static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xff));
		}
		return sb.toString();
	}

}
