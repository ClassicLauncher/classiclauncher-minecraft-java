package net.classiclauncher.extension.minecraftjava.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.classiclauncher.launcher.platform.Platform;

/**
 * Evaluates Minecraft version JSON argument lists, applying platform rules and substituting {@code ${variable}} tokens.
 */
public final class ArgumentEvaluator {

	private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

	private ArgumentEvaluator() {
	}

	/**
	 * Evaluates a list of arguments, applying rule filtering and variable substitution.
	 *
	 * @param args
	 *            the argument list from the version JSON
	 * @param vars
	 *            variable map for {@code ${name}} substitution
	 * @param hasCustomResolution
	 *            whether the profile has a custom resolution set
	 * @return flat list of evaluated argument strings
	 */
	public static List<String> evaluate(List<VersionJson.Argument> args, Map<String, String> vars,
			boolean hasCustomResolution) {
		List<String> result = new ArrayList<>();
		if (args == null) return result;

		for (VersionJson.Argument arg : args) {
			if (arg instanceof VersionJson.StringArgument) {
				result.add(substituteVars(((VersionJson.StringArgument) arg).value, vars));

			} else if (arg instanceof VersionJson.RuleGatedArgument) {
				VersionJson.RuleGatedArgument rga = (VersionJson.RuleGatedArgument) arg;
				if (rulesAllow(rga.rules, hasCustomResolution)) {
					for (String value : rga.values) {
						result.add(substituteVars(value, vars));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Evaluates the rules list using last-match-wins semantics. If the list is empty, the argument is always included.
	 */
	static boolean rulesAllow(List<VersionJson.Rule> rules, boolean hasCustomResolution) {
		if (rules == null || rules.isEmpty()) return true;

		boolean allowed = false;
		for (VersionJson.Rule rule : rules) {
			if (matchesCurrentPlatform(rule, hasCustomResolution)) {
				allowed = "allow".equals(rule.action);
			}
		}
		return allowed;
	}

	/**
	 * Returns {@code true} if the given rule's conditions match the current platform.
	 */
	static boolean matchesCurrentPlatform(VersionJson.Rule rule, boolean hasCustomResolution) {
		// Check OS condition
		if (rule.os != null) {
			String mojangOs = toMojangOs(Platform.current());
			if (rule.os.name != null && !rule.os.name.isEmpty()) {
				if (!rule.os.name.equals(mojangOs)) return false;
			}
			if (rule.os.arch != null && !rule.os.arch.isEmpty()) {
				String arch = System.getProperty("os.arch", "");
				boolean isX86 = arch.equals("x86") || arch.equals("i386") || arch.equals("i686");
				if ("x86".equals(rule.os.arch) && !isX86) return false;
			}
		}

		// Check features
		if (rule.features != null) {
			for (Map.Entry<String, Boolean> feature : rule.features.entrySet()) {
				boolean expected = Boolean.TRUE.equals(feature.getValue());
				boolean actual;
				if ("has_custom_resolution".equals(feature.getKey())) {
					actual = hasCustomResolution;
				} else {
					actual = false; // unknown features default to false
				}
				if (actual != expected) return false;
			}
		}

		return true;
	}

	private static String toMojangOs(Platform platform) {
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
	 * Substitutes {@code ${variable}} tokens in a string using the provided variable map. Unknown variables are left
	 * as-is.
	 */
	public static String substituteVars(String s, Map<String, String> vars) {
		if (s == null || !s.contains("${")) return s;
		Matcher m = VAR_PATTERN.matcher(s);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String key = m.group(1);
			String value = vars.get(key);
			m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

}
