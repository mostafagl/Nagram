package tw.nekomimi.nekogram.helpers;

import tw.nekomimi.nekogram.NekoConfig;

/** Parvagram's look options shared by several cells. */
public final class ParvaLook {

    private ParvaLook() {
    }

    /**
     * The corner radius for an avatar that Telegram draws as a circle of radius {@code circleRadius},
     * per the "avatar roundness" setting (50 = circle, 0 = square).
     */
    public static int avatarRadius(int circleRadius) {
        final int roundness = NekoConfig.avatarRoundness;
        if (roundness >= 50) {
            return circleRadius;
        }
        return Math.max(1, circleRadius * Math.max(0, roundness) / 50);
    }

    public static boolean customAvatarShape() {
        return NekoConfig.avatarRoundness < 50;
    }
}
