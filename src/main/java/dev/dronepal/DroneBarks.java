package dev.dronepal;

import java.util.HashSet;
import java.util.Set;

/** One sample per second. Simultaneous transitions prioritize health, then power. */
final class DroneBarks {
    record State(String dimension, boolean critical, boolean lowBattery, boolean night, boolean rain) {}
    private State last;
    private final Set<String> visited = new HashSet<>();
    private long readyAt;

    String advance(State now, long tick, double draw, double probability, int cooldown) {
        var old = last;
        last = now;
        boolean newDimension = visited.add(now.dimension());
        String event = null;
        if (now.critical() && (old == null || !old.critical())) event = "friend_low";
        else if (now.lowBattery() && (old == null || !old.lowBattery())) event = "low_battery";
        else if (old != null && newDimension) event = "dimension";
        else if (old != null && !old.night() && now.night()) event = "nightfall";
        else if (old != null && !old.rain() && now.rain()) event = "rain_start";
        return event != null && allow(tick, draw, probability, cooldown) ? event : null;
    }
    boolean allow(long tick, double draw, double probability, int cooldown) {
        if (tick < readyAt || draw >= probability) return false;
        readyAt = tick + cooldown;
        return true;
    }
}
