package dev.dronepal;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Completed contacts, relative to the owner. Target names stay translatable on the client. */
final class ScanSpeech {
    private record Result(UUID serial, ResourceLocation dimension, ScanSnapshot.Ore ore, UUID hostile, String persona) {}
    private Result last;
    private long lastTick;
    private static final String[] DIRECTIONS = {"东", "东南", "南", "西南", "西", "西北", "北", "东北"};

    Component describe(ScanSnapshot scan, Vec3 player, String persona, boolean automatic, long tick) {
        var ore = scan.ores().isEmpty() ? null : scan.ores().getFirst();
        var mob = scan.hostiles().isEmpty() ? null : scan.hostiles().getFirst();
        if (automatic && ore == null && mob == null) { last = null; return null; }
        var result = new Result(scan.serial(), scan.dimension(), ore, mob == null ? null : mob.uuid(), persona);
        if (result.equals(last) && (automatic || tick - lastTick < 200)) return null;
        if (automatic && last != null && tick - lastTick < 160) return null;
        last = result;
        lastTick = tick;
        boolean chatty = persona.equals("chatty");
        if (ore == null && mob == null) return Component.literal(chatty ? "我找了一圈，这次没扫到目标。" : "扫描完成。未发现目标。");
        MutableComponent text = Component.literal(chatty ? "看这边！" : "扫描完成。");
        if (ore != null) {
            text.append(location(player, ore.pos())).append(chatty ? "有" : "发现")
                .append(BuiltInRegistries.BLOCK.getOptional(ore.type()).<Component>map(block -> block.getName()).orElseGet(() -> Component.literal(ore.type().toString()))).append("。");
        }
        if (mob != null) {
            text.append(chatty ? "小心，" : "警戒，").append(location(player, mob.pos())).append("有")
                .append(BuiltInRegistries.ENTITY_TYPE.getOptional(mob.type()).<Component>map(type -> type.getDescription()).orElseGet(() -> Component.literal(mob.type().toString()))).append(chatty ? "！" : "。");
        }
        return text;
    }
    private static String location(Vec3 player, BlockPos pos) {
        var delta = Vec3.atCenterOf(pos).subtract(player);
        long distance = Math.round(delta.length());
        if (distance == 0) return "你身边";
        if (delta.horizontalDistance() < 1) return (delta.y > 0 ? "正上方" : "正下方") + distance + "格处";
        int octant = Math.floorMod((int)Math.round(Math.atan2(delta.z, delta.x) / (Math.PI / 4)), 8);
        String vertical = delta.y >= 3 ? "上方" : delta.y <= -3 ? "下方" : "面";
        return DIRECTIONS[octant] + vertical + "约" + distance + "格处";
    }
}
