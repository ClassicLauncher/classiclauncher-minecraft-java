package net.classiclauncher.extension.minecraftjava.launch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.classiclauncher.launcher.launch.LaunchProgress;
import net.classiclauncher.launcher.platform.Platform;

/**
 * Resolves, verifies, and downloads Minecraft libraries.
 *
 * <p>
 * Returns a {@link ResolvedLibraries} containing the classpath JARs and native JARs for the current platform.
 */
public final class MinecraftLibraryResolver {

	private MinecraftLibraryResolver() {
	}

	/**
	 * Resolves all libraries from the version JSON: filters by platform rules, verifies SHA-1, downloads if missing or
	 * corrupt, and separates native JARs from classpath JARs.
	 *
	 * @param libraries
	 *            the library list from the version JSON
	 * @param librariesDir
	 *            the root libraries directory (Minecraft layout)
	 * @param progress
	 *            progress callback
	 * @return the resolved library sets
	 * @throws IOException
	 *             if a required library cannot be downloaded
	 */
	public static ResolvedLibraries resolveAndDownload(List<VersionJson.Library> libraries, File librariesDir,
			LaunchProgress progress) throws IOException {

		List<File> classpath = new ArrayList<>();
		List<File> nativeJars = new ArrayList<>();

		if (libraries == null) return new ResolvedLibraries(classpath, nativeJars);

		String platformOs = toPlatformOs(Platform.current());

		for (VersionJson.Library library : libraries) {
			// 1. Evaluate platform rules
			if (!libraryAppliesToCurrentPlatform(library)) {
				progress.fileCompleted();
				continue;
			}

			VersionJson.LibraryDownload downloads = library.downloads;

			// 2. Main artifact (classpath JAR)
			if (downloads != null && downloads.artifact != null) {
				VersionJson.Artifact artifact = downloads.artifact;
				if (artifact.path != null && !artifact.path.isEmpty()) {
					File jar = new File(librariesDir, artifact.path);
					if (!Sha1Verifier.verify(jar, artifact.sha1)) {
						if (artifact.url != null && !artifact.url.isEmpty()) {
							progress.log("Downloading library: " + library.name);
							MinecraftFileDownloader.download(artifact.url, jar, artifact.sha1, jar.getName(), progress);
						}
					}
					if (jar.exists()) classpath.add(jar);
				}
			}

			// 3. Native JAR
			File nativeJar = resolveNativeJar(library, downloads, librariesDir, platformOs, progress);
			if (nativeJar != null) nativeJars.add(nativeJar);

			progress.fileCompleted();
		}

		return new ResolvedLibraries(classpath, nativeJars);
	}

	private static File resolveNativeJar(VersionJson.Library library, VersionJson.LibraryDownload downloads,
			File librariesDir, String platformOs, LaunchProgress progress) throws IOException {
		// Modern format: library name contains ":natives-<os>"
		if (library.name != null && library.name.contains(":natives-")) {
			// The main artifact IS the native JAR in modern format
			if (downloads != null && downloads.artifact != null) {
				VersionJson.Artifact artifact = downloads.artifact;
				if (artifact.path != null && !artifact.path.isEmpty()) {
					File jar = new File(librariesDir, artifact.path);
					if (!Sha1Verifier.verify(jar, artifact.sha1)) {
						if (artifact.url != null && !artifact.url.isEmpty()) {
							progress.log("Downloading native: " + library.name);
							MinecraftFileDownloader.download(artifact.url, jar, artifact.sha1, jar.getName(), progress);
						}
					}
					return jar.exists() ? jar : null;
				}
			}
		}

		// Legacy format: natives map + classifiers
		if (library.natives != null && downloads != null && downloads.classifiers != null) {
			String classifier = library.natives.get(platformOs);
			if (classifier == null) return null;

			VersionJson.Artifact nativeArtifact = downloads.classifiers.get(classifier);
			if (nativeArtifact == null) return null;

			if (nativeArtifact.path != null && !nativeArtifact.path.isEmpty()) {
				File jar = new File(librariesDir, nativeArtifact.path);
				if (!Sha1Verifier.verify(jar, nativeArtifact.sha1)) {
					if (nativeArtifact.url != null && !nativeArtifact.url.isEmpty()) {
						progress.log("Downloading native: " + library.name + " (" + classifier + ")");
						MinecraftFileDownloader.download(nativeArtifact.url, jar, nativeArtifact.sha1, jar.getName(),
								progress);
					}
				}
				return jar.exists() ? jar : null;
			}
		}

		return null;
	}

	private static boolean libraryAppliesToCurrentPlatform(VersionJson.Library library) {
		if (library.rules == null || library.rules.isEmpty()) return true;

		boolean allowed = false;
		for (VersionJson.Rule rule : library.rules) {
			if (ArgumentEvaluator.matchesCurrentPlatform(rule, false)) {
				allowed = "allow".equals(rule.action);
			}
		}
		return allowed;
	}

	private static String toPlatformOs(Platform platform) {
		switch (platform) {
			case MACOS :
				return "osx";
			case WINDOWS :
				return "windows";
			case LINUX :
				return "linux";
			default :
				return "unknown";
		}
	}

	/**
	 * Result of library resolution.
	 */
	public static final class ResolvedLibraries {

		public final List<File> classpath;
		public final List<File> nativeJars;

		ResolvedLibraries(List<File> classpath, List<File> nativeJars) {
			this.classpath = classpath;
			this.nativeJars = nativeJars;
		}

	}

}
