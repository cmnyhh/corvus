package dev.dronepal.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dronepal.DroneRemote;
import dev.dronepal.Dronepal;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=Dronepal.ID,value=Dist.CLIENT)
public final class DroneWheel extends Screen {
    private static final KeyMapping KEY = new KeyMapping("key.dronepal.wheel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.dronepal");
    private static final int RING=0xd526293a, EMPTY=0xa51c1e2c, SELECTED=0xf165438e, WHITE=0xfff5f2ff, MUTED=0xffc3bcd2;
    /**
     * The wheel only appears after the key has been held this long. Measuring before the screen opens
     * matters: {@code Minecraft.setScreen} calls {@code KeyMapping.releaseAll()}, so once a screen is
     * up {@code KEY.isDown()} is false even while the physical V key is still held.
     */
    private static final long HOLD_MILLIS=160;
    /** Dev-only timeline trace: {@code -Ddronepal.wheelLog=1} prints every open/close with a timestamp. */
    private static final boolean DIAG="1".equals(System.getProperty("dronepal.wheelLog"));
    private static String lastSample;
    private static int sequence;
    private static boolean lastDown;
    /** Wall-clock start of the press under measurement, 0 when no press is pending. */
    private static long pressedAt;
    /** This press already opened the wheel or showed the no-drone hint; release must not act again. */
    private static boolean resolved;
    static void diag(String message, Object... args) {
        if(DIAG) System.out.println("[wheel-diag] "+String.format(message,args));
    }
    private final int nonce=++sequence;
    private final long opened=net.minecraft.Util.getMillis();
    private DroneRemote.Status state;
    private int selection=-1;
    private int revision, accepted, pending, refreshTicks;
    private boolean clickedWhileOpen;
    private void query() { PacketDistributor.sendToServer(new DroneRemote.Query(nonce, ++revision)); }
    private void accept(DroneRemote.Status update) {
        if (update.nonce() != nonce || update.revision() <= accepted || update.revision() < pending) return;
        diag("accept nonce=%d rev=%d signal=%s enabled=0x%x", update.nonce(), update.revision(),
                update.signal(), update.enabled());
        state = update;
        accepted = update.revision();
        pending = 0;
    }
    @Override public void tick() {
        if (++refreshTicks >= 10) { refreshTicks = 0; query(); }
    }
    private DroneWheel() { super(Component.translatable("key.dronepal.wheel")); }
    @EventBusSubscriber(modid=Dronepal.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        @SubscribeEvent static void register(RegisterKeyMappingsEvent event) {
            event.register(KEY);
            DroneRemote.receive=s -> { if(Minecraft.getInstance().screen instanceof DroneWheel w) w.accept(s); };
        }
    }
    @SubscribeEvent static void clientTick(ClientTickEvent.Post event) {
        Minecraft game=Minecraft.getInstance();
        boolean down=KEY.isDown();
        long now=net.minecraft.Util.getMillis();
        if(DIAG) {
            String screen = game.screen==null ? "null" : game.screen.getClass().getSimpleName();
            String sample = down+"/"+lastDown+"/"+screen;
            if(!sample.equals(lastSample)) {
                diag("tick at=%d down=%s lastDown=%s screen=%s", now, down, lastDown, screen);
                lastSample=sample;
            }
        }
        if(down && !lastDown && game.player!=null && game.screen==null) {
            // Hold-to-open: only start the stopwatch here, never show the screen yet.
            pressedAt=now;
            resolved=false;
            diag("press at=%d; wheel waits for %d ms", now, HOLD_MILLIS);
        }
        if(down && !resolved && pressedAt!=0 && game.player!=null && game.screen==null
                && now-pressedAt>=HOLD_MILLIS) {
            long held=now-pressedAt;
            boolean noDrone=noDrone(game);
            resolved=true;
            pressedAt=0;
            if(noDrone) {
                hint(game);
            } else {
                diag("open requested at=%d held=%d", now, held);
                DroneWheel w=new DroneWheel();
                game.setScreen(w);
                w.query();
                diag("open done at=%d screen=%s keyDownAfterSetScreen=%s", net.minecraft.Util.getMillis(),
                        game.screen==null?"null":game.screen.getClass().getSimpleName(), KEY.isDown());
            }
        }
        if(!down && pressedAt!=0) {
            // Released before the hold completed: a tap never opens, never acts, and never pretends a
            // command was attempted, so it stays silent even when no controller is fitted.
            diag("tap released at=%d held=%d resolved=%s", now, now-pressedAt, resolved);
            pressedAt=0;
            resolved=false;
        }
        lastDown=down;
    }

    /** True only when the synced Curios slot positively has no controller in it. */
    private static boolean noDrone(Minecraft game) {
        if(game.player==null) return true;
        try {
            var handler=top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(game.player)
                    .flatMap(inventory -> inventory.getStacksHandler("drone"));
            if(handler.isEmpty()) return true;
            var stacks=handler.get().getStacks();
            for(int i=0;i<stacks.getSlots();i++) {
                if(stacks.getStackInSlot(i).is(Dronepal.DRONE.get())) return false;
            }
            return true;
        } catch(RuntimeException | LinkageError unreadable) {
            // Curios did not expose the slot (e.g. a client-side read failure): never swallow the wheel.
            diag("controller read failed: %s", unreadable.toString());
            return false;
        }
    }

    private static void hint(Minecraft game) {
        if(game.player==null) return;
        game.player.displayClientMessage(Component.translatable("dronepal.wheel.no_drone"), true);
        diag("hint shown: no deployed drone");
    }
    @Override protected void init() {
        diag("init nonce=%d age=%d keyDown=%s", nonce, net.minecraft.Util.getMillis()-opened, KEY.isDown());
        var window=minecraft.getWindow();
        GLFW.glfwSetCursorPos(window.getWindow(), window.getScreenWidth()/2.0, window.getScreenHeight()/2.0);
    }
    @Override public void onClose() {
        diag("onClose nonce=%d age=%d selection=%d", nonce, net.minecraft.Util.getMillis()-opened, selection);
        if(DIAG) {
            var stack=new Throwable().getStackTrace();
            for(int i=1;i<Math.min(stack.length,9);i++) System.out.println("[wheel-diag]   at "+stack[i]);
        }
        super.onClose();
    }
    @Override public void removed() {
        diag("removed nonce=%d age=%d", nonce, net.minecraft.Util.getMillis()-opened);
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
    private java.util.List<net.minecraft.util.FormattedCharSequence> footer() {
        return font.split(text("hint"), Math.max(1, width - 20));
    }
    private WheelLayout layout() {
        return WheelLayout.fit(width, height, footer().size(), font.lineHeight + 1);
    }
    private void drawLabel(GuiGraphics graphics, Component message, WheelLayout.Box box, int color) {
        if (font.width(message) == 0) return;
        // Use native glyph size when possible, then wrap and scale the entire block.
        // The selected name also appears at full screen width in the header.
        float scale = 1;
        var lines = font.split(message, box.width());
        for (int step = 1; lines.size() * font.lineHeight * scale > box.height() && step <= 8; step++) {
            scale = 1f - step * .075f;
            lines = font.split(message, Math.max(1, (int)(box.width() / scale)));
        }
        scale = Math.min(scale, (float)box.height() / (lines.size() * font.lineHeight));
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(box.x(), box.y(), 0);
        pose.scale(scale, scale, 1);
        int y = -lines.size() * font.lineHeight / 2;
        for (var line : lines) {
            graphics.drawString(font, line, -font.width(line) / 2, y, color, true);
            y += font.lineHeight;
        }
        pose.popPose();
    }
    private DroneRemote.Action action(int slot) {
        if(slot<0) return null;
        if(slot==0 && (hasShiftDown() || state!=null && !state.signal())) return DroneRemote.Action.RECALL;
        if(slot==7 && hasShiftDown()) return DroneRemote.Action.SUPPLY;
        if(slot==5 && hasShiftDown()) return DroneRemote.Action.DELIVER;
        if(slot==2 && hasShiftDown()) return DroneRemote.Action.FIREMODE;
        return DroneRemote.Action.values()[slot];
    }
    private boolean selectable(int slot) {
        return slot>=0 && state!=null && (state.enabled() & 1<<slot)!=0 && (state.signal() || slot==0);
    }
    private Component label(int slot) {
        var act=action(slot);
        if(act==DroneRemote.Action.RECALL) return text("recall");
        if(act==DroneRemote.Action.SUPPLY) return text("supply");
        if(act==DroneRemote.Action.DELIVER) return text("deliver");
        if(act==DroneRemote.Action.FIREMODE) return text("firemode");
        if(act==DroneRemote.Action.WORK) return text("work");
        return Component.translatable(state.labels().get(slot));
    }
    private String sectorIcon(int slot) {
        return switch(action(slot)) {
            case FOLLOW -> (state.off() & 1) != 0 ? "wheel_hold" : "wheel_follow";
            case RECALL -> "wheel_recall";
            case WEAPON -> state.labels().get(1).endsWith("safe") ? "wheel_weapon_stow" : "wheel_weapon_open";
            case MODE -> GuiIcons.mode(state.labels().get(2).replace("dronepal.engage.",""));
            case SCAN -> "wheel_scan";
            case MEDIC -> "wheel_medic";
            case CARRY -> "wheel_pickup";
            case DELIVER -> "wheel_deliver";
            case LIGHT -> "wheel_light";
            case LINK, SUPPLY -> "wheel_link";
            case WORK -> "wheel_work";
            case FIREMODE -> "wheel_burst";
        };
    }
    private void iconLabel(GuiGraphics graphics,String icon,Component label,WheelLayout.Box box,int color) {
        int size = Math.min(16, Math.max(8,box.height()/2));
        int top = box.y() - box.height()/2;
        GuiIcons.draw(graphics,icon,box.x()-size/2,top,size);
        int available=Math.max(4,box.height()-size-1);
        drawLabel(graphics,label,new WheelLayout.Box(box.x(),top+size+1+available/2,box.width(),available),color);
    }
    private static Component text(String key) { return Component.translatable("dronepal.wheel."+key); }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        var wheel = layout();
        int x=wheel.x(), y=wheel.y(), r=wheel.radius(), hole=wheel.hole();
        selection=wheel.sector(mx,my);
        // Eight quad strips leave a physical gap between adjacent targets.
        var pose=g.pose();
        float halfWidth = (float)Math.tan(Math.PI / WheelLayout.SECTORS);
        for(int slot=0;slot<WheelLayout.SECTORS;slot++) {
            int color=!selectable(slot)?EMPTY:slot==selection?SELECTED:RING;
            pose.pushPose(); pose.translate(x,y,0); pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(slot*360f/WheelLayout.SECTORS));
            for(int dy=0;dy<r;dy++) {
                int outer=Math.min((int)(dy*halfWidth)-1,(int)Math.sqrt(r*r-dy*dy));
                if(outer<=0) continue;
                if(dy>=hole) g.fill(-outer,-dy,outer+1,1-dy,color);
                else { int inner=(int)Math.sqrt(hole*hole-dy*dy); if(inner<outer) {g.fill(-outer,-dy,-inner,1-dy,color);g.fill(inner,-dy,outer+1,1-dy,color);} }
            }
            pose.popPose();
        }
        for(int slot=0;slot<WheelLayout.SECTORS;slot++) if(state!=null && (state.enabled() & 1<<slot)!=0) {
            iconLabel(g, sectorIcon(slot), label(slot), wheel.sectorText(slot), selectable(slot) ? WHITE : MUTED);
        }
        Component center = state == null ? text("connecting") : state.enabled() == 0 ? text("none")
                : !state.signal() ? text("signal") : selectable(selection) ? label(selection) : text("cancel");
        boolean off = state != null && selection >= 0 && (state.off() & 1 << selection) != 0;
        String centerIcon = state == null ? "wheel_signal" : state.enabled() == 0 ? "wheel_none" : !state.signal() ? "wheel_lost" : selectable(selection) ? sectorIcon(selection) : "wheel_cancel";
        iconLabel(g, centerIcon, off ? center.copy().append("\n").append(text("off")) : center, wheel.centerText(), WHITE);
        Component header = selectable(selection) ? label(selection) : Component.empty();
        if (state != null && state.solar() >= 0) {
            var solar = Component.translatable("dronepal.wheel.solar", state.solar());
            header = header.getString().isEmpty() ? solar : header.copy().append(" · ").append(solar);
        }
        int headerHeight = Math.max(1, y - r - 5);
        drawLabel(g, header, new WheelLayout.Box(x, headerHeight / 2 + 1, width - 16, headerHeight), WHITE);
        int footerY = y + r + 5;
        for (var line : footer()) {
            g.drawString(font, line, x - font.width(line) / 2, footerY, WHITE, true);
            footerY += font.lineHeight + 1;
        }
    }
    private void sendAction(int slot) {
        diag("sendAction slot=%d action=%s selectable=%s pending=%d", slot, action(slot), selectable(slot), pending);
        if (pending != 0 || !selectable(slot)) return;
        pending = ++revision;
        PacketDistributor.sendToServer(new DroneRemote.Command(state.serial(), action(slot), nonce, pending));
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !KEY.matchesMouse(button)) {
            clickedWhileOpen = true;
            sendAction(layout().sector(x,y));
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    private void release() {
        var window = minecraft.getWindow();
        selection = layout().sector(minecraft.mouseHandler.xpos() * width / window.getScreenWidth(),
                minecraft.mouseHandler.ypos() * height / window.getScreenHeight());
        diag("release nonce=%d age=%d selection=%d clicked=%s keyDown=%s", nonce,
                net.minecraft.Util.getMillis()-opened, selection, clickedWhileOpen, KEY.isDown());
        // The wheel only exists after a completed hold, so releasing always runs the aimed sector.
        if(!clickedWhileOpen) sendAction(selection);
        onClose();
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers) {
        diag("keyReleased key=%d scan=%d matches=%s screen=%s", key, scan, KEY.matches(key,scan),
                minecraft==null||minecraft.screen==null?"null":minecraft.screen.getClass().getSimpleName());
        if(KEY.matches(key,scan)) { release(); return true; }
        return super.keyReleased(key,scan,modifiers);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(KEY.matchesMouse(button)) { release(); return true; }
        return super.mouseReleased(x,y,button);
    }
}
