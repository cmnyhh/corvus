package dev.dronepal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.*;
import java.util.*;

public final class DialogueCheck {
    private static final class Fixture implements LocalDialogue.Device {
        boolean active, scan, success = true;
        String chip = "silent", previous = "", answer = "";
        int runs;
        public boolean active() { return active; }
        public String chip() { return chip; }
        public boolean installed(String family) { return scan && family.equals("scan"); }
        public Map<String,String> slots() { return Map.of("energy", "73", "modules", "scan Mk1"); }
        public String lookup(String id, String question) { return id; }
        public boolean execute(String id) {
            if (!id.equals("scan")) throw new AssertionError(id);
            runs++;
            return success;
        }
    }
    /** Device that reports every module fitted, records what was dispatched and answers pass/fail. */
    private static final class Orders implements LocalDialogue.Device {
        boolean success = true;
        final List<String> executed = new ArrayList<>();
        public boolean active() { return true; }
        public String chip() { return "tactical"; }
        public boolean installed(String family) { return true; }
        public Map<String,String> slots() { return Map.of(); }
        public boolean execute(String id) { executed.add(id); return success; }
        public String lookup(String id, String question) { return id; }
    }
    static void expect(boolean test, String description) { if (!test) throw new AssertionError(description); }
    public static void main(String[] ignored) throws Exception {
        var docs = new HashMap<String,JsonObject>();
        Path root = Path.of("src/main/resources/data/huhu_dronepal");
        for (String name : List.of("chips", "lines", "intents")) {
            try (var stream = Files.walk(root.resolve(name))) {
                for (var p : stream.filter(f -> f.toString().endsWith(".json")).toList())
                    docs.put(root.relativize(p).toString().replace('\\','/'), JsonParser.parseString(Files.readString(p)).getAsJsonObject());
            }
        }
        LocalDialogue[] library = {new LocalDialogue(docs)};
        var rng = new Random(13);
        var d = new CommandDispatcher<Fixture>();
        var device = new Fixture();
        DialogueCommands.register(d, (source, text) -> {
            var reply = library[0].respond(source, text, source.previous, rng);
            source.previous = reply.lineId();
            source.answer = reply.text();
            return 1;
        });
        d.execute("dronepal say 你好", device);
        expect(device.answer.contains("部署"), device.answer);
        device.active = true;
        d.execute("dronepal say 谁", device);
        expect(device.answer.contains("芯片"), device.answer);
        device.chip = "tactical";
        d.execute("dronepal", device);
        expect(device.answer.contains("scan Mk1"), device.answer);
        d.execute("dronepal 电量？", device);
        expect(device.answer.contains("73 FE"), device.answer);
        d.execute("dronepal say 讲个笑话", device);
        expect(device.answer.contains("任务范围"), device.answer);
        device.chip = "chatty";
        d.execute("dronepal say 讲个笑话", device);
        expect(device.answer.contains("矿洞"), device.answer);
        d.execute("dronepal say 请帮我扫描", device);
        expect(device.runs == 0 && device.answer.contains("没有扫描模块"), device.answer);
        device.scan = true;
        d.execute("dronepal say ＳＭ！", device);
        expect(device.runs == 1 && device.answer.isEmpty(), device.answer);
        device.success = false;
        d.execute("dronepal say 探一探附近", device);
        expect(device.runs == 2 && device.answer.contains("无法执行"), device.answer);
        d.execute("dronepal 帮我放个方块", device);
        expect(device.runs == 2 && device.answer.equals("我没有机械臂。"), device.answer);
        d.execute("dronepal abcdef", device);
        expect(device.answer.contains("不懂"), device.answer);
        Map<String,Integer> frequencies = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            String prev = device.previous;
            d.execute("dronepal say Hello!", device);
            expect(!device.previous.equals(prev), "repeated " + prev);
            frequencies.merge(device.previous, 1, Integer::sum);
        }
        expect(frequencies.get("hello") > frequencies.get("wave") && frequencies.get("wave") > frequencies.get("ready"), frequencies.toString());
        var nameLine = docs.get("lines/chatty/meta.json").getAsJsonArray("lines").get(0).getAsJsonObject();
        nameLine.addProperty("text", "重载后的新台词");
        library[0] = new LocalDialogue(docs);
        d.execute("dronepal say 你是谁", device);
        expect(device.answer.equals("重载后的新台词"), device.answer);
        for (String chip : List.of("chatty", "tactical")) {
            for (String trigger : List.of("friend_low", "hostile_group", "no_ammo", "fall", "dimension", "nightfall", "rain_start", "low_battery", "module_offline", "death", "respawn", "rare_ore")) {
                var line = library[0].speak(library[0].profile(chip), trigger, Map.of("hp", "20", "energy", "100"), "", rng);
                expect(!line.lineId().isEmpty() && !line.text().contains("{"), "missing or unfilled bark " + chip + '/' + trigger);
            }
        }
        var barks = new DroneBarks();
        var day = new DroneBarks.State("home", false, false, false, false);
        expect(barks.advance(day, 0, 0, 1, 40) == null, "unexpected first greeting");
        expect("nightfall".equals(barks.advance(new DroneBarks.State("home", false, false, true, false), 20, 0, 1, 40)), "dusk");
        expect(barks.advance(new DroneBarks.State("home", false, false, true, true), 40, 0, 1, 40) == null, "cooldown");
        barks.advance(day, 60, 0, 1, 40);
        expect("rain_start".equals(barks.advance(new DroneBarks.State("home", false, false, false, true), 80, 0, 1, 40)), "rain");
        expect("dimension".equals(barks.advance(new DroneBarks.State("cave", false, false, false, false), 120, 0, 1, 40)), "dimension");
        expect("low_battery".equals(barks.advance(new DroneBarks.State("cave", false, true, false, false), 160, 0, 1, 40)), "low battery");
        expect("friend_low".equals(barks.advance(new DroneBarks.State("cave", true, true, false, false), 200, 0, 1, 40)), "low health");
        expect(barks.advance(day, 240, 0, 1, 40) == null, "revisited dimension");
        expect(barks.advance(new DroneBarks.State("home", true, true, false, false), 280, .9, .2, 40) == null, "probability");
        // ---- R3: the eleven implemented orders dispatch on this build too, with the same words ----
        // The same sentences are asserted in the 1.20.1 GapSmoke (whitelist phase), so a whitelist that
        // drifts again fails on one side immediately.
        String[][] cases = {
                {"扫描", "scan"}, {"附近有怪吗", "scan.hostile"}, {"找矿", "scan.ore"},
                {"治疗", "order.heal"}, {"捡一下", "order.fetch"}, {"递弹", "order.supply"},
                {"开灯", "order.light"}, {"掩护我", "order.cover"}, {"停火", "order.hold"},
                {"上工", "order.work"}, {"回来", "order.idle"}};
        for (String[] sample : cases) {
            var intent = library[0].match(sample[0]);
            expect(intent != null && sample[1].equals(intent.id()),
                    sample[0] + " -> " + (intent == null ? "unknown" : intent.id()));
        }
        var orders = new Orders();
        orders.success = true;
        expect("指令已执行。".equals(library[0].respond(orders, "开灯", "", rng).text()), "light success line");
        orders.success = false;
        expect("当前无法执行，请检查能量、目标或冷却。".equals(library[0].respond(orders, "开灯", "", rng).text()),
                "light refusal line");
        orders.executed.clear();
        orders.success = true;
        for (String[] sample : cases) library[0].respond(orders, sample[0], "", rng);
        expect(orders.executed.size() == cases.length, "not every whitelisted order dispatched: " + orders.executed);
        var extra = new HashMap<>(docs);
        extra.put("intents/order.hack.json", JsonParser.parseString(
                "{\"id\":\"order.hack\",\"aliases\":[\"hack\"],\"response\":\"action\"}").getAsJsonObject());
        var strict = new LocalDialogue(extra);
        orders.executed.clear();
        expect("当前固件尚未实现这项指令。".equals(strict.respond(orders, "hack", "", rng).text()),
                "an unknown action order is not dispatched");
        expect(orders.executed.isEmpty(), "an unknown order must not reach the handler");
        System.out.println("DialogueCheck passed: deployed/silent gates, personality, status, intent dispatch, module gates, refusal, reload, weighted non-repeat, R3 whitelist (" + cases.length + " orders) " + frequencies);
    }
}
