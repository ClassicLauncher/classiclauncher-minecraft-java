package net.classiclauncher.extension.minecraftjava.launch;

import java.util.List;
import java.util.Map;

/**
 * POJO for the Minecraft version JSON descriptor. Parsed by {@link VersionJsonParser} using Gson.
 */
public class VersionJson {

	public String id;
	public String type;
	public String mainClass;
	public String assets;
	/**
	 * Legacy game arguments string (pre-1.13 versions). Null for 1.13+.
	 */
	public String minecraftArguments;
	public Arguments arguments;
	public Downloads downloads;
	public List<Library> libraries;
	public AssetIndex assetIndex;
	public JavaVersion javaVersion;
	public Logging logging;
	public int minimumLauncherVersion;

	public static class Arguments {

		public List<Argument> game;
		public List<Argument> jvm;

	}

	/**
	 * Abstract argument — either a simple string or a rule-gated conditional.
	 */
	public abstract static class Argument {
		// Subclasses: StringArgument, RuleGatedArgument
	}

	public static class StringArgument extends Argument {

		public final String value;

		public StringArgument(String value) {
			this.value = value;
		}

	}

	public static class RuleGatedArgument extends Argument {

		public final List<Rule> rules;
		public final List<String> values;

		public RuleGatedArgument(List<Rule> rules, List<String> values) {
			this.rules = rules;
			this.values = values;
		}

	}

	public static class Downloads {

		public Download client;
		public Download server;
		public Download client_mappings;
		public Download server_mappings;

	}

	public static class Download {

		public String sha1;
		public String url;
		public long size;

	}

	public static class Library {

		public String name;
		public LibraryDownload downloads;
		public List<Rule> rules;
		public Map<String, String> natives;
		public Extract extract;

	}

	public static class LibraryDownload {

		public Artifact artifact;
		public Map<String, Artifact> classifiers;

	}

	public static class Artifact {

		public String path;
		public String sha1;
		public String url;
		public long size;

	}

	public static class Extract {

		public List<String> exclude;

	}

	public static class Rule {

		public String action;
		public OsCondition os;
		public Map<String, Boolean> features;

	}

	public static class OsCondition {

		public String name;
		public String version;
		public String arch;

	}

	public static class AssetIndex {

		public String id;
		public String sha1;
		public String url;
		public long size;
		public long totalSize;

	}

	public static class JavaVersion {

		public String component;
		public int majorVersion;

	}

	public static class Logging {

		public LoggingClient client;

	}

	public static class LoggingClient {

		public String argument;
		public LogFile file;
		public String type;

	}

	public static class LogFile {

		public String id;
		public String sha1;
		public String url;
		public long size;

	}

}
