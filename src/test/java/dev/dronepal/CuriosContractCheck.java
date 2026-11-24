package dev.dronepal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Verifies the datapack wiring and the ordinary classes on a Curios runtime. */
public final class CuriosContractCheck {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = CuriosContractCheck.class.getClassLoader();
        Class.forName("top.theillusivec4.curios.api.CuriosApi", false, loader);
        for (String name : new String[]{"Dronepal", "DroneEntity", "DroneItem"}) {
            Class<?> type = Class.forName("dev.dronepal." + name, false, loader);
            type.getDeclaredMethods();
            type.getDeclaredFields();
        }
        JsonObject slot = read("data/huhu_dronepal/curios/slots/drone.json");
        JsonObject player = read("data/huhu_dronepal/curios/entities/player.json");
        JsonObject tag = read("data/curios/tags/item/drone.json");
        require(slot.get("size").getAsInt() == 1, "one functional slot");
        require(slot.getAsJsonArray("validators").get(0).getAsString().equals("curios:tag"), "tag validator");
        require(player.getAsJsonArray("entities").get(0).getAsString().equals("minecraft:player"), "player assignment");
        require(player.getAsJsonArray("slots").get(0).getAsString().equals("drone"), "slot name");
        require(tag.getAsJsonArray("values").get(0).getAsString().equals("huhu_dronepal:drone"), "controller item tag");
        System.out.println("Curios contract passed: required API availability and functional player slot");
    }

    private static JsonObject read(String path) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                CuriosContractCheck.class.getClassLoader().getResourceAsStream(path), path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void require(boolean condition, String contract) {
        if (!condition) throw new AssertionError(contract);
    }
}
