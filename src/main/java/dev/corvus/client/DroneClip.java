package dev.corvus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.Map;
import java.util.TreeMap;
import org.joml.Vector3f;

/**
 * One numeric Bedrock keyframe track, in authoring units (blocks for position, degrees for
 * rotation). Ported from the 1.21 build; the only changes are {@code Math.clamp} (Java 21) being
 * replaced by a local clamp, because this source set compiles at language level 17.
 *
 * <p>The 1.20.1 rig loader ({@link DroneMesh}) already keeps the authored bone names, so a clip is
 * applied by bone name at draw time.</p>
 */
public record DroneClip(String bone, String channel, float duration, float[] times, Vector3f[] values) {
    static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }

    static Map<String, DroneClip> load(Reader source) {
        Map<String, DroneClip> result = new TreeMap<>();
        JsonObject clips = JsonParser.parseReader(source).getAsJsonObject().getAsJsonObject("animations");
        for (var animation : clips.entrySet()) {
            JsonObject clip = animation.getValue().getAsJsonObject();
            JsonObject bones = clip.getAsJsonObject("bones");
            if (bones.size() != 1) throw new IllegalArgumentException("Expected single bone in " + animation.getKey());
            var bone = bones.entrySet().iterator().next();
            JsonObject channels = bone.getValue().getAsJsonObject();
            if (channels.size() != 1) throw new IllegalArgumentException("Expected single track in " + animation.getKey());
            var track = channels.entrySet().iterator().next();
            var keys = new TreeMap<Float, Vector3f>();
            for (var key : track.getValue().getAsJsonObject().entrySet()) {
                JsonArray value = key.getValue().getAsJsonArray();
                keys.put(Float.parseFloat(key.getKey()),
                        new Vector3f(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat()));
            }
            float[] times = new float[keys.size()];
            int index = 0;
            for (float time : keys.keySet()) times[index++] = time;
            result.put(animation.getKey(), new DroneClip(bone.getKey(), track.getKey(),
                    clip.get("animation_length").getAsFloat(), times, keys.values().toArray(new Vector3f[0])));
        }
        return Map.copyOf(result);
    }

    /** Recoil: the first {@code durationTicks} of the clip, driven by the engine's hurt timer. */
    public Vector3f hurt(int remaining, int durationTicks, float partial) {
        if (remaining <= 0 || durationTicks <= 0) return new Vector3f();
        return sample(duration * clamp((durationTicks - remaining + partial) / durationTicks, 0F, 1F));
    }

    public Vector3f sample(float seconds) {
        if (seconds <= times[0]) return new Vector3f(values[0]);
        for (int i = 1; i < times.length; i++) {
            if (seconds <= times[i]) {
                return values[i - 1].lerp(values[i], (seconds - times[i - 1]) / (times[i] - times[i - 1]), new Vector3f());
            }
        }
        return new Vector3f(values[values.length - 1]);
    }

    /** Sampled once at the raw progress of the whole clip, for state clips like stow/deploy. */
    public Vector3f progress(float fraction) {
        return sample(duration * clamp(fraction, 0F, 1F));
    }
}
