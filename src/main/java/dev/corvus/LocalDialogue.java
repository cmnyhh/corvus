package dev.corvus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of one server data reload: chips, intents and line pools.
 *
 * <p>No world and no client dependencies, so the whole dialogue ruleset is testable and a reload
 * cannot observe a half-updated library. Ported from 1.21 with {@code Math.clamp} replaced (Java 17
 * target) and {@code JsonArray.asList} avoided (Gson version pinned by the 1.20.1 toolchain).</p>
 */
final class LocalDialogue {
    record Chip(String pack, boolean chat, boolean smalltalk, double barkRate, int cooldown, float speed) {}

    record Intent(String id, List<String> aliases, List<Pattern> patterns, String response,
                  String module, String denied, String line) {}

    record Line(String id, String text, int weight, String voice) {}

    record Reply(String text, String lineId, String voice) {
        Reply(String text) {
            this(text, "", "");
        }
    }

    interface Device {
        boolean active();

        String chip();

        boolean installed(String family);

        Map<String, String> slots();

        boolean execute(String id);

        String lookup(String intent, String question);
    }

    private final Map<String, Chip> chips = new HashMap<>();
    private final Map<String, List<Line>> lines = new HashMap<>();
    private final SortedMap<String, Intent> intents = new TreeMap<>();
    private static final Pattern NOISE = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    LocalDialogue(Map<String, JsonObject> documents) {
        for (Map.Entry<String, JsonObject> entry : new TreeMap<>(documents).entrySet()) {
            String path = entry.getKey();
            JsonObject json = entry.getValue();
            try {
                if (path.startsWith("chips/")) {
                    chips.put(path.substring(6, path.length() - 5), new Chip(
                            json.get("prompt_profile").getAsString(),
                            json.get("unlocks_chat").getAsBoolean(),
                            json.get("allows_smalltalk").getAsBoolean(),
                            json.get("bark_rate").getAsDouble(),
                            json.get("bark_cooldown_ticks").getAsInt(),
                            clamp(json.get("voice_speed").getAsFloat(), 0.5F, 2F)));
                } else if (path.startsWith("intents/")) {
                    String id = json.get("id").getAsString();
                    List<String> aliases = strings(json, "aliases").stream()
                            .map(LocalDialogue::normalize).filter(s -> !s.isEmpty()).toList();
                    List<Pattern> patterns = strings(json, "patterns").stream().map(Pattern::compile).toList();
                    Intent intent = new Intent(id, aliases, patterns, json.get("response").getAsString(),
                            optional(json, "requires_module").replaceFirst("^mod_", ""),
                            optional(json, "denied"), optional(json, "line"));
                    if (intents.put(id, intent) != null) throw new IllegalArgumentException("duplicate intent " + id);
                } else if (path.startsWith("lines/")) {
                    String pack = json.get("pack").getAsString();
                    for (JsonElement element : json.getAsJsonArray("lines")) {
                        JsonObject item = element.getAsJsonObject();
                        String id = item.get("id").getAsString();
                        String key = pack + "/" + (item.has("trigger") ? item.get("trigger").getAsString() : id);
                        int weight = item.get("weight").getAsInt();
                        if (weight < 1 || weight > 1000000) throw new IllegalArgumentException("invalid line weight");
                        List<Line> pool = lines.computeIfAbsent(key, unused -> new ArrayList<>());
                        if (pool.stream().anyMatch(line -> line.id().equals(id))) {
                            throw new IllegalArgumentException("duplicate line " + id);
                        }
                        pool.add(new Line(id, item.get("text").getAsString(), weight, optional(item, "voice")));
                    }
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid dronepal dialogue resource " + path, failure);
            }
        }
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }

    private static List<String> strings(JsonObject node, String key) {
        if (!node.has(key)) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : node.getAsJsonArray(key)) result.add(element.getAsString());
        return result;
    }

    private static String optional(JsonObject node, String key) {
        return node.has(key) && !node.get(key).isJsonNull() ? node.get(key).getAsString() : "";
    }

    static String normalize(String text) {
        return NOISE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    Intent match(String text) {
        String query = normalize(text);
        Intent partial = null;
        int longest = 0;
        for (Intent intent : intents.values()) {
            for (String alias : intent.aliases()) {
                if (query.equals(alias)) return intent;
                // One-character commands are ambiguous inside sentences (e.g. “上”).
                if (alias.length() > Math.max(1, longest) && query.contains(alias)) {
                    longest = alias.length();
                    partial = intent;
                }
            }
        }
        if (partial != null) return partial;
        for (Intent intent : intents.values()) {
            for (Pattern pattern : intent.patterns()) {
                if (pattern.matcher(query).matches()) return intent;
            }
        }
        return null;
    }

    Chip profile(String id) {
        return chips.get(id);
    }

    /** Loaded chip ids, for the smoke harness and the terminal's chip list. */
    java.util.Set<String> chips() {
        return java.util.Set.copyOf(chips.keySet());
    }

    Reply respond(Device device, String text, String previous, RandomGenerator random) {
        if (!device.active()) return new Reply("请先部署自己的无人机。");
        Chip chip = profile(device.chip());
        if (chip == null || !chip.chat()) return new Reply("对话未启用，请安装战术或话痨芯片。");
        if (text.length() > 256) return new Reply("指令最多 256 个字符。");
        Intent intent = match(text);
        if (intent == null) return speak(chip, "unknown", device.slots(), previous, random);
        if (!intent.module().isEmpty() && !device.installed(intent.module())) {
            return new Reply(intent.denied().isEmpty() ? "缺少模块：" + intent.module() : intent.denied());
        }
        switch (intent.response()) {
            case "lookup":
                return new Reply(device.lookup(intent.id(), text));
            case "refuse":
                return new Reply(intent.line());
            case "smalltalk":
                return speak(chip, chip.smalltalk() ? "smalltalk" : "smalltalk_refuse", device.slots(), previous, random);
            case "line":
                return speak(chip, intent.line(), device.slots(), previous, random);
            case "data":
            case "action":
                switch (intent.id()) {
                    // Scan, medic and cargo orders all execute through the same injected handler; the
                    // cover/hold combat orders stay "not implemented" until the combat layer routes them.
                    case "scan", "scan.hostile", "scan.ore", "order.heal", "order.fetch", "order.supply",
                         "order.light" -> {
                        if (!device.execute(intent.id())) return new Reply("当前无法执行，请检查能量、目标或冷却。");
                        // A successful scan answers exactly once, when its results are ready.
                        return new Reply(intent.id().startsWith("scan") ? "" : "指令已执行。");
                    }
                    default -> {
                        return new Reply("当前固件尚未实现这项指令。");
                    }
                }
            default:
                return new Reply("未知的对话响应类型。");
        }
    }

    Reply speak(Chip chip, String trigger, Map<String, String> slots, String previous, RandomGenerator random) {
        List<Line> pool = lines.getOrDefault(chip.pack() + "/" + trigger, List.of());
        if (pool.isEmpty()) return new Reply("当前芯片缺少台词：" + trigger);
        List<Line> available = pool.size() == 1 ? pool
                : pool.stream().filter(line -> !line.id().equals(previous)).toList();
        long draw = random.nextLong(available.stream().mapToLong(Line::weight).sum());
        for (Line line : available) {
            draw -= line.weight();
            if (draw < 0) {
                String text = line.text();
                for (Map.Entry<String, String> slot : slots.entrySet()) {
                    String value = slot.getValue() == null ? "" : slot.getValue();
                    text = text.replace("{" + slot.getKey() + "}", value);
                }
                return new Reply(text, line.id(), line.voice());
            }
        }
        throw new AssertionError("invalid weight total");
    }
}
