package dev.corvus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The nine spray cans, one per paint.
 *
 * <p>Right-clicking a deployed drone with a can writes the paint into the controller's NBT and pushes
 * it onto the entity, so the texture changes immediately and survives a recall. Only the owning player
 * may repaint, and only the terminal that is actually flying (serial match) is touched — otherwise a
 * second terminal in the inventory would silently change colour.</p>
 */
public final class PaintCans {
    /** Palette order, identical to {@link Frames#PAINTS}: the entity stores the paint by index. */
    public static final List<String> PALETTE = Frames.PAINTS;

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final Map<String, RegistryObject<Item>> CANS = new LinkedHashMap<>();

    private PaintCans() {}

    static {
        for (String color : PALETTE) {
            CANS.put(color, ITEMS.register("paint_can_" + color, () -> new Can(new Item.Properties())));
        }
    }

    static void register(IEventBus bus) {
        ITEMS.register(bus);
        MinecraftForge.EVENT_BUS.addListener(PaintCans::onEntityInteract);
    }

    static void addToTab(net.minecraft.world.item.CreativeModeTab.Output output) {
        CANS.values().forEach(can -> output.accept(new ItemStack(can.get())));
    }

    /** The paint colour a can applies, or null when the stack is not one of ours. */
    @Nullable
    public static String colorOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Map.Entry<String, RegistryObject<Item>> entry : CANS.entrySet()) {
            if (stack.is(entry.getValue().get())) return entry.getKey();
        }
        return null;
    }

    /**
     * Core of the spray interaction: the paint goes into the terminal's NBT (so it survives a recall
     * and a world save) and onto the entity's synced data (so the texture changes right now).
     *
     * <p>Returns false when the colour is unknown or already applied, which keeps the can in hand.</p>
     */
    public static boolean paint(ItemStack controller, @Nullable DroneEntity drone, String color) {
        if (color == null || !PALETTE.contains(color)) return false;
        boolean sameOnTerminal = Frames.paint(controller).equals(color);
        boolean sameOnEntity = drone == null || color.equals(drone.getPaintName());
        if (sameOnTerminal && sameOnEntity) return false;
        Frames.setPaint(controller, color);
        if (drone != null) drone.setPaint(color);
        return true;
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DroneEntity drone)) return;
        String color = colorOf(event.getItemStack());
        if (color == null) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID owner = drone.ownerId();
            if (owner == null || !owner.equals(player.getUUID())) return;
            ItemStack controller = DroneCurios.equipped(player);
            if (controller.isEmpty()) return;
            UUID serial = DroneItem.serial(controller);
            if (serial == null || !serial.equals(drone.getUUID())) return;
            if (!paint(controller, drone, color)) return;
            if (!player.isCreative()) event.getItemStack().shrink(1);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        event.setCanceled(true);
    }

    /** A can advertises its colour and the hint that explains how to use it. */
    private static final class Can extends Item {
        private Can(Properties properties) {
            super(properties);
        }

        @Override
        public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
            String color = colorOf(stack);
            if (color != null) {
                lines.add(Component.translatable("terminal.dronepal.paint." + color)
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            lines.add(Component.translatable("terminal.dronepal.spray")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    /** Diagnostics: which cans are registered, for the smoke harness. */
    public static String describe() {
        StringBuilder text = new StringBuilder();
        CANS.forEach((color, item) -> text.append(color).append('=').append(item.getId()).append(' '));
        return text.toString().trim();
    }
}
