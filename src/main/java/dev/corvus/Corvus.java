package dev.corvus;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Corvus for 1.20.1 / Forge 47.
 *
 * <p>Registration is Forge-style and persistence is NBT: 1.20.1 has no data components, so nothing
 * here (or anywhere else in this source set) may use {@code net.minecraft.core.component}.</p>
 *
 * <p>This class is the single merge point for the parallel port: the data/hardware layer (frames,
 * modules, batteries, paint cans, loot) registers here, and the UI/block layer and the combat layer add
 * their own registration lines at the marked merge points below.</p>
 */
@Mod(Corvus.ID)
public final class Corvus {
    public static final String ID = "huhu_dronepal";
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public static final RegistryObject<Item> DRONE =
            ITEMS.register("drone", () -> new DroneItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<EntityType<DroneEntity>> ENTITY = ENTITIES.register("drone", () ->
            EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                    .sized(0.7F, 0.7F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .noSave()
                    .build(ID + ":drone"));

    /**
     * The 随行无人机 creative page.
     *
     * <p>78 entries in total, matching the 1.21 build: 4 frames, 27 modules, 3 chips, 10 batteries,
     * 9 spray cans (this class and {@link Hardware}/{@link PaintCans}), plus the assembly bench, its 23
     * parts and the charging dock contributed by the UI/block layer at the merge point below.</p>
     */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("drones", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.dronepal.drones"))
            .icon(() -> Frames.create("peregrine"))
            .displayItems((parameters, output) -> {
                Frames.IDS.forEach(id -> output.accept(Frames.create(id)));
                Hardware.addToTab(output);
                PaintCans.addToTab(output);
                // --- B（界面与方块）: 装配台 + 23 个零件 + 充电座 ---
                SurvivalWorks.addToTab(output);
                dev.corvus.charging.ChargingDockRegistry.addToTab(output);
            })
            .build());

    public Corvus() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        // Config file names deliberately match the 1.21 build so existing notes and packs still apply.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, HudOptions.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerOptions.SPEC, "dronepal-server.toml");

        ITEMS.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
        bus.addListener(Corvus::attributes);

        Frames.register();
        Hardware.register(bus);
        PaintCans.register(bus);
        LootSerializers.register(bus);

        // --- B（界面与方块）: 终端/货舱菜单、装配台、充电座。三个 register 都是幂等的。 ---
        TerminalMenus.register(bus);
        SurvivalWorks.register(bus);
        dev.corvus.charging.ChargingDockRegistry.register(bus);

        // --- C（战斗/扫描/对话）: SimpleChannel + 6 个包 + Hardware 注入点 + engage_modes reload。
        // CorvusNetwork.register() 内部同时调用 DroneCombat.register()，只调一次即可。 ---
        CorvusNetwork.register();
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(ENTITY.get(), Mob.createMobAttributes().build());
    }
}
