package net.classiclauncher.extension.minecraftjava.launch;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;

/**
 * Parses a Minecraft version JSON string into a {@link VersionJson} POJO.
 *
 * <p>
 * The complexity lies in the {@code arguments.jvm} and {@code arguments.game} arrays, which contain a mix of plain
 * strings and rule-gated objects. A custom {@link JsonDeserializer} handles this polymorphism.
 */
public final class VersionJsonParser {

	private static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(VersionJson.Argument.class, new ArgumentDeserializer()).create();

	private VersionJsonParser() {
	}

	/**
	 * Parses the given version JSON string.
	 *
	 * @param json
	 *            the raw version JSON (UTF-8 string)
	 * @return the parsed {@link VersionJson}
	 * @throws JsonParseException
	 *             if the JSON is malformed
	 */
	public static VersionJson parse(String json) {
		return GSON.fromJson(json, VersionJson.class);
	}

	private static final class ArgumentDeserializer implements JsonDeserializer<VersionJson.Argument> {

		@Override
		public VersionJson.Argument deserialize(JsonElement el, Type type, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (el.isJsonPrimitive()) {
				return new VersionJson.StringArgument(el.getAsString());
			}

			if (el.isJsonObject()) {
				JsonObject obj = el.getAsJsonObject();

				// Parse rules
				List<VersionJson.Rule> rules = new ArrayList<>();
				if (obj.has("rules") && obj.get("rules").isJsonArray()) {
					for (JsonElement ruleEl : obj.get("rules").getAsJsonArray()) {
						rules.add(ctx.deserialize(ruleEl, VersionJson.Rule.class));
					}
				}

				// Parse value — can be a single string or an array of strings
				List<String> values = new ArrayList<>();
				if (obj.has("value")) {
					JsonElement valueEl = obj.get("value");
					if (valueEl.isJsonPrimitive()) {
						values.add(valueEl.getAsString());
					} else if (valueEl.isJsonArray()) {
						JsonArray arr = valueEl.getAsJsonArray();
						for (JsonElement v : arr) {
							values.add(v.getAsString());
						}
					}
				}

				return new VersionJson.RuleGatedArgument(rules, values);
			}

			throw new JsonParseException("Unexpected Argument element type: " + el.getClass().getSimpleName());
		}

	}

}
