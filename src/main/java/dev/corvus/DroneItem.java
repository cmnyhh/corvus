package dev.corvus;

import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The physical controller. It carries the serial (identity), the frame (机型) and the paint (涂装);
 * everything else about the drone is derived from those three values.
 *
 * <p>Deploying is not a right-click: the terminal is worn in the Curios {@code drone} slot and the
 * projection follows the slot (see {@link DroneDeployment}). Right-click only explains that.</p>
 */
public final class DroneItem extends Item {
    public DroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Frames.name(Frames.id(stack));
    }

    public static UUID serial(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CorvusData data = CorvusData.of(stack);
        String raw = data.getString(CorvusKeys.SERIAL);
        if (raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public static UUID ensureSerial(ItemStack stack) {
        UUID existing = serial(stack);
        if (existing != null) return existing;
        UUID fresh = UUID.randomUUID();
        CorvusData.of(stack).setString(CorvusKeys.SERIAL, fresh.toString());
        return fresh;
    }

    public static boolean deploy(ServerPlayer owner, ItemStack stack, UUID serialId) {
        if (serialId == null || stack.isEmpty() || !serialId.equals(serial(stack))) return false;
        return DroneDeployment.deploy(owner, stack, serialId);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable("message.dronepal.equip_to_deploy"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
