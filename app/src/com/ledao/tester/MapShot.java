package com.ledao.tester;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;

/**
 * MapShot —— 生成「跑步截图」（stopRunV278 的 file_img）
 *
 * 真实 App 上传的是一张 高德地图 + 彩色轨迹线 的截图（实测 540x1059）。
 * 我们照做：拉高德公开栅格瓦片当底图，再把轨迹按配速上色画上去，
 * 起点画绿色三角、终点画圆点 —— 和 App 的观感一致。
 *
 * 瓦片用的是高德的公开栅格接口（不需要 key）：
 *   https://wprd0{1..4}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x={x}&y={y}&z={z}
 */
public class MapShot {

    // 日志接口直接用 Ledao.Log（同包），避免各模块各定义一套接口导致类型不兼容

    private static final String TILE = "https://wprd0%d.is.autonavi.com/appmaptile"
            + "?lang=zh_cn&size=1&style=7&x=%d&y=%d&z=%d";
    private static final int TS = 256;          // 瓦片边长

    /** 轨迹点：{纬度, 经度, 速度 m/s} */
    public static class P {
        public double lat, lon, spd;
        public P(double a, double o, double s) { lat = a; lon = o; spd = s; }
    }

    // ---------------------------------------------------------------- 墨卡托换算
    private static double lon2x(double lon, int z) {
        return (lon + 180.0) / 360.0 * TS * (1 << z);
    }
    private static double lat2y(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * TS * (1 << z);
    }

    /**
     * 生成截图。失败返回 null（调用方会退化成一张纯色图）。
     * @param w 宽 @param h 高  默认用 540x1059（与真实截图一致）
     */
    public static byte[] render(List<P> track, int w, int h, Ledao.Log log) {
        if (track == null || track.size() < 2) return null;

        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (P p : track) {
            minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon);
        }
        // 留边距，别让轨迹贴边
        double padLat = Math.max(1e-4, (maxLat - minLat) * 0.25);
        double padLon = Math.max(1e-4, (maxLon - minLon) * 0.25);
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon;

        // 选一个最清晰又能装下的缩放级别
        int z = 17;
        for (; z > 12; z--) {
            double bw = lon2x(maxLon, z) - lon2x(minLon, z);
            double bh = lat2y(minLat, z) - lat2y(maxLat, z);
            if (bw <= w * 0.9 && bh <= h * 0.9) break;
        }

        // 画布左上角对应的世界像素
        double cx = (lon2x(minLon, z) + lon2x(maxLon, z)) / 2.0;
        double cy = (lat2y(minLat, z) + lat2y(maxLat, z)) / 2.0;
        double ox = cx - w / 2.0;
        double oy = cy - h / 2.0;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.parseColor("#EDEFEA"));      // 底图没拉到时是浅灰绿

        // ---- 铺瓦片
        int tx0 = (int) Math.floor(ox / TS), tx1 = (int) Math.floor((ox + w) / TS);
        int ty0 = (int) Math.floor(oy / TS), ty1 = (int) Math.floor((oy + h) / TS);
        int total = (tx1 - tx0 + 1) * (ty1 - ty0 + 1), done = 0;
        int maxTile = 1 << z;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                if (tx < 0 || ty < 0 || tx >= maxTile || ty >= maxTile) continue;
                Bitmap t = tile(tx, ty, z);
                done++;
                if (t != null) {
                    cv.drawBitmap(t, (float) (tx * TS - ox), (float) (ty * TS - oy), null);
                    t.recycle();
                }
            }
        }
        if (log != null) log.log("      底图瓦片 " + done + "/" + total + "  z=" + z);

        // ---- 轨迹：先白描边再按配速上色（和 App 一样 慢=绿 快=红）
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(11f);

        Paint halo = new Paint(line);
        halo.setColor(Color.parseColor("#CCFFFFFF"));
        halo.setStrokeWidth(17f);

        Path path = new Path();
        float[] xs = new float[track.size()], ys = new float[track.size()];
        for (int i = 0; i < track.size(); i++) {
            P p = track.get(i);
            xs[i] = (float) (lon2x(p.lon, z) - ox);
            ys[i] = (float) (lat2y(p.lat, z) - oy);
        }
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < track.size(); i++) path.lineTo(xs[i], ys[i]);
        cv.drawPath(path, halo);

        double minS = Double.MAX_VALUE, maxS = -Double.MAX_VALUE;
        for (P p : track) { minS = Math.min(minS, p.spd); maxS = Math.max(maxS, p.spd); }
        if (maxS - minS < 0.05) maxS = minS + 0.05;
        for (int i = 1; i < track.size(); i++) {
            double t = (track.get(i).spd - minS) / (maxS - minS);
            line.setColor(speedColor(t));
            cv.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], line);
        }

        // ---- 起点 / 终点
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(Color.parseColor("#12B76A"));
        pt.setStyle(Paint.Style.FILL);
        cv.drawCircle(xs[0], ys[0], 13f, pt);
        pt.setColor(Color.WHITE);
        cv.drawCircle(xs[0], ys[0], 6f, pt);
        pt.setColor(Color.parseColor("#F04438"));
        cv.drawCircle(xs[xs.length - 1], ys[ys.length - 1], 13f, pt);
        pt.setColor(Color.WHITE);
        cv.drawCircle(xs[xs.length - 1], ys[ys.length - 1], 6f, pt);

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        bmp.recycle();
        byte[] out = bo.toByteArray();
        if (log != null) log.log("      截图生成完成 " + w + "x" + h + "  " + out.length + " 字节");
        return out;
    }

    /** 0=慢(绿) 1=快(红)，中间过黄 */
    private static int speedColor(double t) {
        t = Math.max(0, Math.min(1, t));
        int r, g;
        if (t < 0.5) { double u = t / 0.5; r = (int) (0x2E + (0xF5 - 0x2E) * u); g = (int) (0xC4 + (0xD0 - 0xC4) * u); }
        else { double u = (t - 0.5) / 0.5; r = (int) (0xF5 + (0xE0 - 0xF5) * u); g = (int) (0xD0 + (0x3A - 0xD0) * u); }
        return Color.rgb(r, g, 0x2E);
    }

    // ---------------------------------------------------------------- 拉瓦片
    private static Bitmap tile(int x, int y, int z) {
        for (int tryHost = 0; tryHost < 4; tryHost++) {
            int host = 1 + ((x + y + tryHost) & 3);
            HttpURLConnection c = null;
            try {
                URL u = new URL(String.format(TILE, host, x, y, z));
                c = (HttpURLConnection) u.openConnection(Proxy.NO_PROXY);   // ★ 绕开系统代理
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10)");
                if (c.getResponseCode() != 200) continue;
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                byte[] d = bo.toByteArray();
                if (d.length < 500) continue;              // 空白/错误瓦片
                return android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
            } catch (Exception e) {
                // 换下一个瓦片服务器
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    /** 兜底：底图拉不到时给一张能看的图，至少别是空白 */
    public static byte[] fallback(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.parseColor("#1B7A4B"));
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(Color.parseColor("#E8F5E9"));
        cv.drawRect(w * 0.1f, h * 0.35f, w * 0.9f, h * 0.6f, pt);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        bmp.recycle();
        return bo.toByteArray();
    }
}
