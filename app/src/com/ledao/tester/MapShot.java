package com.ledao.tester;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MapShot —— 生成「跑步截图」（stopRunV278 的 file_img）
 *
 * <h3>真机那张图是什么</h3>
 * 真机上传的是**整屏截屏**：{@code files/.screen_shot/<ms>_<uid>.jpg}，
 * 画面里有状态栏（时间/信号/电量）、步道乐跑的标题栏、地图、彩色轨迹线，
 * 以及底部那块写着**这一场的里程/用时/配速**的成绩卡。实测尺寸 540×1059
 * （1080×2118 的屏按 0.5 缩下来，正好去掉底部导航条那一截）。
 *
 * <h3>★★★ v1.0.39 之前它只有"地图 + 折线"</h3>
 * 旧实现只画高德瓦片 + 轨迹 + 起终点圆点，**一个字都没有**。后果写在 docs/71 §二.15：
 * 同一路线跑两次，两张图除了折线外**几乎逐像素相同**；底图没拉到时还会退化成一整块
 * 纯色图（{@link #fallback}），那几张在**所有账号之间一模一样**。
 *
 * <p>现在按真机的信息结构整屏重画（#9），并且把这一场的**真数字**印上去（{@link Rec}）：
 * 用时、里程、配速、打卡数、收尾时刻 —— 都是 {@link Ledao#run} 当场算出来的同一批数，
 * 不是另编的。状态栏的电量/信号/运营商、地图缩放级别、卡片留白等由本场种子决定
 * （{@link #render} 的 {@code rnd} 参数），所以**跨场/跨账号不再同模板**（#15）。
 *
 * <h3>它仍然不是"截屏"</h3>
 * 这一条要如实说：真机那张是**系统截屏**（像素级带着该机型的 UI 细节、字体渲染、
 * 状态栏图标），我们是**按同样的版式重绘**。观感接近，取证层面不同。
 * 想真正等同，只有"在真机上跑官方 App 时截屏"这一条路 —— 那不在本工具的能力范围。
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

    /**
     * 这一场要印在截图上的**数字**（#9）。
     *
     * <p>全部来自 {@link Ledao#run} 的同一个变量：{@code km}/{@code usedSec} 就是
     * stopRunV278 里的 {@code distance}/{@code used_time}，{@code endSec} 就是
     * {@code end_time}。截图与报文对不上是比"没有截图"更容易被抓的破绽，
     * 所以这里只搬数、不造数。
     */
    public static class Rec {
        public double km = 0;              // 上报里程（km）
        public long usedSec = 0;           // 用时（秒）
        public int hits = 0, total = 0;    // 打卡点（打到 / 分配）
        public long endSec = 0;            // 收尾时刻（epoch 秒）
        public double cadence = 0;         // 步频（步/分）
        public double stride = 0;          // 步长（m/步）
    }

    // ---------------------------------------------------------------- 纯格式化（离线可测）
    /**
     * 用时：{@code 616 → "10:16"}；超过一小时 {@code "1:02:03"}。
     * 与真机成绩卡的口径一致（分:秒，不补零到小时）。
     */
    public static String fmtDur(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** 配速：{@code 299 → "4'59\""}（秒/公里，取整） */
    public static String fmtPace(int secPerKm) {
        if (secPerKm < 0) secPerKm = 0;
        return String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60);
    }

    /** 状态栏时钟：{@code epoch 秒 → "21:49"} */
    public static String clock(long epochSec) {
        if (epochSec <= 0) epochSec = System.currentTimeMillis() / 1000;
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(epochSec * 1000L));
    }

    /** 成绩卡上的日期：{@code "2026.10.06"} */
    public static String dayText(long epochSec) {
        if (epochSec <= 0) epochSec = System.currentTimeMillis() / 1000;
        return new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date(epochSec * 1000L));
    }

    // ---------------------------------------------------------------- 墨卡托换算
    private static double lon2x(double lon, int z) {
        return (lon + 180.0) / 360.0 * TS * (1 << z);
    }
    private static double lat2y(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * TS * (1 << z);
    }

    /** 旧签名（保留给老调用方）：没有成绩数字时按 0 处理，仍然画整屏，只是数字是空的 */
    public static byte[] render(List<P> track, int w, int h, Ledao.Log log) {
        return render(track, w, h, null, null, log);
    }

    /**
     * 生成整屏截图。失败返回 null（调用方会退化成 {@link #fallback}）。
     *
     * @param w   宽（默认 540，与真机截图一致）
     * @param h   高（默认 1059）
     * @param rec 这一场的成绩数字；可以为 null（只画地图，不印数）
     * @param rnd 本场随机源：状态栏电量/信号/地图缩放/卡片留白都从它来 —— 跨场不同模板
     */
    public static byte[] render(List<P> track, int w, int h, Rec rec, Random rnd, Ledao.Log log) {
        if (track == null || track.size() < 2) return null;
        Random r = rnd != null ? rnd : new Random();

        /* ---- 版式：状态栏 / 标题栏 / 地图 / 成绩卡（真机那张图就是这四块） ---- */
        int statusH = Math.round(h * 0.032f);
        int headH = Math.round(h * 0.088f);
        int cardH = Math.round(h * 0.190f);
        int mapTop = statusH + headH;
        int mapBot = h - cardH;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.WHITE);

        /* ---- ① 状态栏 ---- */
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(Color.parseColor("#F5F6F8"));
        cv.drawRect(0, 0, w, statusH, pt);
        pt.setColor(Color.parseColor("#1F2328"));
        pt.setTextSize(statusH * 0.56f);
        pt.setTextAlign(Paint.Align.LEFT);
        pt.setFakeBoldText(true);
        cv.drawText(clock(rec != null ? rec.endSec : 0), w * 0.035f, statusH * 0.72f, pt);
        pt.setFakeBoldText(false);
        /* 右侧：信号格 + 4G + 电量（电量与格数每场不同） */
        int bars = 3 + r.nextInt(2);
        float bx = w * 0.795f;
        for (int i = 0; i < 4; i++) {
            float bh = statusH * 0.20f * (i + 1);
            pt.setColor(i < bars ? Color.parseColor("#1F2328") : Color.parseColor("#C7CBD1"));
            cv.drawRect(bx + i * statusH * 0.16f, statusH * 0.70f - bh,
                    bx + i * statusH * 0.16f + statusH * 0.10f, statusH * 0.70f, pt);
        }
        pt.setColor(Color.parseColor("#1F2328"));
        pt.setTextSize(statusH * 0.46f);
        cv.drawText("4G", bx + statusH * 0.72f, statusH * 0.70f, pt);
        int battery = 55 + r.nextInt(42);
        float battL = w * 0.895f, battR = w * 0.962f;
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(statusH * 0.055f);
        pt.setColor(Color.parseColor("#1F2328"));
        cv.drawRoundRect(new RectF(battL, statusH * 0.28f, battR, statusH * 0.72f),
                statusH * 0.10f, statusH * 0.10f, pt);
        pt.setStyle(Paint.Style.FILL);
        cv.drawRect(battR + statusH * 0.02f, statusH * 0.42f, battR + statusH * 0.07f,
                statusH * 0.58f, pt);
        pt.setColor(Color.parseColor("#12B76A"));
        cv.drawRect(battL + statusH * 0.06f, statusH * 0.34f,
                battL + statusH * 0.06f + (battR - battL - statusH * 0.12f) * battery / 100f,
                statusH * 0.66f, pt);
        pt.setTextSize(statusH * 0.42f);
        pt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(battery + "%", battL - statusH * 0.10f, statusH * 0.66f, pt);

        /* ---- ② 标题栏（乐跑的绿） ---- */
        pt.setColor(Color.parseColor("#0E9F6E"));
        cv.drawRect(0, statusH, w, mapTop, pt);
        pt.setColor(Color.WHITE);
        pt.setTextAlign(Paint.Align.CENTER);
        pt.setTextSize(headH * 0.40f);
        pt.setFakeBoldText(true);
        cv.drawText("跑步记录", w * 0.5f, mapTop - headH * 0.36f, pt);
        pt.setFakeBoldText(false);
        pt.setTextSize(headH * 0.34f);
        pt.setTextAlign(Paint.Align.LEFT);
        cv.drawText("‹", w * 0.045f, mapTop - headH * 0.34f, pt);
        pt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("···", w * 0.955f, mapTop - headH * 0.34f, pt);

        /* ---- ③ 地图 + 轨迹（裁在地图区里） ---- */
        cv.save();
        cv.clipRect(0, mapTop, w, mapBot);
        cv.drawRect(0, mapTop, w, mapBot, tint("#EBEEE9"));
        drawMap(cv, track, w, mapTop, mapBot, r, log);
        cv.restore();
        /* 地图右下角的高德署名（真机截图里那块地图也带这个） */
        pt.setTextSize(w * 0.026f);
        pt.setTextAlign(Paint.Align.RIGHT);
        pt.setColor(Color.parseColor("#7A828C"));
        cv.drawText("© 高德地图", w - w * 0.03f, mapBot - w * 0.022f, pt);

        /* ---- ④ 成绩卡 ---- */
        pt.setColor(Color.WHITE);
        cv.drawRect(0, mapBot, w, h, pt);
        pt.setColor(Color.parseColor("#E7E9ED"));
        cv.drawRect(0, mapBot, w, mapBot + 1, pt);
        double km = rec != null ? rec.km : 0;
        long used = rec != null ? rec.usedSec : 0;
        int pace = (int) (used / Math.max(0.001, km));
        float top = mapBot + cardH * 0.10f;
        /* 大号里程 */
        pt.setTextAlign(Paint.Align.LEFT);
        pt.setFakeBoldText(true);
        pt.setColor(Color.parseColor("#111827"));
        pt.setTextSize(cardH * 0.40f);
        String kmTxt = String.format(Locale.US, "%.2f", km);
        cv.drawText(kmTxt, w * 0.055f, top + cardH * 0.36f, pt);
        pt.setFakeBoldText(false);
        pt.setTextSize(cardH * 0.16f);
        pt.setColor(Color.parseColor("#6B7280"));
        float kmW = pt.measureText("0000");
        cv.drawText("公里", w * 0.055f + cardH * 0.40f + kmW * 0.62f, top + cardH * 0.36f, pt);
        /* 三列：用时 / 配速 / 打卡 */
        String[][] cells = {
                {"用时", fmtDur(used)},
                {"平均配速", fmtPace(pace) + "/公里"},
                {"打卡点", rec != null ? (rec.hits + "/" + rec.total) : "—"},
        };
        float cw = (w - w * 0.11f) / 3f;
        for (int i = 0; i < cells.length; i++) {
            float cx = w * 0.055f + cw * i;
            pt.setTextAlign(Paint.Align.LEFT);
            pt.setTextSize(cardH * 0.115f);
            pt.setColor(Color.parseColor("#9AA1AC"));
            cv.drawText(cells[i][0], cx, top + cardH * 0.56f, pt);
            pt.setTextSize(cardH * 0.155f);
            pt.setColor(Color.parseColor("#111827"));
            pt.setFakeBoldText(true);
            cv.drawText(cells[i][1], cx, top + cardH * 0.74f, pt);
            pt.setFakeBoldText(false);
        }
        /* 底部：一句话 + 绿按钮 */
        pt.setTextAlign(Paint.Align.LEFT);
        pt.setTextSize(cardH * 0.105f);
        pt.setColor(Color.parseColor("#9AA1AC"));
        cv.drawText(dayText(rec != null ? rec.endSec : 0)
                + (rec != null && rec.cadence > 0
                    ? String.format(Locale.US, " · 步频 %.0f · 步长 %.2f m", rec.cadence, rec.stride)
                    : ""), w * 0.055f, h - cardH * 0.10f, pt);
        float btnL = w * 0.055f, btnR = w - w * 0.055f;
        float btnB = h - cardH * 0.20f, btnT = btnB - cardH * 0.30f;
        pt.setColor(Color.parseColor("#0E9F6E"));
        cv.drawRoundRect(new RectF(btnL, btnT, btnR, btnB), cardH * 0.15f, cardH * 0.15f, pt);
        pt.setColor(Color.WHITE);
        pt.setTextAlign(Paint.Align.CENTER);
        pt.setTextSize(cardH * 0.145f);
        pt.setFakeBoldText(true);
        cv.drawText("完成", (btnL + btnR) / 2f, btnT + cardH * 0.205f, pt);
        pt.setFakeBoldText(false);

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        bmp.recycle();
        byte[] out = bo.toByteArray();
        if (log != null)
            log.log("      截图生成完成 " + w + "x" + h + "  " + out.length + " 字节"
                    + "（整屏：状态栏 + 标题栏 + 地图 + 成绩卡；用时 "
                    + fmtDur(used) + " / 里程 " + String.format(Locale.US, "%.2f", km)
                    + " km / 配速 " + fmtPace(pace) + "）");
        return out;
    }

    /** 底色画笔的小工具（少写两行） */
    private static Paint tint(String c) {
        Paint p = new Paint();
        p.setColor(Color.parseColor(c));
        return p;
    }

    // ---------------------------------------------------------------- 地图区
    private static void drawMap(Canvas cv, List<P> track, int w, int mapTop, int mapBot,
                                Random rnd, Ledao.Log log) {
        int h = mapBot - mapTop;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (P p : track) {
            minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon);
        }
        double padLat = Math.max(2e-4, (maxLat - minLat) * 0.22);
        double padLon = Math.max(2e-4, (maxLon - minLon) * 0.22);
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon;

        /* 缩放级别：装得下就行；本场再随机放宽一级，别每张图都同一个比例（#15） */
        int z = 17;
        for (; z > 12; z--) {
            double bw = lon2x(maxLon, z) - lon2x(minLon, z);
            double bh = lat2y(minLat, z) - lat2y(maxLat, z);
            if (bw <= w * 0.86 && bh <= h * 0.86) break;
        }
        if (rnd.nextDouble() < 0.35 && z > 13) z--;

        double cx = (lon2x(minLon, z) + lon2x(maxLon, z)) / 2.0;
        double cy = (lat2y(minLat, z) + lat2y(maxLat, z)) / 2.0;
        double ox = cx - w / 2.0;
        double oy = cy - h / 2.0;

        int tx0 = (int) Math.floor(ox / TS), tx1 = (int) Math.floor((ox + w) / TS);
        int ty0 = (int) Math.floor(oy / TS), ty1 = (int) Math.floor((oy + h) / TS);
        int total = (tx1 - tx0 + 1) * (ty1 - ty0 + 1), done = 0, got = 0;
        int maxTile = 1 << z;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                if (tx < 0 || ty < 0 || tx >= maxTile || ty >= maxTile) continue;
                Bitmap t = tile(tx, ty, z);
                done++;
                if (t != null) {
                    got++;
                    cv.drawBitmap(t, (float) (tx * TS - ox), (float) (mapTop + ty * TS - oy), null);
                    t.recycle();
                }
            }
        }
        if (log != null) log.log("      底图瓦片 " + got + "/" + total + "  z=" + z);
        /* 一张瓦片都没拉到：画本场专属的街道网格，别交一张纯色的图（旧代码就是纯色） */
        if (got == 0) drawStreetGrid(cv, w, mapTop, h, rnd);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(11f);
        Paint halo = new Paint(line);
        halo.setColor(Color.parseColor("#CCFFFFFF"));
        halo.setStrokeWidth(17f);

        float[] xs = new float[track.size()], ys = new float[track.size()];
        for (int i = 0; i < track.size(); i++) {
            P p = track.get(i);
            xs[i] = (float) (lon2x(p.lon, z) - ox);
            ys[i] = (float) (mapTop + lat2y(p.lat, z) - oy);
        }
        Path path = new Path();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) path.lineTo(xs[i], ys[i]);
        cv.drawPath(path, halo);

        double minS = Double.MAX_VALUE, maxS = -Double.MAX_VALUE;
        for (P p : track) { minS = Math.min(minS, p.spd); maxS = Math.max(maxS, p.spd); }
        if (maxS - minS < 0.05) maxS = minS + 0.05;
        for (int i = 1; i < track.size(); i++) {
            double t = (track.get(i).spd - minS) / (maxS - minS);
            line.setColor(speedColor(t));
            cv.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], line);
        }

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
    }

    /** 底图全挂时的兜底：画一张本场专属的"街道图"（不是纯色，跨账号不同） */
    private static void drawStreetGrid(Canvas cv, int w, int mapTop, int h, Random rnd) {
        cv.drawRect(0, mapTop, w, mapTop + h, tint("#E9EDE7"));
        int roads = 5 + rnd.nextInt(4);
        for (int i = 0; i < roads; i++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(rnd.nextBoolean() ? Color.parseColor("#FFFFFF")
                    : Color.parseColor("#F6F1DC"));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f + rnd.nextFloat() * 5f);
            if (rnd.nextBoolean()) {
                float y = mapTop + rnd.nextFloat() * h;
                cv.drawLine(0, y, w, y + (rnd.nextFloat() - 0.5f) * h * 0.2f, p);
            } else {
                float x = rnd.nextFloat() * w;
                cv.drawLine(x, mapTop, x + (rnd.nextFloat() - 0.5f) * w * 0.2f, mapTop + h, p);
            }
        }
        /* 几块绿地 */
        for (int i = 0; i < 4; i++) {
            Paint g = tint("#D6E6D2");
            float l = rnd.nextFloat() * w * 0.8f, t = mapTop + rnd.nextFloat() * h * 0.8f;
            cv.drawRect(l, t, l + w * 0.12f, t + h * 0.10f, g);
        }
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
                URL u = new URL(String.format(Locale.US, TILE, host, x, y, z));
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

    /**
     * 兜底：整屏照画，只是底图换成一张本场专属的街道示意图。
     * （旧版这里是一整块绿色 + 一个浅色方块 —— 那几张图在所有账号之间**逐像素相同**。）
     */
    public static byte[] fallback(int w, int h) {
        return fallback(w, h, null, null);
    }

    public static byte[] fallback(int w, int h, Rec rec, Random rnd) {
        try {
            Random r = rnd != null ? rnd : new Random();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            cv.drawColor(Color.WHITE);
            drawStreetGrid(cv, w, 0, h, r);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
            bmp.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
