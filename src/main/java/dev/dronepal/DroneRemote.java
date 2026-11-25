package dev.dronepal;

import dev.dronepal.mill.DroneWork;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Each command is authorised against the owner's current controller again at execution. */
public final class DroneRemote {
    /** Sector order is the wheel order: the first nine values are the nine sectors. */
    public enum Action { FOLLOW, WEAPON, MODE, SCAN, MEDIC, CARRY, LIGHT, LINK, WORK, RECALL, DELIVER, SUPPLY, FIREMODE }
    /** The wheel now has the work sector on top of the original eight. */
    public static final int SECTORS = 9;
    static final UUID EMPTY = new UUID(0, 0);
    private static final Map<ServerPlayer,Long> requests = new WeakHashMap<>(), commands = new WeakHashMap<>();
    /** All spoken lines live behind the chip: no chip, a silent chip, or the switch off means silence. */
    public static boolean barksEnabled(ItemStack controller) {
        return controller != null && !controller.isEmpty() && Hardware.hasChip(controller)
                && !disabled(controller, "barks");
    }
    /** The personality switch itself, independent of whether a chip is fitted. */
    static boolean barksOn(ItemStack controller) {
        return controller != null && !controller.isEmpty() && !disabled(controller, "barks");
    }
    /** Flips only {@code dronepal_off_barks}; module and link switches keep their own keys. */
    static void toggleBarks(ItemStack controller) {
        if (controller != null && !controller.isEmpty()) toggle(controller, "barks");
    }
    public static boolean disabled(ItemStack s, String family) {
        return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean("dronepal_off_" + family);
    }
    private static <P extends CustomPacketPayload> CustomPacketPayload.Type<P> type(String id) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Dronepal.ID, id));
    }
    public record Query(int nonce, int revision) implements CustomPacketPayload {
        static final Type<Query> TYPE = DroneRemote.type("wheel_query");
        public Type<Query> type() { return TYPE; }
    }
    public record Status(int nonce, int revision, UUID serial, boolean signal, int enabled, int off, int solar, List<String> labels) implements CustomPacketPayload {
        static final Type<Status> TYPE = DroneRemote.type("wheel_status");
        public Type<Status> type() { return TYPE; }
    }
    public record Command(UUID serial, Action action, int nonce, int revision) implements CustomPacketPayload {
        public Command(UUID serial, Action action) { this(serial, action, 0, 0); }
        static final Type<Command> TYPE = DroneRemote.type("wheel_action");
        public Type<Command> type() { return TYPE; }
    }
    public static java.util.function.Consumer<Status> receive = state -> {};
    private record Cursor(int nonce, int revision) {}
    private static final Map<ServerPlayer, Cursor> cursors = new WeakHashMap<>();

    static void register(RegisterPayloadHandlersEvent event) {
        var net = event.registrar("2");
        net.playToServer(Query.TYPE, StreamCodec.of((b,q) -> { b.writeInt(q.nonce); b.writeInt(q.revision); },
                b -> new Query(b.readInt(), b.readInt())), (q,ctx) -> {
            ServerPlayer p = (ServerPlayer)ctx.player();
            Status answer = query(p,q);
            if (answer != null) ctx.reply(answer);
        });
        net.playToClient(Status.TYPE, StreamCodec.of(DroneRemote::writeStatus, DroneRemote::readStatus), (s,ctx) -> receive.accept(s));
        net.playToServer(Command.TYPE, StreamCodec.of((b,c) -> {
            b.writeUUID(c.serial); b.writeEnum(c.action); b.writeInt(c.nonce); b.writeInt(c.revision);
        }, b -> new Command(b.readUUID(), b.readEnum(Action.class), b.readInt(), b.readInt())), (c,ctx) -> {
            ServerPlayer p = (ServerPlayer)ctx.player();
            Status answer = command(p,c);
            if (answer != null) ctx.reply(answer);
        });
    }
    static Status query(ServerPlayer p, Query q) {
        if (!allow(requests, p) || q.revision <= 0) return null;
        var previous = cursors.get(p);
        if (previous != null && (q.nonce < previous.nonce || q.nonce == previous.nonce && q.revision <= previous.revision)) return null;
        cursors.put(p, new Cursor(q.nonce, q.revision));
        return status(p, q.nonce, q.revision);
    }
    static Status command(ServerPlayer p, Command c) {
        var cursor = cursors.get(p);
        if (cursor == null || cursor.nonce != c.nonce || c.revision <= cursor.revision) return null;
        cursors.put(p, new Cursor(c.nonce,c.revision));
        if (!allow(commands, p)) return null;
        execute(p,c);
        return status(p,c.nonce,c.revision);
    }
    private static void writeStatus(RegistryFriendlyByteBuf b, Status s) {
        b.writeInt(s.nonce); b.writeInt(s.revision); b.writeUUID(s.serial); b.writeBoolean(s.signal); b.writeInt(s.enabled); b.writeInt(s.off); b.writeInt(s.solar);
        for (String label : s.labels) b.writeUtf(label,128);
    }
    private static Status readStatus(RegistryFriendlyByteBuf b) {
        int nonce=b.readInt(), revision=b.readInt(); UUID serial=b.readUUID(); boolean signal=b.readBoolean();
        int enabled=b.readInt(), off=b.readInt(), solar=b.readInt();
        List<String> labels = new ArrayList<>();
        for(int i=0;i<SECTORS;i++) labels.add(b.readUtf(128));
        return new Status(nonce,revision,serial,signal,enabled,off,solar,List.copyOf(labels));
    }
    private static boolean allow(Map<ServerPlayer,Long> map, ServerPlayer p) {
        long now = p.serverLevel().getGameTime();
        if (map.getOrDefault(p, -1L) > now) return false;
        map.put(p,now+3); return true;
    }
    private static ItemStack controller(ServerPlayer p, UUID id) {
        if (!p.isAlive() || p.isSpectator() || !p.getPersistentData().hasUUID(DroneItem.ACTIVE) || !id.equals(p.getPersistentData().getUUID(DroneItem.ACTIVE))) return ItemStack.EMPTY;
        var equipped = CuriosDrones.equipped(p);
        return id.equals(DroneItem.serial(equipped)) ? equipped : ItemStack.EMPTY;
    }
    private static DroneEntity entity(ServerPlayer p, UUID id) {
        return p.serverLevel().getEntity(id) instanceof DroneEntity d && d.isAlive() && p.getUUID().equals(d.owner) ? d : null;
    }
    static Status status(ServerPlayer p, int nonce) { return status(p,nonce,0); }
    private static Status status(ServerPlayer p, int nonce, int revision) {
        UUID id=p.getPersistentData().hasUUID(DroneItem.ACTIVE) ? p.getPersistentData().getUUID(DroneItem.ACTIVE) : EMPTY;
        ItemStack stack=controller(p,id);
        var labels=new ArrayList<>(Collections.nCopies(SECTORS,""));
        boolean signal = !stack.isEmpty() && entity(p,id) != null;
        int enabled=0, off=0, solar=-1;
        if (!stack.isEmpty()) {
            enabled=1; labels.set(0,"dronepal.wheel.follow");
            // The work sector is offered whenever a controller exists: the drone itself refuses
            // without a scan module, which is the moment the player needs to hear about it.
            enabled |= 1<<8; labels.set(8,"dronepal.wheel.work");
            if (disabled(stack,"follow")) off |= 1;
            for(var m:Hardware.installed(stack)) {
                int slot=switch(m.family()) { case "combat" -> 1; case "scan" -> 3; case "medic" -> 4; case "carry" -> 5; case "light" -> 6; case "link" -> 7; default -> -1; };
                if(slot>=0) {
                    enabled |= 1<<slot; labels.set(slot,m.wheelLabel());
                    if(disabled(stack,m.family())) off |= 1<<slot;
                    if (slot == 3) {
                        String scanState = disabled(stack,"scan") ? "scan_off" : Hardware.powered(stack,"scan") ? "scan_on" : "scan_standby";
                        labels.set(slot, "dronepal.wheel." + scanState);
                    }
                    if(slot==1) { labels.set(1, DroneCombat.automatic(stack) ? "dronepal.wheel.weapon_auto" : "dronepal.wheel.weapon_safe"); enabled |= 4; labels.set(2,"dronepal.engage." + Engagement.mode(stack)); }
                } else if(m.family().equals("solar")) {
                    var drone=entity(p,id);
                    solar=drone==null || !p.serverLevel().canSeeSky(drone.blockPosition()) ? 0 : m.integer(!p.serverLevel().isDay()?"night":p.serverLevel().isRaining()?"rain":"clear");
                }
            }
        }
        return new Status(nonce,revision,id,signal,enabled,off,solar,labels);
    }
    static boolean execute(ServerPlayer p, Command command) {
        ItemStack stack=controller(p,command.serial);
        if(stack.isEmpty()) return false;
        DroneEntity d=entity(p,command.serial);
        if(command.action==Action.RECALL) return rescue(p,stack,command.serial,d);
        if(d==null) return feedback(p,"signal");
        boolean ok=switch(command.action) {
            case FOLLOW -> {
                toggle(stack,"follow");
                d.holdingStation = disabled(stack,"follow");
                if (d.holdingStation) DroneWork.stop(d);
                yield true;
            }
            case WORK -> Hardware.execute(p,"order.work");
            case WEAPON -> DroneCombat.toggle(d);
            case MODE -> Engagement.cycle(d);
            case SCAN -> {
                if (Hardware.tier(stack, "scan") == 0) yield false;
                toggle(stack, "scan");
                d.scanPatrolChanged(p, !disabled(stack, "scan"));
                yield true;
            }
            case FIREMODE -> DroneCombat.cycleMode(d);
            case DELIVER -> Hardware.execute(p,"order.fetch");
            case SUPPLY -> Hardware.execute(p,"order.supply");
            case MEDIC, CARRY, LIGHT, LINK -> {
                String family=command.action.name().toLowerCase(Locale.ROOT);
                yield Hardware.tier(stack,family)>0 && (Hardware.online(stack,family) || (disabled(stack,family) && Hardware.energy(stack)>0)) && toggle(stack,family);
            }
            default -> false;
        };
        if(!ok) feedback(p,"unavailable");
        else p.displayClientMessage(Component.translatable("dronepal.wheel.done"), true);
        return ok;
    }
    static void enable(ItemStack stack,String family) {
        if (disabled(stack,family)) toggle(stack,family);
    }
    private static boolean toggle(ItemStack stack,String family) {
        String key="dronepal_off_"+family;
        CustomData.update(DataComponents.CUSTOM_DATA,stack,t -> t.putBoolean(key,!t.getBoolean(key)));
        return true;
    }
    private static boolean rescue(ServerPlayer p, ItemStack stack, UUID id, DroneEntity d) {
        long now=p.serverLevel().getServer().overworld().getGameTime();
        long wait=stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLong("dronepal_recall")-now;
        if(wait>0) { p.displayClientMessage(Component.translatable("dronepal.wheel.cooldown",(wait+19)/20),true); return false; }
        if(d==null) {
            if(!DroneItem.deploy(p,stack,id)) return feedback(p,"repair");
            d=entity(p,id);
        }
        if(d==null) return false;
        DroneWork.stop(d);
        d.holdingStation = false;
        d.setTarget(null); d.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); d.setPos(DroneEntity.station(p));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,t -> { t.putLong("dronepal_recall",now+200); t.remove("dronepal_off_follow"); });
        p.displayClientMessage(Component.translatable("dronepal.wheel.rescued"),true);
        return true;
    }
    private static boolean feedback(ServerPlayer p,String key) { p.displayClientMessage(Component.translatable("dronepal.wheel."+key),true); return false; }
}
