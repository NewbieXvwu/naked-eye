package dev.honest;

import net.minecraft.client.Minecraft;

/**
 * How bright things LOOK, and how big something must appear to be noticed at that brightness.
 *
 * 1. Brightness: a straight port of vanilla's lightmap shader (assets/minecraft/shaders/core/lightmap.fsh),
 *    including its two "mix towards 0.75 by 4%" floors (why light-level-0 caves aren't pitch black)
 *    and the player's Brightness slider (notGamma blend). Output is relative to full daylight.
 *
 * 2. Noticing: at lower luminance the eye needs more contrast/area to detect a target (de Vries–Rose
 *    behaviour). We use a single documented heuristic: required on-screen size grows as L^-1.5.
 *    Calibration (default brightness, 1080p): daylight ~4 px (>200 blocks for a mob), moonlit night
 *    ~30 px (~30 blocks), unlit cave ~125 px (~13 blocks), unlit cave on "Moody" ~270 px (~6 blocks).
 */
public final class Luma {
    private Luma() {}

    static double getBrightness(double level) { return level / (4.0 - 3.0 * level); }

    /** Perceived brightness (0..~1, relative to noon) for a raw light level 0..15. */
    public static double perceived(int rawLevel, double brightnessSlider) {
        double b = getBrightness(rawLevel / 15.0);
        // the shader's warm "cubic nonsense" tint; we keep the max channel (luminance proxy)
        double r = b, g = b * ((b * 0.6 + 0.4) * 0.6 + 0.4), bl = b * (b * b * 0.6 + 0.4);
        double c = Math.max(r, Math.max(g, bl));
        c = c * 0.96 + 0.75 * 0.04;                       // first floor
        c = Math.min(1, Math.max(0, c));
        double inv = 1 - c, ng = c <= 0 ? 0 : (1 - inv * inv * inv * inv);   // notGamma (max-channel form)
        c = c * (1 - brightnessSlider) + ng * brightnessSlider;
        c = c * 0.96 + 0.75 * 0.04;                       // second floor
        return c / DAY;
    }

    private static final double DAY;
    static {
        double b = 1.0, c = b * 0.96 + 0.03, inv = 1 - c, ng = 1 - inv * inv * inv * inv;
        DAY = (c * 0.5 + ng * 0.5) * 0.96 + 0.03;
    }

    public static double slider() {
        try { return Minecraft.getInstance().options.gamma().get(); } catch (Throwable t) { return 0.5; }
    }

    /** Raw light level as the renderer would use it: max(block, sky - skyDarken). */
    public static int raw(int block, int sky, int skyDarken) { return Math.max(block, Math.max(0, sky - skyDarken)); }

    /** Unpack vanilla packed light coords (block << 4 | sky << 20). */
    public static int blockOf(int packed) { return (packed >> 4) & 0xF; }
    public static int skyOf(int packed) { return (packed >> 20) & 0xF; }

    /** On-screen pixels (1080p) needed to notice something at perceived brightness L. */
    public static double pxToNotice(double L) { return Perception.MIN_PX_TO_NOTICE / Math.pow(Math.max(L, 1e-3), 1.5); }
    public static double pxForDetail(double L) { return Perception.MIN_PX_FOR_DETAIL / Math.pow(Math.max(L, 1e-3), 1.5); }

    /** Max distance at which a surface of the given size is noticed at brightness L. */
    public static double maxDistance(double size, double L, double vfovDeg) {
        double px = pxToNotice(L);
        double angle = Math.toRadians(px / Perception.NOMINAL_SCREEN_PX * vfovDeg);
        if (angle >= Math.PI * 0.9) return 0;
        return (size / 2) / Math.tan(angle / 2);
    }
}
