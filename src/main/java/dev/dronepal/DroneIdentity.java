package dev.dronepal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Reads the controller's physical frame and player-given name. */
public record DroneIdentity(String product, String callsign, String nickname) {
    public static DroneIdentity of(ItemStack stack) {
        var frame = Frames.of(stack);
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String nickname = stack.getOrDefault(Frames.NAME.get(), tag.getString("dronepal:name")).strip();
        if (nickname.isEmpty() && stack.has(DataComponents.CUSTOM_NAME)) {
            nickname = stack.get(DataComponents.CUSTOM_NAME).getString().strip();
        }
        return new DroneIdentity(frame.productName(), frame.callsign(), nickname);
    }
}
