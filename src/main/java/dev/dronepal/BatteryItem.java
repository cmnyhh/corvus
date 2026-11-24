package dev.dronepal;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class BatteryItem extends Item {
    private static volatile Map<String,Integer> remote = Map.of();
    BatteryItem(Properties properties) { super(properties); }

    record Catalog(Map<String,Integer> capacities) implements CustomPacketPayload {
        static final Type<Catalog> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, "battery_capacities"));
        // Both ends know the ten registered items. Send only their capacities, in stable ID order.
        static final StreamCodec<RegistryFriendlyByteBuf,Catalog> CODEC = StreamCodec.of((buf, data) -> {
            for (String id : Batteries.IDS) buf.writeVarInt(data.capacities.get(id));
        }, buf -> new Catalog(Batteries.IDS.stream().collect(Collectors.toUnmodifiableMap(id -> id, id -> {
            int value = buf.readVarInt();
            if (value <= 0) throw new IllegalArgumentException("Invalid battery capacity: " + value);
            return value;
        }))));
        public Type<Catalog> type() { return TYPE; }
    }
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Catalog.TYPE, Catalog.CODEC, (payload, context) -> remote = payload.capacities());
    }
    static void sync(OnDatapackSyncEvent event) {
        var values = Hardware.BATTERIES.stream().map(item -> Batteries.model(item.toStack()))
                .collect(Collectors.toUnmodifiableMap(Batteries.Model::id, Batteries.Model::capacity));
        var payload = new Catalog(values);
        event.getRelevantPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, payload));
    }
    public static void disconnected() { remote = Map.of(); }

    public record Gauge(int stored, int capacity) implements TooltipComponent {
        public int pixels(int width) { return (int)Math.round(width * (double)stored / capacity); }
        public int color() { return stored * 5L < capacity ? 0xe88653 : 0x52d4ab; }
        int percent() { return (int)(100L * stored / capacity); }
    }
    private static Gauge gauge(ItemStack stack, boolean client) {
        var model = Batteries.model(stack);
        int capacity = client ? remote.getOrDefault(model.id(), model.capacity()) : model.capacity();
        var data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        int energy = data.contains("energy") ? Math.clamp(data.getInt("energy"), 0, capacity) : capacity;
        return new Gauge(energy, capacity);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var gauge = gauge(stack, context.level() == null || context.level().isClientSide);
        var numbers = NumberFormat.getIntegerInstance(Locale.ROOT);
        lines.add(Component.translatable("dronepal.tip.battery_charge", numbers.format(gauge.stored()), numbers.format(gauge.capacity()), gauge.percent()));
    }
    @Override public Optional<TooltipComponent> getTooltipImage(ItemStack stack) { return Optional.of(gauge(stack, true)); }
    @Override public boolean isBarVisible(ItemStack stack) { return true; }
    @Override public int getBarWidth(ItemStack stack) { return gauge(stack, true).pixels(13); }
    @Override public int getBarColor(ItemStack stack) { return gauge(stack, true).color(); }
}
