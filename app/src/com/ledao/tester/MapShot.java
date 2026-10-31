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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * MapShot —— 生成「跑步截图」（stopRunV278 的 file_img）
 *
 * <h3>真机那张图到底是什么（2026-10-30 拿 7 张原图逐张核过）</h3>
 * 真机上传的是 {@code files/.screen_shot/<ms>_<uid>.jpg}，540×1059，**整幅就是地图**：
 * 高德底图 + 一条**纯绿**轨迹 + 起点「青色圆点里一个白色箭头」+ 终点「青色圆环里一个白色对勾」
 * + 左下角「高德地图」logo。<b>没有状态栏、没有标题栏、没有成绩卡、没有按钮。</b>
 * 原图在 {@code work/phone_runs/shot_00..05.jpg} 与
 * {@code work/cmp/real/real_official_20261006.jpg}，量出来的事实：
 * <ul>
 *   <li>轨迹核心像素就是 {@code (0,255,0)}，整条**一个颜色**，没有速度渐变、也没有白色描边；</li>
 *   <li>线宽 9~11 px（540 宽下）；</li>
 *   <li>起点圆点 r≈13.5，色 {@code #18BA7E}；终点圆环 r≈17，半透明填充 + 白色对勾；</li>
 *   <li>字节是 <b>JPEG</b>（{@code FFD8…FFD9}），对象名却以 {@code .png} 结尾，
 *       上传时 {@code Content-Type: image/jpeg}。</li>
 * </ul>
 *
 * <h3>v1.0.57：把"整屏界面"全删了（docs/95）</h3>
 * v1.0.39~v1.0.56 画的是「状态栏 + 标题栏 + 地图 + 成绩卡 + 完成按钮」，
 * 那是**照着一台想象中的手机画的**，真机截图里根本没有那些东西；
 * 而且成绩卡那三列数字还被「完成」按钮整块盖住（坐标见 docs/95 §三），等于白画。
 * 越画越像"另一个 App"，取证上一眼可辨 —— 现在改回真机的样子：只画地图。
 *
 * <h3>v1.0.58：相机参数按真机量出来的值（{@link #CAM_ZOOM}）</h3>
 * 以前按"路线 bbox 刚好装下"算缩放，3.2 km 的路线落到 2.86 m/px —— 比真机远 3.3 倍，
 * 看着就是"真机那张被放大了"。现在固定 0.860 m/px（等效 zoom 17.16）、中心压起点，
 * 和真机那张逐像素量出来的相机一模一样；路线跑出画面就让它裁掉（真机也裁）。
 *
 * <h3>它仍然不是"截屏"</h3>
 * 这一条要如实说：真机那张是**系统截屏**（像素级带着该机型的 UI 细节、字体渲染、
 * 状态栏图标），我们是**按同样的版式重绘地图**。观感接近，取证层面不同。
 * 想真正等同，只有"在真机上跑官方 App 时截屏"这一条路 —— 那不在本工具的能力范围。
 */
public class MapShot {

    // 日志接口直接用 Ledao.Log（同包），避免各模块各定义一套接口导致类型不兼容

    private static final String TILE = "https://wprd0%d.is.autonavi.com/appmaptile"
            + "?lang=zh_cn&size=1&style=7&x=%d&y=%d&z=%d";
    private static final int TS = 256;          // 瓦片边长

    /**
     * 真机相机的缩放级别 —— **量出来的**，不是猜的。
     *
     * <p>反推方法（{@code work/cmp/fit_real_scale2.py}）：把 2026-10-06 21:14 那场
     * （2.06 km / 272 点 / 616 s）的上传轨迹 {@code work/1006/run_record__119.PLAINTEXT.json}
     * 和**真机自己上传的那张截图**逐点做 chamfer 拟合：
     * 画面内的 235 个点全部落在绿色轨迹上（命中率 100%），平均残差 0.06 px。
     * 拟合结果：
     * <ul>
     *   <li>比例尺 <b>0.860 m/px</b>（540 px 宽 = 464 m）→ 等效 zoom <b>17.16</b>；</li>
     *   <li>地图中心就是**起点**：首点落在 (267,531)，画布中心 (270,529.5)。</li>
     * </ul>
     * 7 张真机样品里起点标记全部压在画布正中，所以这两条是硬事实。
     */
    static final double CAM_ZOOM = 17.16;

    /** 轨迹点：{纬度, 经度, 速度 m/s} */
    public static class P {
        public double lat, lon, spd;
        public P(double a, double o, double s) { lat = a; lon = o; spd = s; }
    }

    /**
     * 这一场的**数字**（不再印在图上，只进日志）。
     *
     * <p>全部来自 {@link Ledao#run} 的同一个变量：{@code km}/{@code usedSec} 就是
     * stopRunV278 里的 {@code distance}/{@code used_time}，{@code endSec} 就是
     * {@code end_time}。v1.0.57 起图面上一个字都不印（真机那张也没有），
     * 但日志里要能和报文对得上 —— 截图与报文对不上是比"没有截图"更容易被抓的破绽。
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

    /** 时钟：{@code epoch 秒 → "21:49"}（v1.0.57 起不再印在图上，留着给日志/离线测试） */
    public static String clock(long epochSec) {
        if (epochSec <= 0) epochSec = System.currentTimeMillis() / 1000;
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(epochSec * 1000L));
    }

    /** 日期：{@code "2026.10.06"}（同上，只给日志/测试） */
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

    /** 旧签名（保留给老调用方）：没有成绩数字时按 0 处理；图面与新版一致（只画地图） */
    public static byte[] render(List<P> track, int w, int h, Ledao.Log log) {
        return render(track, w, h, null, null, log);
    }

    /**
     * 生成截图（**整幅地图**，与真机那张同构）。失败返回 null（调用方会退化成 {@link #fallback}）。
     *
     * @param w   宽（默认 540，与真机截图一致）
     * @param h   高（默认 1059）
     * @param rec 这一场的数字；可以为 null —— 只影响日志，不影响画面
     * @param rnd 本场随机源：地图缩放级别从这里来 —— 跨场不同模板
     */
    public static byte[] render(List<P> track, int w, int h, Rec rec, Random rnd, Ledao.Log log) {
        if (track == null || track.size() < 2) return null;
        Random r = rnd != null ? rnd : new Random();

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        /* 底色取高德瓦片自己的浅蓝底色 —— 万一有瓦片没拉回来，缺口和周围是同一个色，
         * 不会出现 v1.0.57 那种米白色空白带（真机也不可能出现这种带子） */
        cv.drawColor(Color.parseColor("#E3EDF5"));

        /* ① 底图 + 轨迹 + 起终点（铺满整幅画布） */
        drawMap(cv, track, w, 0, h, r, log);
        /* ② 左下角「高德地图」署名（真机那张这个位置就有） */
        drawAmapLogo(cv, w, h);

        double km = rec != null ? rec.km : 0;
        long used = rec != null ? rec.usedSec : 0;
        int pace = (int) (used / Math.max(0.001, km));

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        /* ★★★ 真机那几张是 **JPEG**（对象名虽以 .png 结尾，字节是 FFD8…FFD9，34~69 KB）。
         *   我们也压 JPEG —— 上传时 Content-Type 用 image/jpeg，与真机逐字段一致。 */
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        bmp.recycle();
        byte[] out = bo.toByteArray();
        if (log != null)
            log.log("      截图生成完成 " + w + "x" + h + "  JPEG " + out.length + " 字节"
                    + "（整幅地图：底图 + 纯绿轨迹 + 起终点，无界面元素；本场 "
                    + fmtDur(used) + " / " + String.format(Locale.US, "%.2f", km) + " km / "
                    + fmtPace(pace) + "）");
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
        /* ★★★ v1.0.58：取景不再"把路线塞进画面"，而是照抄真机自己的相机。
         *
         *   反推（work/cmp/fit_real_scale2.py）：同一场（2026-10-06 21:14，2.06 km /
         *   272 点）的轨迹 work/1006/run_record__119.PLAINTEXT.json 与真机自己上传的
         *   那张截图逐点做 chamfer 拟合 —— 画面内的 235 个点 100% 落在绿色轨迹上、
         *   平均残差 0.06 px，于是两个相机参数是**量出来的**：
         *     · 比例尺 0.860 m/px（540 px 宽 = 464 m，等效 zoom 17.16）
         *     · 地图中心 = **起点**（首点像素 (267,531)，画布中心 (270,529.5)）
         *
         *   以前我们按 bbox 缩放"刚好装下"，3.2 km 的路线落到 2.86 m/px ——
         *   比真机远 3.3 倍，看过去就是"真机那张被放大了"。高德 SDK 的相机是
         *   固定级别跟人的，路线跑大了它**照样裁**（真机那张右边就裁掉 24 px 绿线），
         *   所以这里不再为了"装下"而降低级别：裁掉就裁掉，和真机一个口径。 */
        double zf = CAM_ZOOM;
        int z = (int) Math.ceil(zf - 1e-6);
        float k = (float) Math.pow(2.0, zf - z);                // ≤1：瓦片缩下来画
        if (k > 1f) k = 1f;

        P p0 = track.get(0);                                    // 相机跟着起点（= 跑完时的位置）
        double cx = lon2x(p0.lon, z);
        double cy = lat2y(p0.lat, z);
        double ox = cx - (w / (double) k) / 2.0;
        double oy = cy - (h / (double) k) / 2.0;

        int tx0 = (int) Math.floor(ox / TS), tx1 = (int) Math.floor((ox + w / k) / TS);
        int ty0 = (int) Math.floor(oy / TS), ty1 = (int) Math.floor((oy + h / k) / TS);
        int maxTile = 1 << z;
        Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);          // 缩图要开滤波，否则锯齿
        float ts = TS * k;

        /* 要拉的瓦片清单（连续缩放后一屏会有 30~40 张） */
        ArrayList<int[]> jobs = new ArrayList<int[]>();
        for (int tx = tx0; tx <= tx1; tx++)
            for (int ty = ty0; ty <= ty1; ty++) {
                if (tx < 0 || ty < 0 || tx >= maxTile || ty >= maxTile) continue;
                jobs.add(new int[]{tx, ty});
            }
        int total = jobs.size(), got = 0;

        /* ★ 并发拉：串行 40 张、每张几百毫秒，收尾流程要干等十几秒 —— 等不起。
         *   6 个线程、单张 15 秒上限；哪张没回来就留空，不影响整张图。 */
        Bitmap[] tiles = new Bitmap[total];
        ExecutorService ex = Executors.newFixedThreadPool(6);
        try {
            final int zz = z;
            ArrayList<Future<Bitmap>> fs = new ArrayList<Future<Bitmap>>();
            for (int i = 0; i < total; i++) {
                final int tx = jobs.get(i)[0], ty = jobs.get(i)[1];
                fs.add(ex.submit(new Callable<Bitmap>() {
                    public Bitmap call() { return tile(tx, ty, zz); }
                }));
            }
            for (int i = 0; i < total; i++) {
                try { tiles[i] = fs.get(i).get(15, TimeUnit.SECONDS); }
                catch (Exception ignore) { /* 超时/异常：这一张留空 */ }
            }
        } finally {
            ex.shutdown();
        }

        for (int i = 0; i < total; i++) {
            Bitmap t = tiles[i];
            if (t == null) continue;
            got++;
            float dx = (float) ((jobs.get(i)[0] * TS - ox) * k);
            float dy = (float) (mapTop + (jobs.get(i)[1] * TS - oy) * k);
            cv.drawBitmap(t, null, new RectF(dx, dy, dx + ts, dy + ts), tp);
            t.recycle();
        }
        /* ★ v1.0.58：没拉回来的瓦片**补一遍**（V1.0.57 实测会缺 5~7 张，顶上一条空白带）。
         *   补的时候串行、单张 8 秒 —— 已经等过并发那轮，这里只是兜尾巴。 */
        int miss = total - got;
        if (miss > 0) {
            int tried = 0;
            for (int i = 0; i < total; i++) {
                if (tiles[i] != null) continue;
                if (tried++ >= 3) break;               // 最多补 3 张，别让收尾多等半分钟
                Bitmap t = tile(jobs.get(i)[0], jobs.get(i)[1], z);
                if (t == null) continue;
                got++;
                float dx = (float) ((jobs.get(i)[0] * TS - ox) * k);
                float dy = (float) (mapTop + (jobs.get(i)[1] * TS - oy) * k);
                cv.drawBitmap(t, null, new RectF(dx, dy, dx + ts, dy + ts), tp);
                t.recycle();
            }
        }
        if (log != null)
            log.log("      底图瓦片 " + got + "/" + total + "  z=" + z
                    + " 缩放 " + String.format(Locale.US, "%.3f", k)
                    + "（相机 zoom " + String.format(Locale.US, "%.2f", zf)
                    + " = 0.86 m/px，起点居中，真机实测" + (miss > 0 ? "，补拉 " + miss + " 张" : "") + "）");
        /* 一张瓦片都没拉到：画本场专属的街道网格，别交一张纯色的图（旧代码就是纯色） */
        if (got == 0) drawStreetGrid(cv, w, mapTop, h, rnd);

        /* ★★★ v1.0.58：真机那张的底图**明显发白**（底色 #ECF4F4 一个人占了 60% 的像素）。
         *   把同一份轨迹的两张图逐像素对齐比一遍（work/cmp/fit_veil.py）：
         *     不做处理  平均误差 11.7/255
         *     盖 alpha .44 白幕后 4.99/255      ← 三通道误差同时掉一半
         *   也就是说 App 的地图上本来就压着一层半透明白（收尾弹窗的底衬），
         *   而轨迹和起终点标记画在白幕**之上**，所以它们照样是鲜亮的绿/青。
         *   这里照抄：白幕只盖底图，轨迹、标记、署名都画在后面。 */
        Paint veil = new Paint();
        /* 幕布用**纯白**：三通道合起来的整体均值会偏蓝 8~9（真机底图更绿），
         * 但逐像素误差最小（4.97/255）；换成偏绿的白幕虽然三个通道均值能对上，
         * 逐像素误差反而涨到 7.84 —— 那个色差其实来自"我们的瓦片标签比真机多"，
         * 用色偏去补它只会把整张图带歪。 */
        veil.setColor(0x70FFFFFF);                              // A=112/255≈0.44
        cv.drawRect(0, mapTop, w, mapBot, veil);

        float[] xs = new float[track.size()], ys = new float[track.size()];
        for (int i = 0; i < track.size(); i++) {
            P p = track.get(i);
            xs[i] = (float) ((lon2x(p.lon, z) - ox) * k);
            ys[i] = (float) (mapTop + (lat2y(p.lat, z) - oy) * k);
        }
        Path path = new Path();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) path.lineTo(xs[i], ys[i]);

        /* ★ 轨迹：单一纯绿、无渐变、无白色描边 —— 与真机逐项对齐
         *   （真机核心像素 (0,255,0)，线宽 9~11 px @540）。 */
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(w * 0.0190f);          // 540 → 10.3 px
        line.setColor(Color.rgb(0, 255, 0));
        cv.drawPath(path, line);

        /* ★ v1.0.58：终点**先画、起点后画**，并且两者靠得太近时干脆不画终点。
         *   真机那张轨迹是闭环（首尾相距 2.5 m），画布正中只有那个「青色圆点 + 白箭头」的
         *   起点标记，没有被终点圆环压住；我们把 E 画在收尾点上，闭环时它和 S 重叠成
         *   一个"环里带勾"的怪标记 —— 真机没有这种东西。 */
        float endX = xs[xs.length - 1], endY = ys[ys.length - 1];
        boolean farEnough = Math.hypot(endX - xs[0], endY - ys[0]) > w * 0.045f;   // 540 → 24 px
        if (farEnough) drawEndMark(cv, endX, endY, w);
        drawStartMark(cv, xs[0], ys[0], w);
    }

    /** 起点：淡白光晕 + 青色实心圆 + 白色向上箭头（真机那几张就这样，不是字母 A） */
    private static void drawStartMark(Canvas cv, float x, float y, int w) {
        float r = w * 0.0250f;                     // 540 → 13.5 px
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x55FFFFFF);                    // 圆点外面那圈很淡的白
        cv.drawCircle(x, y, r * 1.55f, p);
        p.setColor(Color.parseColor("#18BA7E"));
        cv.drawCircle(x, y, r, p);
        Path a = new Path();                       // 箭头：尖顶 + 底部中间凹进去
        a.moveTo(x, y - r * 0.58f);
        a.lineTo(x + r * 0.48f, y + r * 0.44f);
        a.lineTo(x, y + r * 0.14f);
        a.lineTo(x - r * 0.48f, y + r * 0.44f);
        a.close();
        p.setColor(Color.WHITE);
        cv.drawPath(a, p);
    }

    /** 终点：淡白光晕 + 半透明青色圆 + 青色圆环 + 白色对勾（真机的收尾标记） */
    private static void drawEndMark(Canvas cv, float x, float y, int w) {
        float r = w * 0.0315f;                     // 540 → 17 px
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x3DFFFFFF);
        cv.drawCircle(x, y, r * 1.28f, p);
        p.setColor(0x4D12A46E);                    // 半透明填充（透出底图）
        cv.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.0046f);             // 环宽 ≈2.5 px
        p.setColor(Color.parseColor("#12A46E"));
        cv.drawCircle(x, y, r, p);
        Path c = new Path();
        c.moveTo(x - r * 0.42f, y + r * 0.02f);
        c.lineTo(x - r * 0.12f, y + r * 0.32f);
        c.lineTo(x + r * 0.44f, y - r * 0.28f);
        p.setColor(Color.WHITE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(w * 0.0069f);
        cv.drawPath(c, p);
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * 左下角「高德地图」署名。真机原图那块（work/cmp/zoom_logo_real.png）是：
     * 一个浅绿小圆角方块 + 一只**比方块大得多**的蓝色纸飞机（尖朝右上、尾巴在左下、
     * 中间一道折口）+ 上方一段浅蓝弧 + 右边灰字「高德地图」。
     * 位置/字号按 540×1059 原图量：方块约 18×18、飞机宽约 35、文字高约 17.6、
     * 整块贴左边距 ≈8 px、文字基线离底 ≈11 px。
     */
    private static void drawAmapLogo(Canvas cv, int w, int h) {
        float u = w * 0.0325f;                    // 一个 u = 17.6 px @540（字高）
        float x0 = w * 0.0155f;
        float yb = h - w * 0.0205f;               // 文字基线

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setColor(Color.parseColor("#C9F2A8"));  // 浅绿圆角方块（飞机后面露出一条边）
        cv.drawRoundRect(new RectF(x0, yb - u * 1.02f, x0 + u * 1.06f, yb),
                u * 0.30f, u * 0.30f, p);

        p.setStyle(Paint.Style.STROKE);           // 上方那一段浅蓝弧
        p.setStrokeWidth(u * 0.16f);
        p.setColor(Color.parseColor("#8ADBF3"));
        cv.drawArc(new RectF(x0 - u * 0.10f, yb - u * 1.55f, x0 + u * 1.35f, yb - u * 0.10f),
                -72f, 108f, false, p);
        p.setStyle(Paint.Style.FILL);

        /* 蓝色纸飞机：两片（上片深蓝、折下那片浅蓝），中间那道接缝就是真机上的折口 */
        Path wing = new Path();
        wing.moveTo(x0 + u * 2.08f, yb - u * 1.19f);    // 尖（右上，出一截框）
        wing.lineTo(x0 + u * 0.09f, yb + u * 0.06f);    // 尾巴（左下）
        wing.lineTo(x0 + u * 1.10f, yb - u * 0.34f);    // 折口
        wing.close();
        p.setColor(Color.parseColor("#1E7FE0"));
        cv.drawPath(wing, p);

        Path fold = new Path();
        fold.moveTo(x0 + u * 2.08f, yb - u * 1.19f);    // 尖
        fold.lineTo(x0 + u * 1.10f, yb - u * 0.34f);    // 折口
        fold.lineTo(x0 + u * 1.85f, yb - u * 0.46f);    // 右
        fold.close();
        p.setColor(Color.parseColor("#5CB8F0"));
        cv.drawPath(fold, p);

        p.setColor(Color.parseColor("#7E868F"));  // 「高德地图」
        p.setTextSize(u);
        p.setTextAlign(Paint.Align.LEFT);
        cv.drawText("高德地图", x0 + u * 2.20f, yb - u * 0.08f, p);
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
                if (d.length < 60) continue;               // 真·错误瓦片（正常空白瓦片也有 200 字节）
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
     * 兜底：照样只画地图，只是底图换成一张本场专属的街道示意图。
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
            cv.drawColor(Color.parseColor("#F3F4F0"));
            drawStreetGrid(cv, w, 0, h, r);
            drawAmapLogo(cv, w, h);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo);
            bmp.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
