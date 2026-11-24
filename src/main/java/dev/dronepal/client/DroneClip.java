package dev.dronepal.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Map;
import java.util.TreeMap;
import org.joml.Vector3f;

/** A numeric Bedrock keyframe track, in authoring units and degrees. */
record DroneClip(String bone, String channel, float duration, float[] times, Vector3f[] values) {
    static Map<String,DroneClip> load(Reader source) {
        Map<String,DroneClip> result = new TreeMap<>();
        JsonObject clips = JsonParser.parseReader(source).getAsJsonObject().getAsJsonObject("animations");
        for (var animation : clips.entrySet()) {
            JsonObject clip = animation.getValue().getAsJsonObject();
            JsonObject bones = clip.getAsJsonObject("bones");
            if (bones.size()!=1) throw new IllegalArgumentException("Expected single bone in " + animation.getKey());
            var bone = bones.entrySet().iterator().next();
            JsonObject channels = bone.getValue().getAsJsonObject();
            if (channels.size()!=1) throw new IllegalArgumentException("Expected single track in " + animation.getKey());
            var track = channels.entrySet().iterator().next();
            var keys = new TreeMap<Float,Vector3f>();
            for (var k : track.getValue().getAsJsonObject().entrySet()) {
                JsonArray v = k.getValue().getAsJsonArray();
                keys.put(Float.parseFloat(k.getKey()), new Vector3f(v.get(0).getAsFloat(),v.get(1).getAsFloat(),v.get(2).getAsFloat()));
            }
            float[] times = new float[keys.size()];
            int i = 0;
            for (float time : keys.keySet()) times[i++] = time;
            result.put(animation.getKey(), new DroneClip(bone.getKey(),track.getKey(),clip.get("animation_length").getAsFloat(),times,keys.values().toArray(Vector3f[]::new)));
        }
        return Map.copyOf(result);
    }
    Vector3f hurt(int remaining, int durationTicks, float partial) {
        if (remaining <= 0 || durationTicks <= 0) return new Vector3f();
        return sample(duration * Math.clamp((durationTicks - remaining + partial) / durationTicks, 0F, 1F));
    }
    Vector3f sample(float seconds) {
        if (seconds<=times[0]) return new Vector3f(values[0]);
        for (int i=1; i<times.length; i++) {
            if (seconds<=times[i]) return values[i-1].lerp(values[i],(seconds-times[i-1])/(times[i]-times[i-1]),new Vector3f());
        }
        return new Vector3f(values[values.length-1]);
    }
}
