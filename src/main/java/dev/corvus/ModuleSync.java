package dev.corvus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/**
 * Sends the module table a datapack actually loaded to the client, so tooltips and item names show the
 * tuned numbers on a dedicated server (where the client has no copy of the datapack).
 *
 * <p>1.21 gets this for free because module data is a client-synced registry; 1.20.1 datapacks are
 * server-authoritative, which is why this packet exists at all. The table is one UTF JSON string, sent
 * on datapack sync and on login, and applied to {@link Hardware#acceptSynced(Map)}. The table is also
 * cached here, and {@link #tooltip} fills the gap while no table has arrived yet, so a
 * dedicated-server client never shows "unknown module" for a product the server does know.</p>
 */
@Mod.EventBusSubscriber(modid = Corvus.ID)
public final class ModuleSync {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-modules");
    private static final Gson GSON = new Gson();
    /** Client-side copy; also the fallback when the hardware table could not be updated. */
    private static volatile Map<ResourceLocation, Hardware.Module> synced = Map.of();

    /** One datapack snapshot, carried as JSON so a field added later needs no protocol change. */
    public record Table(String json) {
        public static void encode(Table table, FriendlyByteBuf buffer) {
            buffer.writeUtf(table.json, 262144);
        }

        public static Table decode(FriendlyByteBuf buffer) {
            return new Table(buffer.readUtf(262144));
        }
    }

    private ModuleSync() {}

    // ------------------------------------------------------------------ server

    @SubscribeEvent
    public static void datapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) send(event.getPlayer());
        else event.getPlayerList().getPlayers().forEach(ModuleSync::send);
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    public static void send(ServerPlayer player) {
        Map<ResourceLocation, Hardware.Module> table = Hardware.definitions();
        if (table.isEmpty() || player == null) return;
        JsonArray array = new JsonArray();
        for (Hardware.Module module : table.values()) array.add(toJson(module));
        CorvusNetwork.send(player, new Table(GSON.toJson(array)));
        LOGGER.info("[corvus-modules] synced {} module definitions to {}", table.size(), player.getGameProfile().getName());
    }

    private static JsonObject toJson(Hardware.Module module) {
        JsonObject json = new JsonObject();
        json.addProperty("id", module.id().toString());
        json.addProperty("family", module.family());
        json.addProperty("tier", module.tier());
        json.addProperty("drainIdle", module.drainIdle());
        json.addProperty("drainActive", module.drainActive());
        json.add("params", module.params());
        JsonArray intents = new JsonArray();
        module.intents().forEach(intents::add);
        json.add("intents", intents);
        json.addProperty("wheel", module.wheelLabel());
        json.addProperty("vendor", module.vendor());
        json.addProperty("tierLabel", module.tierLabel());
        json.addProperty("productKey", module.productKey());
        json.addProperty("productName", module.productName());
        return json;
    }

    // ------------------------------------------------------------------ client

    /**
     * Decodes the table and installs it into the hardware layer. Reached only through
     * {@link DistExecutor} from the packet handler, so a dedicated server never runs it.
     */
    public static void accept(Table table) {
        Map<ResourceLocation, Hardware.Module> parsed = new HashMap<>();
        try {
            for (JsonElement element : JsonParser.parseString(table.json).getAsJsonArray()) {
                Hardware.Module module = fromJson(element.getAsJsonObject());
                parsed.put(module.id(), module);
            }
        } catch (RuntimeException malformed) {
            LOGGER.warn("[corvus-modules] could not decode the synced table", malformed);
            return;
        }
        synced = Map.copyOf(parsed);
        // Hardware ignores an empty table on purpose: a bad packet must not blank every product name.
        Hardware.acceptSynced(synced);
        LOGGER.info("[corvus-modules] received {} module definitions, hardware table now synced={}",
                synced.size(), Hardware.synced());
    }

    private static Hardware.Module fromJson(JsonObject json) {
        Set<String> intents = new HashSet<>();
        if (json.has("intents")) {
            for (JsonElement element : json.getAsJsonArray("intents")) intents.add(element.getAsString());
        }
        return new Hardware.Module(
                new ResourceLocation(json.get("id").getAsString()),
                json.get("family").getAsString(),
                json.get("tier").getAsInt(),
                json.get("drainIdle").getAsInt(),
                json.get("drainActive").getAsInt(),
                json.getAsJsonObject("params"),
                Set.copyOf(intents),
                json.get("wheel").getAsString(),
                json.get("vendor").getAsString(),
                json.get("tierLabel").getAsString(),
                json.get("productKey").getAsString(),
                json.get("productName").getAsString());
    }

    /** The synced definition table (client), or an empty map before the first packet. */
    public static Map<ResourceLocation, Hardware.Module> synced() {
        return synced;
    }

    public static Hardware.Module definition(ResourceLocation id) {
        return id == null ? null : synced.get(id);
    }

    /**
     * Fallback for a client whose hardware table was not updated: replace the "unknown module" line
     * with the real hover text, using the hardware layer's own formatter so both paths read alike.
     */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> tooltipClient(event));
    }

    private static void tooltipClient(ItemTooltipEvent event) {
        if (synced.isEmpty() || Hardware.definition(event.getItemStack()) != null) return;
        ResourceLocation id = Hardware.productId(event.getItemStack());
        Hardware.Module module = synced.get(id);
        if (module == null) return;
        // Header lines from the item's own name/hover text are kept; only the error line is dropped.
        Component unknown = Component.translatable("item.dronepal.unknown_module");
        List<Component> lines = event.getToolTip();
        lines.removeIf(line -> line.getString().equals(unknown.getString()));
        lines.addAll(ModuleTooltips.describe(module));
    }
}
