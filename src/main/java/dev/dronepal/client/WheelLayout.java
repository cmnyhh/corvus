package dev.dronepal.client;

/** All distances are in GUI-scaled pixels, shared by drawing and hit testing. */
record WheelLayout(int x, int y, int radius, int hole) {
    /** Eight original sectors plus the work sector. */
    static final int SECTORS = 9;
    static WheelLayout fit(int width, int height, int footerLines, int lineHeight) {
        int y = height / 2;
        int footer = footerLines * lineHeight + 12;
        int radius = Math.max(12, Math.min(140, Math.min(width / 2 - 6, y - footer)));
        return new WheelLayout(width / 2, y, radius, Math.max(5, radius / 3));
    }

    int sector(double mouseX, double mouseY) {
        double dx = mouseX - x, dy = mouseY - y;
        double distance = Math.hypot(dx, dy);
        if (distance < hole || distance > radius) return -1;
        return Math.floorMod((int)Math.floor(Math.atan2(dx, -dy) / (2 * Math.PI / SECTORS) + 0.5), SECTORS);
    }

    record Box(int x, int y, int width, int height) {}

    Box sectorText(int slot) {
        // The rotated wedge contains this circle, so its inscribed horizontal rectangle
        // fits even in the diagonal sectors, independent of the selected font.
        double angle = slot * 2 * Math.PI / SECTORS;
        double distance = (radius + hole) * 0.52;
        double safeRadius = Math.min(radius - distance, Math.min(distance - hole, distance * Math.sin(Math.PI / SECTORS))) - 2;
        int cx = x + (int)Math.round(distance * Math.sin(angle));
        int cy = y - (int)Math.round(distance * Math.cos(angle));
        return new Box(cx, cy, Math.max(2,(int)(safeRadius * 1.76)), Math.max(2,(int)(safeRadius * .90)));
    }

    Box centerText() { return new Box(x,y,Math.max(2,(int)(hole*1.6)-2),Math.max(2,hole-2)); }
}
