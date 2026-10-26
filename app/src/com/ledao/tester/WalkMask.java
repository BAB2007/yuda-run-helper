package com.ledao.tester;

import android.graphics.Bitmap;

/**
 * WalkMask —— 把 {@code routenet.bin} 里那张涂鸦栅格变成一张**能给地图画的位图**。
 *
 * <h3>为什么要有这个东西</h3>
 * 用户 2026-10-26 第 60 轮：「跑步路线不要显示路网，要显示可跑区域」。
 * 之前地图上铺的是一层"路网底纹"（363 街口 / 433 街段的细线），
 * 那条线只是**生成路线用的骨架**，不是人能跑的全部地方 ——
 * 真正能跑的是用户自己涂出来的那一片。骨架和那一片差多少，看涂鸦就知道：
 * 涂了 0.50 km²、骨架 24.1 km，中间那片 20 m 宽的带子在图上几乎看不出来。
 *
 * <h3>位图怎么来的</h3>
 * {@code RouteNet.maskBits} 是按行存的点阵（{@code byte[y*stride + (x>>3)] 的 (x&7) 位}），
 * 一格 ≈ 1.9 m。这里把它铺成 ARGB_8888 的位图：涂过的格给一个半透明的绿，
 * 没涂的格全透明（{@code 0}）—— 所以底图能照常透出来，只有"能跑的地方"被染色。
 *
 * <p>位图只建一次（约 3 MB），之后每次绘制只是把同一张图按当前缩放拉伸，
 * 拖动地图不会重新扫一遍 78 万个格。
 *
 * <h3>这不是装饰</h3>
 * 用户看到的那片绿 = {@code RouteNet.inside()} 返回 true 的地方 = 轨迹**可以经过**的地方
 * （见 v1.0.49 的净空摆动：路线在涂鸦里左右摆，摆到边界为止）。
 * 所以"绿的地方车能走、灰的地方走不了"和实际生成路线是同一个判据，不是两套。
 */
public final class WalkMask {

    private WalkMask() { }

    /** 可跑区的染色：和主页面的青绿同一个色相，压在底图上不抢路线（绿粗线）的戏。 */
    public static final int COLOR = 0xFF2E9E6B;
    /** 不透明度（0~255）。压太重会把街道名糊掉，压太轻又看不出边界。 */
    public static final int ALPHA = 96;

    /**
     * 建位图。
     *
     * @return 没有掩膜（老资源 / {@code LEDAO_NOMASK}）时返回 null —— 调用方按"没有可跑区可画"处理
     */
    public static Bitmap build(RouteNet net) {
        return build(net, COLOR, ALPHA);
    }

    public static Bitmap build(RouteNet net, int rgb, int alpha) {
        if (net == null || net.maskBits == null || net.maskW <= 0 || net.maskH <= 0) return null;
        final int w = net.maskW, h = net.maskH, stride = net.maskStride;
        final byte[] bits = net.maskBits;
        final int argb = ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
        int[] px = new int[w * h];
        int on = 0;
        for (int y = 0; y < h; y++) {
            final int row = y * stride;
            final int base = y * w;
            for (int x = 0; x < w; x++) {
                boolean painted = ((bits[row + (x >> 3)] >> (x & 7)) & 1) != 0;
                if (painted) {
                    px[base + x] = argb;
                    on++;
                }
            }
        }
        if (on == 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    /**
     * 涂了多少格（只给日志和自检用 —— 和位图里那 78 万次循环没关系，
     * 单独数是为了让"到底有没有染色"这件事在没手机的时候也能断出来）。
     */
    public static int countPainted(RouteNet net) {
        if (net == null || net.maskBits == null) return 0;
        int n = 0;
        for (byte b : net.maskBits) {
            int v = b & 0xFF;
            while (v != 0) { n += v & 1; v >>= 1; }
        }
        return n;
    }
}
