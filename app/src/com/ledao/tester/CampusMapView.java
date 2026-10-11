package com.ledao.tester;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CampusMapView —— 可缩放 / 平移的校园地图（高德底图 + 打卡点 + 路线 + 位置标注）。
 *
 * 和上一版 RouteView 的区别：那个是「把坐标硬画在白板上」，
 * 这个是真的地图 —— 有街道底图，能捏合放大看细节，能拖到校园任意角落。
 *
 * 坐标：Web Mercator。高德瓦片是按 GCJ-02 切的，而服务端下发的打卡点
 *      本来就是高德坐标系（步道乐跑自己也用高德），所以直接铺上去就对齐，
 *      **不要**做 WGS84->GCJ02 纠偏，那反而会偏 500 米。
 */
public class CampusMapView extends View {

    public interface Listener {
        /** 用户点了「当前位置」标注 */
        void onMarkerClick(double lat, double lon);
        /** 用户在编辑模式下拖动/点了地图 */
        void onMapTap(double lat, double lon);
    }

    private static final double MIN_Z = 13.0, MAX_Z = 19.0;

    private final TileCache tiles;
    private final Paint pTile = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pGrid = new Paint();
    private final Paint pRouteHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRoute = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrace = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 实跑轨迹里的"无效段"：相邻两点超过这么多米就当中间有一段没记到（刷脸暂停） */
    private static final double TRACE_GAP_M = 25.0;
    /** 灰虚线的间隔（屏幕像素）——画 gap 段用 */
    private final android.graphics.DashPathEffect gapDash =
            new android.graphics.DashPathEffect(new float[]{sp(5f), sp(5f)}, 0);
    private final Paint pTraceGap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCpTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCpHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double centerLat = 36.6559, centerLon = 114.5990;
    private double zoom = 16.4;

    private final List<double[]> cps = new ArrayList<>();     // {lat, lon, id}
    private final List<double[]> route = new ArrayList<>();   // {lat, lon}
    private final List<double[]> trace = new ArrayList<>();
    private double curLat, curLon;
    private boolean hasCur = false;

    private String markerSpec = "c:#1B7A4B";

    private boolean editMode = false;
    private boolean userMoved = false;      // 用户手动拖过之后就不再自动跟随

    private Listener listener;

    // 手势
    private float downX, downY, lastX, lastY;
    private long downT;
    private boolean dragging, pinching;
    private float pinchStartDist;
    private double pinchStartZoom;
    private double pinchFocusLat, pinchFocusLon;
    private float lastMarkerX = -999, lastMarkerY = -999;

    public CampusMapView(Context c) {
        super(c);
        tiles = new TileCache(c);
        pGrid.setColor(0x22FFFFFF);
        pGrid.setStrokeWidth(1f);
        pRouteHalo.setColor(0xFFFFFFFF);
        pRouteHalo.setStyle(Paint.Style.STROKE);
        pRouteHalo.setStrokeCap(Paint.Cap.ROUND);
        pRouteHalo.setStrokeJoin(Paint.Join.ROUND);
        pRoute.setColor(0xFF1B7A4B);
        pRoute.setStyle(Paint.Style.STROKE);
        pRoute.setStrokeCap(Paint.Cap.ROUND);
        pRoute.setStrokeJoin(Paint.Join.ROUND);
        pTrace.setColor(0xFFEF6C00);
        pTrace.setStyle(Paint.Style.STROKE);
        pTrace.setStrokeCap(Paint.Cap.ROUND);
        pTrace.setStrokeJoin(Paint.Join.ROUND);
        // 无效段（刷脸暂停留下的缺口）：灰色虚线，和参考截图里那种一致
        pTraceGap.setColor(0xFF9E9E9E);
        pTraceGap.setStyle(Paint.Style.STROKE);
        pTraceGap.setStrokeCap(Paint.Cap.ROUND);
        pTraceGap.setPathEffect(gapDash);
        pCp.setColor(0xFF9AA79E);
        pCpTxt.setColor(0xFF44504A);
        pCpTxt.setTextSize(sp(8f));
        pCpTxt.setTextAlign(Paint.Align.CENTER);
        pCpHalo.setColor(0xFFFFFFFF);
        pHint.setColor(0xCC000000);
        pHint.setTextSize(sp(10f));
        // 路网底纹：半透明的深绿，压在底图上面、路线下面。
        // 路线本身是绿色粗线，底纹太深会抢戏，所以透明度压到 90/255。
        pNet.setColor(Color.parseColor("#1B7A4B"));
        pNet.setAlpha(90);
        pNet.setStyle(Paint.Style.STROKE);
        pNet.setStrokeCap(Paint.Cap.ROUND);
        pNet.setStrokeJoin(Paint.Join.ROUND);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // 保证 drawBitmap 在缩放时不失真
    }

    /** 本次必须经过的 2 个打卡点 —— 着重标注 */
    private final List<double[]> keyPts = new ArrayList<>();
    private final List<String> keyIds = new ArrayList<>();
    private final Paint pKeyRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pKeyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pKeyTxt = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setKeyPoints(List<double[]> keys, List<String> ids) {
        keyPts.clear();
        keyIds.clear();
        if (keys != null) for (double[] p : keys) keyPts.add(new double[]{p[0], p[1]});
        if (ids != null) keyIds.addAll(ids);
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setEditMode(boolean b) { editMode = b; invalidate(); }
    public boolean isEditMode() { return editMode; }
    public double getZoom() { return zoom; }
    public double getCenterLat() { return centerLat; }
    public double getCenterLon() { return centerLon; }
    public boolean hasPosition() { return hasCur; }
    public double getCurLat() { return curLat; }
    public double getCurLon() { return curLon; }

    // ---------------------------------------------------------------- 路网底纹
    /**
     * 把整张路网（1683 街口 / 2871 街段）淡淡地铺在底图上。
     *
     * 这不是装饰 —— 路线是在这张图上**随机游走**出来的，"为什么这条线要绕这么一下"
     * 只有把路网画出来才看得懂。开关在 {@link #setShowNetwork}。
     *
     * 投影后的 Path 会缓存：2871 段每帧重投影没必要，缩放/平移没变就直接复用。
     */
    private float[] netLL;
    private int[] netBreaks;
    private boolean showNet = true;
    private final Paint pNet = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path netPath;
    private double netOx = Double.NaN, netOy = Double.NaN, netZoom = Double.NaN;

    public void setNetwork(float[] ll, int[] breaks) {
        netLL = ll;
        netBreaks = breaks;
        netPath = null;
        invalidate();
    }

    public void setShowNetwork(boolean b) { showNet = b; invalidate(); }

    public boolean isShowNetwork() { return showNet; }

    /** 按当前视口重投影路网（只在 ox/oy/zoom 变了的时候真的重算） */
    private Path networkPath(double ox, double oy) {
        if (netLL == null || netBreaks == null) return null;
        if (netPath != null && ox == netOx && oy == netOy && zoom == netZoom) return netPath;
        Path p = new Path();
        for (int e = 0; e + 1 < netBreaks.length; e++) {
            int a = netBreaks[e], b = netBreaks[e + 1];
            if (b - a < 2) continue;
            p.moveTo(sx(netLL[2 * a + 1], ox), sy(netLL[2 * a], oy));
            for (int i = a + 1; i < b; i++)
                p.lineTo(sx(netLL[2 * i + 1], ox), sy(netLL[2 * i], oy));
        }
        netPath = p;
        netOx = ox; netOy = oy; netZoom = zoom;
        return p;
    }

    // ---------------------------------------------------------------- 数据
    public void setCheckpoints(List<double[]> list) {
        cps.clear();
        if (list != null) for (double[] p : list) cps.add(new double[]{p[0], p[1], p.length > 2 ? p[2] : 0});
        invalidate();
    }

    public void setRoute(List<double[]> r) {
        route.clear();
        if (r != null) for (double[] p : r) route.add(new double[]{p[0], p[1]});
        invalidate();
    }

    public void setMarkerSpec(String spec) {
        markerSpec = spec == null ? "c:#1B7A4B" : spec;
        invalidate();
    }

    public void setProgress(double lat, double lon) {
        setProgress(lat, lon, -1);
    }

    /**
     * ★ 2026-10-10：多带一个速度，实跑轨迹就能按「慢→快」上色。
     *
     * @param speed m/s；&lt;0 表示调用方没给（老调用点），那一段就按中性色画。
     *
     * <h3>为什么要它</h3>
     * 用户发来的参考截图里，某个跑步 App 的轨迹是「绿(慢) → 黄 → 橙 → 红(快)」的渐变色，
     * 而刷脸暂停那一段是**灰色虚线**。我们原来是一条纯橙色实线，看不出快慢、也看不出停顿。
     * 现在颜色跟着每一段的速度走；相邻两点间距 &gt; {@link #TRACE_GAP_M} 米就当"无效段"，
     * 用灰虚线连过去 —— 正好是中途刷脸那几十秒留下的缺口。
     */
    public void setProgress(double lat, double lon, double speed) {
        curLat = lat; curLon = lon; hasCur = true;
        trace.add(new double[]{lat, lon, speed});
        if (trace.size() > 1500) trace.remove(0);
        if (!userMoved) { centerLat = lat; centerLon = lon; }   // 跑步时自动跟随
        invalidate();
    }

    public void clearTrace() {
        trace.clear();
        hasCur = false;
        userMoved = false;
        invalidate();
    }

    /** 定位到某点；z<0 表示保持当前缩放 */
    public void focusOn(double lat, double lon, double z, boolean byUser) {
        centerLat = lat; centerLon = lon;
        if (z > 0) zoom = Math.max(MIN_Z, Math.min(MAX_Z, z));
        userMoved = byUser;
        invalidate();
    }

    /** 带一点动画的定位（用于点击标注跳过去并放大） */
    public void animateTo(double lat, double lon, final double z) {
        final double lat0 = centerLat, lon0 = centerLon, z0 = zoom;
        final long t0 = System.currentTimeMillis();
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                float f = Math.min(1f, (System.currentTimeMillis() - t0) / 420f);
                float e = f * f * (3 - 2 * f);          // smoothstep
                centerLat = lat0 + (lat - lat0) * e;
                centerLon = lon0 + (lon - lon0) * e;
                zoom = z0 + (z - z0) * e;
                userMoved = true;
                invalidate();
                if (f < 1f) h.postDelayed(this, 16);
            }
        });
    }

    public void zoomBy(double dz) {
        zoom = Math.max(MIN_Z, Math.min(MAX_Z, zoom + dz));
        userMoved = true;
        invalidate();
    }

    /** 布局还没量出来时先记下来，等 onSizeChanged 再算 —— 否则会缩到最小级别 */
    private boolean pendingFit = false;

    public void fitRoute() {
        if (getWidth() <= 0 || getHeight() <= 0) { pendingFit = true; return; }
        List<double[]> pts = new ArrayList<>();
        pts.addAll(cps);
        pts.addAll(route);
        if (pts.isEmpty()) return;
        double laMin = 90, laMax = -90, loMin = 180, loMax = -180;
        for (double[] p : pts) {
            laMin = Math.min(laMin, p[0]); laMax = Math.max(laMax, p[0]);
            loMin = Math.min(loMin, p[1]); loMax = Math.max(loMax, p[1]);
        }
        centerLat = (laMin + laMax) / 2;
        centerLon = (loMin + loMax) / 2;
        int w = getWidth(), h = getHeight();
        // 从最大级别往下找第一个装得下的。
        // ★ 留白要够：地图卡片只有 148dp 高，按 0.80 的高度系数算出来的级别太贴边，
        //   实测首屏只能看见一个打卡点标注，另一个被顶出可视区。放到 0.55 才稳。
        double z = MIN_Z;
        for (double t = MAX_Z; t >= MIN_Z; t -= 0.1) {
            double sx = (worldX(loMax, t) - worldX(loMin, t));
            double sy = (worldY(laMin, t) - worldY(laMax, t));
            if (sx <= w * 0.78 && sy <= h * 0.55) { z = t; break; }
        }
        zoom = z;
        userMoved = true;
        android.util.Log.i("LedaoTester", String.format(java.util.Locale.US,
                "[map] fitRoute 点数=%d lat %.5f..%.5f lon %.5f..%.5f -> 中心 %.5f,%.5f z=%.2f 视口 %dx%d",
                pts.size(), laMin, laMax, loMin, loMax, centerLat, centerLon, zoom, getWidth(), getHeight()));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (pendingFit && cps.size() + route.size() > 0) {
            pendingFit = false;
            post(this::fitRoute);
        }
    }

    // ---------------------------------------------------------------- 投影
    private static double sc(double z) { return TileCache.TILE * Math.pow(2, z); }

    private static double worldX(double lon, double z) { return (lon + 180.0) / 360.0 * sc(z); }

    private static double worldY(double lat, double z) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * sc(z);
    }

    private static double lonOf(double wx, double z) { return wx / sc(z) * 360.0 - 180.0; }

    private static double latOf(double wy, double z) {
        double n = Math.PI - 2.0 * Math.PI * wy / sc(z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private float sx(double lon, double ox) { return (float) (worldX(lon, zoom) - ox); }
    private float sy(double lat, double oy) { return (float) (worldY(lat, zoom) - oy); }

    /** 当前缩放下 1 屏幕像素 = 多少米（纬度方向；把经度折算成米时再乘 cos(lat)） */
    private static double metersPerPixel(double z, double lat) {
        double mPerDegLat = 111132.0;
        return mPerDegLat * latSpanOfOnePx(z, lat);
    }
    /** 一个屏幕像素对应多少度纬度（Web 墨卡托） */
    private static double latSpanOfOnePx(double z, double lat) {
        double a = latOf(worldY(lat, z) - 0.5, z);      // 往"下"挪半像素
        double b = latOf(worldY(lat, z) + 0.5, z);      // 再往"上"挪半像素
        return Math.abs(a - b);
    }

    /**
     * 速度 → 颜色。绿(慢) → 黄 → 橙 → 红(快)。
     *
     * <p>分段线性插值，四档颜色和用户发来的那张参考截图一致：
     * 起步 1~2 m/s 是绿的，巡航 3.5~4.5 偏橙红，冲刺更红。
     * {@code v &lt; 0}（调用方没给速度）返回中性橙，和旧版一个观感。
     */
    private static int traceColor(double v, double vFastRef) {
        if (v < 0) return 0xFFEF6C00;
        double t = Math.max(0, Math.min(1, v / Math.max(0.1, vFastRef)));
        final int[] stops = {0xFF3DBE5A, 0xFFC6D93B, 0xFFF5C518, 0xFFFF8A2B, 0xFFE8402A};
        double x = t * (stops.length - 1);
        int i = (int) Math.floor(x);
        if (i >= stops.length - 1) return stops[stops.length - 1];
        double f = x - i;
        int c0 = stops[i], c1 = stops[i + 1];
        int r = (int) (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
        int g = (int) (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
        int b = (int) ((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- 绘制
    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        cv.drawColor(Color.parseColor("#DDE6E0"));

        double ox = worldX(centerLon, zoom) - w / 2.0;
        double oy = worldY(centerLat, zoom) - h / 2.0;

        // ---- 底图瓦片
        int zi = (int) Math.floor(zoom);
        double ratio = Math.pow(2, zoom - zi);
        double tileSpan = TileCache.TILE * ratio;
        int n = 1 << zi;
        // 可视区域左上角在 zi 级别下的坐标
        double leftZ = worldX(lonOf(ox, zoom), zi);
        double topZ = worldY(latOf(oy, zoom), zi);
        int tx0 = (int) Math.floor(leftZ / TileCache.TILE);
        int ty0 = (int) Math.floor(topZ / TileCache.TILE);
        int tx1 = (int) Math.floor((leftZ + w / ratio) / TileCache.TILE);
        int ty1 = (int) Math.floor((topZ + h / ratio) / TileCache.TILE);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (tx < 0 || ty < 0 || tx >= n || ty >= n) continue;
                Bitmap b = tiles.peek(zi, tx, ty);
                float dx = (float) (tx * TileCache.TILE * ratio - ox);
                float dy = (float) (ty * TileCache.TILE * ratio - oy);
                if (b != null) {
                    cv.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()),
                            new RectF(dx, dy, (float) (dx + tileSpan), (float) (dy + tileSpan)), pTile);
                } else {
                    cv.drawRect(dx, dy, (float) (dx + tileSpan), (float) (dy + tileSpan), pGrid);
                    final int fz = zi, fx = tx, fy = ty;
                    tiles.request(fz, fx, fy, this::invalidate);
                }
            }
        }

        // ---- 路网底纹（路线就是在这张图上走出来的）
        // 缩得太小时整张校园只有几十像素，2871 段会糊成一坨、还白费一次重投影。
        if (showNet && zoom >= 13.5) {
            Path np = networkPath(ox, oy);
            if (np != null) {
                pNet.setStrokeWidth(dp(1.1f));
                cv.drawPath(np, pNet);
            }
        }

        // ---- 主路线
        if (route.size() >= 2) {
            pRouteHalo.setStrokeWidth(dp(7));
            pRoute.setStrokeWidth(dp(3.2f));
            Path p = new Path();
            for (int i = 0; i < route.size(); i++) {
                float x = sx(route.get(i)[1], ox), y = sy(route.get(i)[0], oy);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            cv.drawPath(p, pRouteHalo);
            cv.drawPath(p, pRoute);
        }

        // ---- 实跑轨迹：按速度上色（绿=慢 → 黄 → 橙 → 红=快），缺口画灰虚线
        if (trace.size() >= 2) {
            /* 把"屏幕像素 → 米"算出来，才能判两点之间是不是"没记到的缺口"。
             * 纬度的米/像素是常数；经度要乘 cos(lat)。 */
            double mPerPx = metersPerPixel(zoom, centerLat);
            double gapPx = TRACE_GAP_M / Math.max(1e-6, mPerPx);
            pTrace.setStrokeWidth(dp(3f));
            pTraceGap.setStrokeWidth(dp(2.4f));
            // 快慢归一化的参考速度：真机巡航 3.5~4.5 m/s，取 4.6 当"红"
            final double vFastRef = 4.6;
            for (int i = 1; i < trace.size(); i++) {
                double[] a = trace.get(i - 1), b2 = trace.get(i);
                float x0 = sx(a[1], ox), y0 = sy(a[0], oy);
                float x1 = sx(b2[1], ox), y1 = sy(b2[0], oy);
                float dxp = x1 - x0, dyp = y1 - y0;
                if (Math.hypot(dxp, dyp) > gapPx) {
                    cv.drawLine(x0, y0, x1, y1, pTraceGap);      // 无效段
                    continue;
                }
                double vv = b2.length > 2 ? b2[2] : -1;
                if (vv >= 0) pTrace.setColor(traceColor(vv, vFastRef));
                cv.drawLine(x0, y0, x1, y1, pTrace);
            }
        }

        // ---- 打卡点
        pCpHalo.setStrokeWidth(dp(1.6f));
        pCpHalo.setStyle(Paint.Style.STROKE);
        for (double[] c : cps) {
            float x = sx(c[1], ox), y = sy(c[0], oy);
            if (x < -40 || y < -40 || x > w + 40 || y > h + 40) continue;
            cv.drawCircle(x, y, dp(4.5f), pCpHalo);
            cv.drawCircle(x, y, dp(3.2f), pCp);
        }
        for (double[] c : cps) {
            float x = sx(c[1], ox), y = sy(c[0], oy);
            if (x < -40 || y < -40 || x > w + 40 || y > h + 40) continue;
            String tag = c[2] > 0 ? String.valueOf((int) c[2] % 100) : "";
            if (tag.length() > 0) cv.drawText(tag, x, y - dp(7f), pCpTxt);
        }

        // ---- 本次必须经过的 2 个打卡点：大号彩色圈 + 序号，一眼看清
        for (int i = 0; i < keyPts.size(); i++) {
            double[] c = keyPts.get(i);
            float x = sx(c[1], ox), y = sy(c[0], oy);
            if (x < -60 || y < -60 || x > w + 60 || y > h + 60) continue;
            int col = (i == 0) ? 0xFFD32F2F : 0xFF1565C0;
            pKeyRing.setColor(0x55000000);
            pKeyRing.setStyle(Paint.Style.STROKE);
            pKeyRing.setStrokeWidth(dp(2.5f));
            cv.drawCircle(x, y, dp(13f), pKeyRing);
            pKeyRing.setColor(0xFFFFFFFF);
            cv.drawCircle(x, y, dp(11.5f), pKeyRing);
            pKeyFill.setColor(col);
            cv.drawCircle(x, y, dp(9.5f), pKeyFill);
            pKeyTxt.setColor(0xFFFFFFFF);
            pKeyTxt.setTextSize(sp(11f));
            cv.drawText(String.valueOf(i + 1), x, y + sp(4f), pKeyTxt);
        }

        // ---- 当前位置标注（可点击）
        if (hasCur) {
            float x = sx(curLon, ox), y = sy(curLat, oy);
            lastMarkerX = x; lastMarkerY = y;
            drawMarker(cv, x, y);
        } else {
            lastMarkerX = lastMarkerY = -999;
        }

        if (editMode) {
            cv.drawText("编辑模式：点地图上的位置可把路线端点挪过去", dp(8), h - dp(8), pHint);
        }
    }

    /**
     * ★ 界面优化 #8（2026-10-07）：定位标注改成**水滴状**（地图图钉），
     *   头像嵌在水滴里头。
     *
     * 水滴的**尖端就是当前位置**（和真机地图图钉一样，针尖指坐标），
     * 所以命中判定、点击跳转都不用改 —— lastMarkerX/Y 仍然是坐标本身。
     *
     * 头像来源：{@link #avatarSpec}
     *   · ""（默认）    → 用 markerSpec 的颜色画个实心水滴
     *   · "a:v01"      → assets/avatars/v01.png
     *   · "f:/path"    → 用户自己上传的图
     */
    private void drawMarker(Canvas cv, float x, float y) {
        float r = dp(13f);                 // 水滴「肚子」半径
        float tipY = y;                    // 针尖 = 当前坐标
        float cy = y - r * 1.62f;          // 圆心（往上抬，给水滴尾巴留长度）

        Bitmap face = avatarBitmap();
        int col = 0xFF1B7A4B;
        try {
            if (markerSpec != null && markerSpec.startsWith("c:"))
                col = Color.parseColor(markerSpec.substring(2));
        } catch (Throwable ignore) { }

        pMark.setStyle(Paint.Style.FILL);
        pMark.setAntiAlias(true);

        // 地面投影：让水滴看起来是立在地图上的
        pMark.setColor(0x33000000);
        cv.drawOval(x - r * 0.62f, tipY - dp(2.2f), x + r * 0.62f, tipY + dp(2.2f), pMark);

        // ---- 水滴轮廓：大圆 + 下方收成尖（两段三次贝塞尔）
        Path drop = new Path();
        drop.addCircle(x, cy, r, Path.Direction.CW);
        drop.moveTo(x - r * 0.80f, cy + r * 0.60f);
        drop.cubicTo(x - r * 0.70f, cy + r * 1.30f, x - r * 0.34f, cy + r * 1.78f, x, tipY);
        drop.cubicTo(x + r * 0.34f, cy + r * 1.78f, x + r * 0.70f, cy + r * 1.30f,
                     x + r * 0.80f, cy + r * 0.60f);
        drop.close();

        // 白色外圈（描边）
        pMark.setStyle(Paint.Style.STROKE);
        pMark.setStrokeWidth(dp(3.2f));
        pMark.setColor(0xFFFFFFFF);
        cv.drawPath(drop, pMark);

        // 填充：有头像就贴头像，没有就纯色
        cv.save();
        cv.clipPath(drop);
        if (face != null) {
            RectF dst = new RectF(x - r, cy - r, x + r, cy + r);
            cv.drawBitmap(face, null, dst, pTile);
        } else {
            pMark.setStyle(Paint.Style.FILL);
            pMark.setColor(col);
            cv.drawPath(drop, pMark);
        }
        cv.restore();

        // 头像外再补一圈彩色细环，让它跟主题色呼应
        pMark.setStyle(Paint.Style.STROKE);
        pMark.setStrokeWidth(dp(1.8f));
        pMark.setColor(col);
        cv.drawCircle(x, cy, r - dp(0.9f), pMark);

        // 中心小白点（纯色模式下更像图钉）
        if (face == null) {
            pMark.setStyle(Paint.Style.FILL);
            pMark.setColor(0xFFFFFFFF);
            cv.drawCircle(x, cy - r * 0.10f, dp(3.2f), pMark);
        }
    }

    /** 头像描述串：""=用颜色 · a:v01=内置 · f:/path=自传 */
    private String avatarSpec = "";
    private Bitmap avatarBmp;
    private String avatarLoadedFor = null;

    public void setAvatarSpec(String spec) {
        avatarSpec = spec == null ? "" : spec;
        avatarBmp = null;
        avatarLoadedFor = null;
        invalidate();
    }

    private Bitmap avatarBitmap() {
        if (avatarSpec == null || avatarSpec.length() == 0) return null;
        if (avatarBmp != null && avatarSpec.equals(avatarLoadedFor)) return avatarBmp;
        try {
            if (avatarSpec.startsWith("a:")) {
                // ★ 2026-10-08：内置图片并成一个图库之后，spec 可能是
                //   "a:markers/m01"（带目录）或旧的 "a:v01"（不带目录，落在 avatars/）。
                String p = avatarSpec.substring(2);
                if (p.indexOf('/') < 0) p = "avatars/" + p;
                InputStream in = getContext().getAssets().open(p + ".png");
                avatarBmp = BitmapFactory.decodeStream(in);
                in.close();
            } else if (avatarSpec.startsWith("f:")) {
                avatarBmp = BitmapFactory.decodeFile(avatarSpec.substring(2));
            }
        } catch (Throwable ignore) {
            avatarBmp = null;
        }
        avatarLoadedFor = avatarSpec;
        return avatarBmp;
    }

    // ---------------------------------------------------------------- 手势
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downT = System.currentTimeMillis();
                dragging = false;
                pinching = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinching = true;
                    dragging = false;
                    pinchStartDist = dist(e);
                    pinchStartZoom = zoom;
                    pinchFocusLat = latOf(worldY(centerLat, zoom) + (e.getY(0) + e.getY(1)) / 2.0
                            - getHeight() / 2.0, zoom);
                    pinchFocusLon = lonOf(worldX(centerLon, zoom) + (e.getX(0) + e.getX(1)) / 2.0
                            - getWidth() / 2.0, zoom);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    if (pinchStartDist > 10) {
                        double nz = pinchStartZoom + Math.log(d / pinchStartDist) / Math.log(2);
                        zoom = Math.max(MIN_Z, Math.min(MAX_Z, nz));
                        // 让捏合中心保持不动
                        double fx = (e.getX(0) + e.getX(1)) / 2.0, fy = (e.getY(0) + e.getY(1)) / 2.0;
                        double wx = worldX(pinchFocusLon, zoom) - (fx - getWidth() / 2.0);
                        double wy = worldY(pinchFocusLat, zoom) - (fy - getHeight() / 2.0);
                        centerLon = lonOf(wx + getWidth() / 2.0, zoom);
                        centerLat = latOf(wy + getHeight() / 2.0, zoom);
                        userMoved = true;
                        invalidate();
                    }
                    return true;
                }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (Math.abs(e.getX() - downX) > dp(4) || Math.abs(e.getY() - downY) > dp(4)) dragging = true;
                if (dragging) {
                    centerLon = lonOf(worldX(centerLon, zoom) - dx, zoom);
                    centerLat = latOf(worldY(centerLat, zoom) - dy, zoom);
                    lastX = e.getX(); lastY = e.getY();
                    userMoved = true;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                return true;

            case MotionEvent.ACTION_UP:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                long dt = System.currentTimeMillis() - downT;
                if (!dragging && dt < 400) {
                    float ux = e.getX(), uy = e.getY();
                    // 先判是不是点在「当前位置」标注上
                    if (hasCur && Math.hypot(ux - lastMarkerX, uy - lastMarkerY) < dp(26)) {
                        if (listener != null) listener.onMarkerClick(curLat, curLon);
                        return true;
                    }
                    if (editMode && listener != null) {
                        double ox = worldX(centerLon, zoom) - getWidth() / 2.0;
                        double oy = worldY(centerLat, zoom) - getHeight() / 2.0;
                        listener.onMapTap(latOf(oy + uy, zoom), lonOf(ox + ux, zoom));
                    }
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
