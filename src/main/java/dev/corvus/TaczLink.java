package dev.corvus;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunReloadData;
import com.tacz.guns.resource.pojo.data.gun.GunReloadTime;
import com.tacz.guns.sound.SoundManager;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;

/**
 * TaCZ 1.1.8+ integration for 1.20.1: slot predicates, the operator calls, and the link module's
 * ammunition / hit telemetry.
 *
 * <p><b>API level.</b> Everything below is present in TaCZ <b>1.1.8</b>; nothing here needs the
 * 1.2.0-only additions. The one structural difference from the 1.2.0 reference
 * ({@code ../dronepal/TaczCombat.java}) is that 1.1.8 exposes the same operator through an
 * {@code IGunOperator} mixin applied to {@code LivingEntity}, so a {@link DroneEntity} is a legal
 * shooter without any extra registration. A gun's magazine is decremented by TaCZ itself; the
 * reserve comes from the entity's Forge item-handler capability, which {@link DroneCombat} attaches
 * and mirrors into the controller's parts list.</p>
 *
 * <p><b>Optional dependency.</b> TaCZ is a runtime {@code manual=false} dependency: this class is
 * only ever called through {@link CombatSlots}, which short-circuits to "no gun" when the mod is not
 * loaded, so the JVM never resolves a TaCZ type in that case.</p>
 */
public final class TaczLink {
    /** Reserve rounds at or below this trigger the low-ammo line (roughly one magazine). */
    private static final int LOW_RESERVE = 6;
    /** Gun-pack weight is in "weight points"; the frame budget is in the same unit used by the 1.21 build. */
    private static final double WEIGHT_PER_UNIT = 6.0D;
    /** 换弹锁定 length when the gun pack carries no reload data at all: 30 tick ≈ 1.5 s. */
    static final int DEFAULT_RELOAD_TICKS = 30;
    /** 换弹音效 reach in blocks (the gun packs' own reload audio is short-ranged as well). */
    private static final int SOUND_RADIUS = 24;

    private record Sample(ResourceLocation gun, int loaded, int reserve) {}

    /** Test hooks: rounds actually fired and hit/kill lines delivered (the dev smoke asserts these). */
    static int shots;
    static int reports;
    /** Test hooks: 装填 locks armed, and the feedback sound — total, plus which flavour was used. */
    static int reloads;
    static int reloadSounds;
    static int taczReloadSounds;
    static int vanillaReloadSounds;

    private static final Map<ServerPlayer, Sample> previous = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> nextHit = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> nextAmmoCry = new WeakHashMap<>();

    // ---------------------------------------------------------------------------------------------
    // Slot predicates (called from CombatSlots)
    // ---------------------------------------------------------------------------------------------

    static boolean isGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // Dummy-ammo guns (the ones TaCZ fills from a creative feed) are not real magazine weapons.
        return stack.getItem() instanceof AbstractGunItem gun && !gun.useDummyAmmo(stack);
    }

    static boolean isAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof IAmmo || stack.getItem() instanceof IAmmoBox;
    }

    /** Gun-pack {@code type} ("rifle", "smg", ...) — the frame allow-list is written against it. */
    static String gunType(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return "";
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(CommonGunIndex::getType)
                .orElse("");
    }

    /** Gun-pack weight converted to normalized frame units; infinite means "cannot be judged". */
    static double mountLoad(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return Double.POSITIVE_INFINITY;
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> Math.max(0D, index.getGunData().getWeight()) / WEIGHT_PER_UNIT)
                .orElse(Double.POSITIVE_INFINITY);
    }

    static String gunId(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return "";
        ResourceLocation id = gun.getGunId(stack);
        return id == null ? "" : id.toString();
    }

    /**
     * Server-side fire sound id — always null in 1.1.8.
     *
     * <p>The fire sound is not a server-side resource in this version: TaCZ only ships
     * {@code GunData.fire_sound} as two volume multipliers and resolves the actual sound event on the
     * client from the gun display index ({@code SoundManager.SHOOT_SOUND} + the display asset). TaCZ
     * already pushes that to every nearby client when the drone fires, so the port needs no separate
     * sound path; the method stays for callers that probe it and answers "unknown" honestly.</p>
     *
     * <p>The reload sound needs our own dispatch (TaCZ only plays it from a gun pack's Lua on a human
     * reload) but it works the same name-based way — see {@link #reloadSound}.</p>
     */
    static ResourceLocation soundId(ItemStack stack) {
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Operator calls (the fire path)
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers the gun into the shooter's operator state. TaCZ's {@code draw} is what makes
     * {@code shoot} legal at all: until it runs, {@code currentGunItem} is null and every shot
     * answers {@code NOT_DRAW}. The draw cooldown that follows is the gun's own {@code draw_time}.
     */
    static void draw(DroneEntity drone) {
        IGunOperator operator = IGunOperator.fromLivingEntity(drone);
        operator.draw(drone::getMainHandItem);
    }

    /**
     * Fires one round through TaCZ's own pipeline.
     *
     * <p>The two-argument overload is used on purpose: it derives the timestamp from the shooter's
     * own {@code ShooterDataHolder.baseTimestamp}, which is exactly what TaCZ's server-side network
     * check compares against. Passing a hand-rolled timestamp would answer {@code NETWORK_FAIL} for
     * any non-player shooter.</p>
     *
     * @return true only when TaCZ reports {@code SUCCESS}, i.e. a bullet really left the barrel
     */
    @SuppressWarnings("UnnecessaryDefaultInEnumSwitch")
    static boolean shoot(DroneEntity drone, float pitch, float yaw) {
        IGunOperator operator = IGunOperator.fromLivingEntity(drone);
        ShootResult result = operator.shoot(() -> pitch, () -> yaw);
        drone.combat.lastResult = result.name();
        switch (result) {
            case SUCCESS -> {
                shots++;
                return true;
            }
            // NO_AMMO is answered one layer up ({@link DroneCombat#fire}): the drone performs the
            // 装填 itself and then holds the trigger through a reload lock. TaCZ's own {@code reload}
            // is deliberately NOT started — it exists to drive a humanoid reload animation, which a
            // drone must not play; the magazine transaction is local and instantaneous.
            case NO_AMMO -> {
            }
            case NEED_BOLT -> operator.bolt();
            case NOT_DRAW -> draw(drone);
            default -> {
            }
        }
        return false;
    }

    /**
     * 装填: moves rounds out of the drone's own reserve into the gun's magazine.
     *
     * <p>TaCZ 1.1.8 normally refills a magazine from the shooter's {@code ITEM_HANDLER} capability, but
     * a Forge 1.20.1 {@code LivingEntity} answers that capability with its equipment handler and never
     * consults an attached provider, so the drone carries the ammunition itself and hands it over here.
     * The capacity is the gun-pack's base {@code ammo_amount}; extended-magazine attachments are not
     * simulated yet (TODO, needs TaCZ's attachment cache which 1.1.8 keeps operator-internal).</p>
     *
     * <p>A successful feed is not free any more: the magazine taking rounds arms the 装填 lock
     * ({@link DroneCombat#beginReload}, length {@link #reloadTicks}) and plays the reload sound, so
     * the drone cannot answer {@code NO_AMMO} with an instant resupply that looks like infinite ammo.
     * </p>
     *
     * @param reserve mutable working copies of the controller's ammo parts, in slot order
     * @return how many rounds actually went in
     */
    static int feed(DroneEntity drone, ItemStack[] reserve) {
        ItemStack gunStack = drone.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null || reserve == null || reserve.length == 0) return 0;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(gunStack)).orElse(null);
        if (index == null) return 0;
        int capacity = Math.max(0, index.getGunData().getAmmoAmount());
        int current = gun.getCurrentAmmoCount(gunStack);
        if (capacity <= 0 || current >= capacity) return 0;
        int need = capacity - current;
        int moved = 0;
        for (ItemStack part : reserve) {
            if (moved >= need) break;
            if (part == null || part.isEmpty() || !(part.getItem() instanceof IAmmo ammo)) continue;
            if (!ammo.isAmmoOfGun(gunStack, part)) continue;
            int take = Math.min(need - moved, part.getCount());
            if (take <= 0) continue;
            part.shrink(take);
            moved += take;
        }
        if (moved > 0) {
            gun.setCurrentAmmoCount(gunStack, current + moved);
            gun.setBulletInBarrel(gunStack, true);
            // 换弹锁定 + 云台反馈: the trigger is dead until the gun says the reload is done, and the
            // gimbal takes the recoil of a magazine going in (DroneCombat applies it to aimPitch).
            drone.combat.beginReload(drone, reloadTicks(gunStack));
            reloadSound(drone, gunStack);
            reloads++;
        }
        return moved;
    }

    /**
     * 换弹时长 in ticks, read straight out of the gun pack.
     *
     * <p>TaCZ's own reload state machine splits a reload into a feed phase and a finishing phase and
     * only calls it done at {@code reload.cooldown.empty} seconds — verified against
     * {@code ModernKineticGunItem#defaultTickReload}: {@code elapsed < feed → *FEEDING},
     * {@code elapsed < cooldown → *FINISHING, else NOT_RELOADING}. The drone only ever reloads from an
     * empty magazine (that IS the {@code NO_AMMO} state this feed answers), so the empty column is the
     * right one — for the stock {@code tacz:m4a1} that is 2.2 s = 44 tick. Guns whose pack carries no
     * reload data fall back to {@link #DEFAULT_RELOAD_TICKS}.</p>
     */
    static int reloadTicks(ItemStack gunStack) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) return DEFAULT_RELOAD_TICKS;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(gunStack)).orElse(null);
        if (index == null) return DEFAULT_RELOAD_TICKS;
        GunReloadData reload = index.getGunData().getReloadData();
        if (reload == null) return DEFAULT_RELOAD_TICKS;
        GunReloadTime cooldown = reload.getCooldown();
        if (cooldown == null) return DEFAULT_RELOAD_TICKS;
        float seconds = cooldown.getEmptyTime();
        if (seconds <= 0F) return DEFAULT_RELOAD_TICKS;
        // 4 tick floor: a lock shorter than that is not a reload, it is a stutter.
        return Mth.clamp(Math.round(seconds * 20F), 4, 200);
    }

    /**
     * 装填音效: the clack of a magazine, audible to every player around the drone.
     *
     * <p><b>First choice is the gun pack's own reload audio.</b> TaCZ 1.1.8 exposes no server-side
     * <em>sound event</em> for a reload: {@code GunData} carries only volume multipliers, and the real
     * asset id lives in the gun's <em>display</em> json ({@code "sounds": {"reload_empty": ...}}),
     * which is client-side data — {@code TimelessAPI.getGunDisplay} answers empty on a dedicated
     * server. What the server can do is exactly what a gun pack's own Lua does for a human reload:
     * {@link SoundManager#sendSoundToNearby} ships the gun id, the display id and the sound
     * <em>name</em> ({@code "reload_empty"}) to nearby clients, and every client resolves that name
     * out of its own display index. No sound id has to be fetched here, skins included.</p>
     *
     * <p><b>Fallback.</b> If no gun or display id resolves (or the dispatch throws), the vanilla
     * mechanical layer plays through {@code level.playSound(null, pos, ...)} so the reload is still
     * audible: {@code ARMOR_EQUIP_IRON} — an iron clack that reads as a magazine seating, chosen over
     * {@code BLOCK_PISTON_CONTRACT} because the piston line is slow and airy while the armour equip is
     * a short, hard, metallic "click-clack" at exactly the right length.</p>
     */
    static void reloadSound(DroneEntity drone, ItemStack gunStack) {
        reloadSounds++;
        IGun gun = IGun.getIGunOrNull(gunStack);
        ResourceLocation gunId = gun == null ? null : gun.getGunId(gunStack);
        ResourceLocation displayId = gun == null ? null : gun.getGunDisplayId(gunStack);
        if (gunId != null && displayId != null) {
            try {
                SoundManager.sendSoundToNearby(drone, SOUND_RADIUS, gunId, displayId,
                        SoundManager.RELOAD_EMPTY_SOUND, 1.0F, 1.0F);
                taczReloadSounds++;
                return;
            } catch (Throwable ignored) {
                // The TaCZ dispatch is unavailable on this runtime (or the channel is not up):
                // fall through to the vanilla layer rather than reloading in silence.
            }
        }
        Level level = drone.level();
        if (level != null && !level.isClientSide) {
            level.playSound(null, drone.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON,
                    SoundSource.PLAYERS, 0.9F, 1.15F);
            vanillaReloadSounds++;
        }
    }

    /** Why the last attempt produced no round; the combat tick records this. */
    static String lastResult(DroneEntity drone) {
        return drone.combat.lastResult;
    }

    // ---------------------------------------------------------------------------------------------
    // Link module: ammunition / hit telemetry, reserve delivery
    // ---------------------------------------------------------------------------------------------

    /** Registers the gun events. Called from {@link DroneCombat#register()} when TaCZ is present. */
    static void register() {
        // Hit / kill reports. 1.1.8 posts both on the Forge bus with the same accessors as 1.2.0.
        MinecraftForge.EVENT_BUS.addListener((EntityHurtByGunEvent.Post event) -> {
            if (event.getLogicalSide().isServer()
                    && event.getHurtEntity() instanceof LivingEntity target && target.isAlive()) {
                hit(event.getAttacker(), target, false);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((EntityKillByGunEvent event) -> {
            if (event.getLogicalSide().isServer()) hit(event.getAttacker(), event.getKilledEntity(), true);
        });

        // Friendly fire and the combat module's damage multiplier. 1.1.8 marks Pre @Cancelable, so a
        // refused target is dropped before TaCZ applies any damage.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (EntityHurtByGunEvent.Pre event) -> {
            if (event.getLogicalSide().isClient() || !(event.getAttacker() instanceof DroneEntity drone)) return;
            if (!(event.getHurtEntity() instanceof LivingEntity target) || !DroneCombat.validTarget(drone, target)) {
                event.setCanceled(true);
                return;
            }
            Hardware.installed(drone.controller()).stream()
                    .filter(module -> module.family().equals("combat")).findFirst()
                    .ifPresent(module -> event.setBaseAmount(
                            event.getBaseAmount() * Mth.clamp(module.decimal("damage_mult"), 0F, 4F)));
        });

        // Drone rounds must not carve up the landscape: TaCZ hands the block hit to us before applying
        // it, so the bullet is simply dropped. Same rule as the 1.21 build.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (AmmoHitBlockEvent event) -> {
            if (event.getAmmo() != null && event.getAmmo().getOwner() instanceof DroneEntity) {
                event.getAmmo().discard();
                event.setCanceled(true);
            }
        });
        // ... and a drone's explosive round must not reshape terrain either.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ExplosionEvent.Start event) -> {
            Entity source = event.getExplosion().getDirectSourceEntity();
            if (source instanceof Projectile projectile && projectile.getOwner() instanceof DroneEntity) {
                event.setCanceled(true);
            }
        });
    }

    /** The link module that currently drives the reports, or null when reporting is off. */
    private static Hardware.Module active(ServerPlayer player, ItemStack terminal) {
        if (terminal.isEmpty() || !player.isAlive() || player.isSpectator()) return null;
        if (!Hardware.online(terminal, "link")) return null;
        // The wheel's link switch silences reports and delivery together; otherwise it would be a dead toggle.
        if (DroneRemote.disabled(terminal, "link")) return null;
        return Hardware.installed(terminal).stream()
                .filter(module -> module.family().equals("link")).findFirst().orElse(null);
    }

    private static boolean permits(Hardware.Module module, String key) {
        return module != null && module.params().has(key) && module.params().get(key).getAsBoolean();
    }

    /**
     * 递弹判据: true when {@code part} is ammunition that feeds {@code gun} — a loose round or a box
     * with rounds left. This is the predicate both the reserve feed and the cargo delivery use.
     */
    static boolean isAmmoFor(ItemStack gun, ItemStack part) {
        return compatible(gun, part);
    }

    /** True when {@code part} feeds {@code gun}: a loose round or a box with rounds left. */
    private static boolean compatible(ItemStack gun, ItemStack part) {
        if (part.isEmpty()) return false;
        if (part.getItem() instanceof IAmmo loose) return loose.isAmmoOfGun(gun, part);
        return part.getItem() instanceof IAmmoBox box && box.getAmmoCount(part) > 0 && box.isAmmoBoxOfGun(gun, part);
    }

    /**
     * 递弹: moves one matching stack from the cargo hold into the owner's inventory.
     *
     * <p>Gated by the link module's {@code deliver_mags}; the matcher asks TaCZ whether the stack is
     * ammunition for the gun actually in the owner's hand.</p>
     */
    static boolean supply(ServerPlayer player) {
        ItemStack controller = Hardware.controller(player);
        if (!permits(active(player, controller), "deliver_mags")) return false;
        ItemStack gun = player.getMainHandItem();
        if (!isGun(gun)) return false;
        int moved = CarryLight.deliverMatching(player, controller, stack -> compatible(gun, stack));
        if (moved > 0) player.displayClientMessage(Component.translatable("dronepal.link.supplied", moved), true);
        return moved > 0;
    }

    /**
     * Per-second telemetry for the worn controller: ammunition state and the automatic delivery.
     *
     * <p>Called from {@link DroneCombat} while the drone flies (1.20.1 has no
     * {@code DroneSystems.second} hook for the gun layer). Every quantity comes from real stacks —
     * the magazine out of the gun's own NBT, the reserve out of the owner's inventory.</p>
     */
    static void second(ServerPlayer player, ItemStack controller) {
        Hardware.Module module = active(player, controller);
        ItemStack held = player.getMainHandItem();
        if (!permits(module, "report_ammo") || !isGun(held)) {
            previous.remove(player);
            return;
        }
        IGun gun = IGun.getIGunOrNull(held);
        if (gun == null) {
            previous.remove(player);
            return;
        }
        int reserve = 0;
        for (ItemStack item : player.getInventory().items) {
            if (!compatible(held, item)) continue;
            reserve += item.getItem() instanceof IAmmoBox box ? box.getAmmoCount(item) : item.getCount();
        }
        Sample sample = new Sample(gun.getGunId(held),
                gun.getCurrentAmmoCount(held) + (gun.hasBulletInBarrel(held) ? 1 : 0), reserve);
        Sample last = previous.put(player, sample);
        boolean armed = DroneRemote.barksEnabled(controller);
        long now = player.serverLevel().getGameTime();
        // Ammo is not narrated on every tick: only a finished reload or a reserve running low gets a
        // line, and that line still obeys the chip's master switch.
        if (sample.loaded == 0 && reserve == 0) {
            if (armed && (last == null || !sample.equals(last))) DialogueServer.signal(player, "no_ammo");
        } else if (armed && last != null && sample.loaded > last.loaded && sample.reserve < last.reserve) {
            if (now >= nextAmmoCry.getOrDefault(player, 0L) && player.getRandom().nextFloat() < 0.75F) {
                nextAmmoCry.put(player, now + 200);
                DialogueServer.signal(player, "reload_done");
            }
        } else if (armed && reserve > 0 && reserve <= LOW_RESERVE) {
            if (now >= nextAmmoCry.getOrDefault(player, 0L) && player.getRandom().nextFloat() < 0.6F) {
                nextAmmoCry.put(player, now + 400);
                DialogueServer.signal(player, "low_ammo");
            }
        }
        if (sample.loaded == 0 && reserve == 0 && permits(module, "auto_deliver")) supply(player);
    }

    /**
     * 命中播报: resolves the owner of the shooter and prints the hit/kill line.
     *
     * <p>1.20.1 has no {@code NeoForge.EVENT_BUS}; both events are ordinary Forge bus events, and the
     * owner lookup goes through {@code PlayerList} because {@link DroneEntity} keeps only the UUID.</p>
     */
    private static void hit(LivingEntity attacker, LivingEntity target, boolean kill) {
        if (target == null) return;
        ServerPlayer owner = attacker instanceof ServerPlayer player ? player
                : attacker instanceof DroneEntity drone && drone.ownerId() != null && drone.getServer() != null
                ? drone.getServer().getPlayerList().getPlayer(drone.ownerId()) : null;
        if (owner == null) return;
        ItemStack terminal = Hardware.controller(owner);
        if (attacker instanceof DroneEntity drone && !ItemStack.matches(drone.controller(), terminal)) return;
        Hardware.Module module = active(owner, terminal);
        if (!permits(module, "report_hits")) return;
        long now = owner.serverLevel().getGameTime();
        if (!kill && now < nextHit.getOrDefault(owner, 0L)) return;
        nextHit.put(owner, now + 10);
        Component report = Component.translatable(kill ? "dronepal.link.kill" : "dronepal.link.hit",
                target.getDisplayName());
        if (!kill && permits(module, "armor_hint") && target.getArmorValue() > 0) {
            report = report.copy().append(Component.translatable("dronepal.link.armor", target.getArmorValue()));
        }
        owner.displayClientMessage(report, true);
        reports++;
    }

    private TaczLink() {}
}
