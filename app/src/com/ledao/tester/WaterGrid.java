package com.ledao.tester;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * WaterGrid —— 校园水面位图（离线资产，由 scripts/mk_watergrid.py 从高德瓦片提取）。
 *
 * 用途：上一版路线是打卡点之间的直线，直接穿湖。有了这张图，
 *      路线生成时可以把落在水里的点推回岸上。
 *
 * 资产很小（576x576 位 = 41KB），加载一次常驻内存。
 */
public class WaterGrid {

    private final int w, h;
    private final byte[] bits;
    private final double lonLeft, latTop, lonRight, latBottom;
    private boolean ok = false;

    private WaterGrid(int w, int h, byte[] bits, double loL, double laT, double loR, double laB,
                      boolean valid) {
        this.w = w; this.h = h; this.bits = bits;
        this.lonLeft = loL; this.latTop = laT; this.lonRight = loR; this.latBottom = laB;
        this.ok = valid;
    }

    public boolean isLoaded() { return ok; }

    public static WaterGrid load(Context ctx) {
        try {
            String js = readText(ctx, "watergrid.json");
            JSONObject o = new JSONObject(js);
            int w = o.getInt("w"), h = o.getInt("h");
            byte[] raw = readBytes(ctx, "watergrid.bin");
            return new WaterGrid(w, h, raw,
                    o.getDouble("lon_left"), o.getDouble("lat_top"),
                    o.getDouble("lon_right"), o.getDouble("lat_bottom"), true);
        } catch (Throwable t) {
            // 加载失败不能崩 —— 退化成「没有水面数据」，路线照跑（只是不避湖）
            return new WaterGrid(1, 1, new byte[1], 0, 0, 0, 0, false);
        }
    }

    public boolean isWater(double lat, double lon) {
        if (!ok) return false;
        int x = (int) ((lon - lonLeft) / (lonRight - lonLeft) * w);
        int y = (int) ((latTop - lat) / (latTop - latBottom) * h);
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        int i = y * w + x;
        return (bits[i >> 3] & (1 << (i & 7))) != 0;
    }

    /**
     * 把水里的点推到最近的岸上。
     * 用「一圈圈往外找」而不是算梯度 —— 对离散位图更稳，也不会推错方向。
     * @return 推好之后的 {lat, lon}；本来就不在水里则原样返回
     */
    public double[] pushOut(double lat, double lon, double maxCells) {
        if (!isWater(lat, lon)) return new double[]{lat, lon};
        double cellLat = (latTop - latBottom) / h;
        double cellLon = (lonRight - lonLeft) / w;
        int cx = (int) ((lon - lonLeft) / (lonRight - lonLeft) * w);
        int cy = (int) ((latTop - lat) / (latTop - latBottom) * h);
        int maxR = (int) Math.max(4, maxCells);
        for (int r = 1; r <= maxR; r++) {
            for (int a = 0; a < 16; a++) {
                double ang = a * Math.PI * 2 / 16;
                int nx = cx + (int) Math.round(Math.cos(ang) * r);
                int ny = cy + (int) Math.round(Math.sin(ang) * r);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int i = ny * w + nx;
                if ((bits[i >> 3] & (1 << (i & 7))) == 0) {
                    return new double[]{
                            latTop - ny * cellLat - cellLat / 2,
                            lonLeft + nx * cellLon + cellLon / 2};
                }
            }
        }
        return new double[]{lat, lon};
    }

    private static String readText(Context c, String name) throws Exception {
        return new String(readBytes(c, name), "UTF-8");
    }

    private static byte[] readBytes(Context c, String name) throws Exception {
        InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
