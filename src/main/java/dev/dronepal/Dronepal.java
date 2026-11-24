package dev.dronepal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import dev.dronepal.charging.ChargingDockRegistry;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Dronepal.ID)
public final class Dronepal {
    public static final String ID = "huhu_dronepal";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, ID);
    public static final DeferredItem<DroneItem> DRONE = ITEMS.register("drone", () -> new DroneItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> ENTITY = ENTITIES.register("drone", () ->
        EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC).sized(0.7F, 0.7F)
            .clientTrackingRange(8).updateInterval(1).noSave().noSummon().build(ID + ":drone"));

    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, ID);
    static final DeferredHolder<CreativeModeTab, CreativeModeTab> CATALOG = TABS.register("drones", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.dronepal.drones"))
            .icon(() -> Frames.create("peregrine"))
            .displayItems((parameters, output) -> {
                Frames.IDS.forEach(id -> output.accept(Frames.create(id)));
                Hardware.creative(output);
                PaintCans.addToTab(output);
                output.accept(ChargingDockRegistry.DOCK.get());
                SurvivalWorks.addToTab(output);
            }).build());

    /**
     * The Patchouli guide book, resolved by registry id so the mod never links against Patchouli.
     * Patchouli itself inserts this stack into our tab (book.json's creative_tab), so we must not
     * add it again: a duplicate entry makes NeoForge throw while building the tab.
     * Returns an empty stack when Patchouli is absent or its data component is not registered.
     */
    @SuppressWarnings("unchecked")
    public static net.minecraft.world.item.ItemStack manualStack() {
        if (!net.neoforged.fml.ModList.get().isLoaded("patchouli")) return net.minecraft.world.item.ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"));
        var component = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("patchouli", "book"));
        if (item.isEmpty() || component.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        var stack = new net.minecraft.world.item.ItemStack(item.get());
        stack.set((net.minecraft.core.component.DataComponentType<net.minecraft.resources.ResourceLocation>) component.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ID, "corvus_manual"));
        return stack;
    }

    public Dronepal(IEventBus bus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, HudOptions.SPEC);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ServerOptions.SPEC, "dronepal-server.toml");
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(DroneRemote::register);
        bus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                event.put(ENTITY.get(), net.minecraft.world.entity.Mob.createMobAttributes().build()));
        Frames.register(bus);
        Hardware.register(bus);
        PaintCans.register(bus);
        SurvivalWorks.register(bus);
        DroneCombat.register(bus);
        TerminalMenus.register(bus);
        dev.dronepal.charging.ChargingDockRegistry.register(bus);
        TABS.register(bus);
    }
}
