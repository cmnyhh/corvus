package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class DroneItem extends Item {
    static final String ACTIVE = "dronepal_active";
    private static final String SERIAL = "dronepal_serial";
    DroneItem(Properties props) { super(props); }

    @Override public Component getName(ItemStack stack) { return Frames.name(Frames.id(stack)); }

    /** The airframe this controller belongs to, or {@code null} for anything that is not a controller. */
    public static UUID serial(ItemStack stack) {
        if (!stack.is(Dronepal.DRONE.get())) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID(SERIAL) ? tag.getUUID(SERIAL) : null;
    }

    static UUID ensureSerial(ItemStack stack) {
        UUID id = serial(stack);
        if (id != null) return id;
        UUID fresh = UUID.randomUUID();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putUUID(SERIAL, fresh));
        return fresh;
    }

    static boolean deploy(ServerPlayer owner, ItemStack stack, UUID id) {
        ServerLevel world = owner.serverLevel();
        if (id == null || stack != CuriosDrones.equipped(owner) || !id.equals(serial(stack))) return false;
        if (DroneLifecycle.repairing(world, id)) return false;
        for (ServerLevel dim : world.getServer().getAllLevels()) {
            Entity existing = dim.getEntity(id);
            if (existing != null) return false;
        }
        DroneEntity drone = new DroneEntity(Dronepal.ENTITY.get(), world);
        drone.setUUID(id);
        drone.holdingStation = DroneRemote.disabled(stack, "follow");
        drone.owner = owner.getUUID();
        Frames.migrate(stack);
        drone.setFrame(Frames.id(stack));
        drone.setPaint(Frames.paint(stack));
        drone.setPos(DroneEntity.station(owner));
        if (!world.addFreshEntity(drone)) return false;
        owner.getPersistentData().putUUID(ACTIVE, id);
        DroneArchive.deployed(world, drone);
        return true;
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer owner) owner.displayClientMessage(Component.translatable("message.dronepal.equip_to_deploy"), true);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
