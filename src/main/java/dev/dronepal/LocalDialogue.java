package dev.dronepal;

import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Immutable snapshot of one server data reload. No world or client dependencies. */
final class LocalDialogue {
    record Chip(String pack, boolean chat, boolean smalltalk, double barkRate, int cooldown, float speed) {}
    record Intent(String id, List<String> aliases, List<Pattern> patterns, String response,
                  String module, String denied, String line) {}
    record Line(String id, String text, int weight, String voice) {}
    record Reply(String text, String lineId, String voice) {
        Reply(String text) { this(text, "", ""); }
    }
    interface Device {
        boolean active();
        String chip();
        boolean installed(String family);
        Map<String,String> slots();
        boolean execute(String id);
        String lookup(String intent, String question);
    }
    private final Map<String,Chip> chips = new HashMap<>();
    private final Map<String,List<Line>> lines = new HashMap<>();
    private final SortedMap<String,Intent> intents = new TreeMap<>();
    private static final Pattern NOISE = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    LocalDialogue(Map<String,JsonObject> documents) {
        for (var entry : new TreeMap<>(documents).entrySet()) {
            String path = entry.getKey();
            JsonObject json = entry.getValue();
            try {
                if (path.startsWith("chips/")) {
                    chips.put(path.substring(6, path.length()-5), new Chip(json.get("prompt_profile").getAsString(),
                        json.get("unlocks_chat").getAsBoolean(), json.get("allows_smalltalk").getAsBoolean(),
                        json.get("bark_rate").getAsDouble(), json.get("bark_cooldown_ticks").getAsInt(), Math.clamp(json.get("voice_speed").getAsFloat(), 0.5F, 2F)));
                } else if (path.startsWith("intents/")) {
                    String id = json.get("id").getAsString();
                    var aliases = strings(json, "aliases").stream().map(LocalDialogue::normalize).filter(s -> !s.isEmpty()).toList();
                    var patterns = strings(json, "patterns").stream().map(Pattern::compile).toList();
                    var intent = new Intent(id, aliases, patterns, json.get("response").getAsString(),
                        optional(json, "requires_module").replaceFirst("^mod_", ""), optional(json, "denied"), optional(json, "line"));
                    if (intents.put(id, intent) != null) throw new IllegalArgumentException("duplicate intent " + id);
                } else if (path.startsWith("lines/")) {
                    String pack = json.get("pack").getAsString();
                    for (var element : json.getAsJsonArray("lines")) {
                        var item = element.getAsJsonObject();
                        String id = item.get("id").getAsString();
                        String key = pack + "/" + (item.has("trigger") ? item.get("trigger").getAsString() : id);
                        int weight = item.get("weight").getAsInt();
                        if (weight < 1 || weight > 1000000) throw new IllegalArgumentException("invalid line weight");
                        var pool = lines.computeIfAbsent(key, unused -> new ArrayList<>());
                        if (pool.stream().anyMatch(line -> line.id().equals(id))) throw new IllegalArgumentException("duplicate line " + id);
                        pool.add(new Line(id, item.get("text").getAsString(), weight, optional(item, "voice")));
                    }
                }
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid dronepal dialogue resource " + path, ex); }
        }
    }
    private static List<String> strings(JsonObject node, String key) {
        if (!node.has(key)) return List.of();
        return node.getAsJsonArray(key).asList().stream().map(e -> e.getAsString()).toList();
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
        for (var intent : intents.values()) {
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
        for (var intent : intents.values())
            if (intent.patterns().stream().anyMatch(p -> p.matcher(query).matches())) return intent;
        return null;
    }
    Chip profile(String id) { return chips.get(id); }

    Reply respond(Device device, String text, String previous, RandomGenerator random) {
        if (!device.active()) return new Reply("请先部署自己的无人机。");
        var chip = profile(device.chip());
        if (chip == null || !chip.chat()) return new Reply("对话未启用，请安装战术或话痨芯片。");
        if (text.length() > 256) return new Reply("指令最多 256 个字符。");
        var intent = match(text);
        if (intent == null) return speak(chip, "unknown", device.slots(), previous, random);
        if (!intent.module().isEmpty() && !device.installed(intent.module()))
            return new Reply(intent.denied().isEmpty() ? "缺少模块：" + intent.module() : intent.denied());
        return switch (intent.response()) {
            case "lookup" -> new Reply(device.lookup(intent.id(), text));
            case "refuse" -> new Reply(intent.line());
            case "smalltalk" -> speak(chip, chip.smalltalk() ? "smalltalk" : "smalltalk_refuse", device.slots(), previous, random);
            case "line" -> speak(chip, intent.line(), device.slots(), previous, random);
            case "data", "action" -> switch (intent.id()) {
                case "scan", "scan.hostile", "scan.ore", "order.heal", "order.cover", "order.hold", "order.work", "order.idle" -> {
                    if (!device.execute(intent.id())) yield new Reply("当前无法执行，请检查能量、目标或冷却。");
                    // Successful scans answer exactly once when their results are ready.
                    yield new Reply(intent.id().startsWith("scan") ? "" : "指令已执行。");
                }
                default -> new Reply("当前固件尚未实现这项指令。");
            };
            default -> new Reply("未知的对话响应类型。");
        };
    }
    Reply speak(Chip chip, String trigger, Map<String,String> slots, String previous, RandomGenerator random) {
        var pool = lines.getOrDefault(chip.pack() + "/" + trigger, List.of());
        if (pool.isEmpty()) return new Reply("当前芯片缺少台词：" + trigger);
        var available = pool.size() == 1 ? pool : pool.stream().filter(l -> !l.id().equals(previous)).toList();
        long draw = random.nextLong(available.stream().mapToLong(Line::weight).sum());
        for (Line line : available) {
            draw -= line.weight();
            if (draw < 0) {
                String text = line.text();
                for (var slot : slots.entrySet()) text = text.replace("{" + slot.getKey() + "}", slot.getValue());
                return new Reply(text, line.id(), line.voice());
            }
        }
        throw new AssertionError("invalid weight total");
    }
}
