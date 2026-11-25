package dev.dronepal.client;

/** Executable geometry checks at window pixel sizes and the effective Minecraft GUI scale. */
public final class WheelLayoutCheck {
    public static void main(String[] args) {
        for (int[] window : new int[][]{{640,480,2},{854,480,2},{1280,720,3},{1920,1080,4},{2560,1440,6},{3440,1440,4},{320,240,1}}) {
            int width = Math.ceilDiv(window[0],window[2]), height = Math.ceilDiv(window[1],window[2]);
            for (int lines = 1; lines <= 3; lines++) {
                WheelLayout w = WheelLayout.fit(width,height,lines,10);
                if (w.sector(w.x(),w.y()) != -1 || w.sector(w.x(), w.y()-w.radius()-1) != -1) throw new AssertionError("cancel");
                if (w.y()+w.radius()+5+lines*10 > height) throw new AssertionError("footer clipped");
                for (int slot=0; slot<WheelLayout.SECTORS; slot++) {
                    var box = w.sectorText(slot);
                    for (int x=-1; x<=1; x++) for (int y=-1; y<=1; y++) {
                        if (w.sector(box.x()+x*box.width()*.5, box.y()+y*box.height()*.5)!=slot) throw new AssertionError("text outside its sector");
                    }
                }
            }
            System.out.printf("%dx%d @%d: %dx%d GUI; all sector bounds, center cancellation and wrapped footer passed%n",window[0],window[1],window[2],width,height);
        }
    }
}
