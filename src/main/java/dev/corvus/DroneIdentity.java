package dev.corvus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.corvus.data.CorvusData;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Reads the controller's physical frame identity and the player-given name.
 *
 * <p>1.20.1 has no data components, so the product/callsign pair is read from the frame definition
 * ({@code data/huhu_dronepal/frames/*.json}) through the classpath once, and the nickname comes from
 * the controller's NBT or its custom name. Same public shape as the 1.21 record.</p>
 */
public record DroneIdentity(String product, String callsign, String nickname) {
    /** NBT key shared with the wheel/terminal nickname field; same key as the 1.21 build. */
    static final String NICKNAME = "dronepal_name";

    private record Product(String product, String callsign) {}

    private static final Map<String, Product> PRODUCTS = load();

    private static Map<String, Product> load() {
        Map<String, Product> result = new HashMap<>();
        for (String id : Frames.IDS) {
            String path = "/data/huhu_dronepal/frames/" + id + ".json";
            try (InputStream in = DroneIdentity.class.getResourceAsStream(path)) {
                if (in == null) continue;
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    String product = json.has("product_name") ? json.get("product_name").getAsString() : id;
                    String prefix = json.has("callsign_prefix") ? json.get("callsign_prefix").getAsString() : "DR";
                    String number = json.has("callsign_number") ? json.get("callsign_number").getAsString() : "00";
                    result.put(id, new Product(product, prefix + "-" + number));
                }
            } catch (Exception malformed) {
                // A frame without a readable definition still has to render and follow.
            }
        }
        return Map.copyOf(result);
    }

    public static DroneIdentity of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new DroneIdentity("Corvus", "DR-00", "");
        String frame = Frames.id(stack);
        Product product = PRODUCTS.getOrDefault(frame, new Product(frame, "DR-00"));
        String nickname = CorvusData.of(stack).getString(NICKNAME).strip();
        if (nickname.isEmpty() && stack.hasCustomHoverName()) {
            nickname = Objects.requireNonNull(stack.getHoverName()).getString().strip();
        }
        return new DroneIdentity(product.product(), product.callsign(), nickname);
    }
}
