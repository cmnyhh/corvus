package dev.corvus;

import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers the 1.20.1 replacement for {@code forge:add_table}.
 *
 * <p>Global loot modifiers are resolved by codec id from
 * {@code data/forge/loot_modifiers/global_loot_modifiers.json}, so the serializer has to be in the
 * registry before the serverside datapack loads.</p>
 */
public final class LootSerializers {
    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Corvus.ID);

    /** {@code huhu_dronepal:supply}. */
    public static final RegistryObject<Codec<? extends IGlobalLootModifier>> SUPPLY =
            SERIALIZERS.register("supply", () -> LootSupplyModifier.CODEC);

    private LootSerializers() {}

    static void register(IEventBus bus) {
        SERIALIZERS.register(bus);
    }
}
