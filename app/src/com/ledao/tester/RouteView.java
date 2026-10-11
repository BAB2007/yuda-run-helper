package com.ledao.tester;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * RouteView —— 「跑步路线」示意图（可收起，收起由父布局控制可见性）。
 *
 * 画三层：
 *   ① 跑区全部打卡点（灰色小点，带编号）—— 一眼看出校园打卡点分布
 *   ② 本次要走的主路线（绿色粗线 + 白描边，起点绿/终点红）
 *   ③ 实跑轨迹（橙色）与当前位置（蓝点）
 *
 * 坐标：等距圆柱投影（x 乘 cos(lat)），对校园这种几百米尺度完全够用。
 * 没有地图底图 —— 不联网、不依赖高德 key，纯粹是给人看形状的示意图。
 */
public class RouteView extends View {

    /**
     * 河北工程大学 跑区 2562 的打卡点（point_id 升序，{纬度, 经度}）。
     * 这只是**离线参考**：真跑时以服务端 Run/GetZonePoints 返回的为准，
     * 一旦跑步开始，onRoute 回调会把这些覆盖掉。
     */
    public static final int[] CAMPUS_ID = {
            28987, 28988, 28989, 28990, 28991, 28992, 28993,
            28994, 28996, 28997, 28998, 28999, 29000, 29001,
    };
    public static final double[][] CAMPUS = {
            {36.658704, 114.604740},
            {36.657353, 114.604694},
            {36.657919, 114.604223},
            {36.658888, 114.600222},
            {36.656578, 114.600205},
            {36.653305, 114.600156},
            {36.651242, 114.602991},
            {36.660473, 114.599705},
            {36.657520, 114.593297},
            {36.653020, 114.593914},
            {36.653929, 114.595951},
            {36.653929, 114.598189},
            {36.656379, 114.593276},
            {36.652239, 114.594433},
    };

    private final List<double[]> all = new ArrayList<>();     // 全部打卡点
    private final List<double[]> route = new ArrayList<>();   // 本次路线
    private final List<double[]> trace = new ArrayList<>();   // 实跑轨迹
    private double curLat, curLon;
    private boolean hasCur = false;
    private int hit = 0;
    private String caption = "参考路线 · 尚未开跑";

    private final Paint pBg = new Paint();
    private final Paint pGrid = new Paint();
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDotTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRouteHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRoute = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RouteView(Context c) { this(c, null); }

    public RouteView(Context c, AttributeSet a) {
        super(c, a);
        pBg.setColor(Color.parseColor("#EEF3EF"));
        pGrid.setColor(Color.parseColor("#DCE5DE"));
        pGrid.setStrokeWidth(1f);
        pDot.setColor(Color.parseColor("#9AA79E"));
        pDotTxt.setColor(Color.parseColor("#78857C"));
        pDotTxt.setTextSize(sp(7.5f) * 0.9f);
        pRouteHalo.setColor(Color.WHITE);
        pRouteHalo.setStrokeWidth(dp(5.5f));
        pRouteHalo.setStyle(Paint.Style.STROKE);
        pRouteHalo.setStrokeCap(Paint.Cap.ROUND);
        pRouteHalo.setStrokeJoin(Paint.Join.ROUND);
        pRoute.setColor(Color.parseColor("#1B7A4B"));
        pRoute.setStrokeWidth(dp(2.6f));
        pRoute.setStyle(Paint.Style.STROKE);
        pRoute.setStrokeCap(Paint.Cap.ROUND);
        pRoute.setStrokeJoin(Paint.Join.ROUND);
        pTrace.setColor(Color.parseColor("#EF6C00"));
        pTrace.setStrokeWidth(dp(1.8f));
        pTrace.setStyle(Paint.Style.STROKE);
        pTrace.setStrokeCap(Paint.Cap.ROUND);
        pCur.setColor(Color.parseColor("#1565C0"));
        pTxt.setColor(Color.parseColor("#3C4A40"));
        pTxt.setTextSize(sp(9.5f));
        // 先放一份离线参考路线，没开跑时也不至于空着
        resetToCampusPreview();
    }

    /** 用内置的校园打卡点铺一张参考图（挑 5 个点，和 Ledao.run 的选点公式一致） */
    public void resetToCampusPreview() {
        List<double[]> a = new ArrayList<>();
        for (double[] p : CAMPUS) a.add(new double[]{p[0], p[1]});
        int want = Math.max(2, Math.min(a.size(), 5));
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            int k = want == 1 ? 0 : (int) Math.round(i * (a.size() - 1) / (double) (want - 1));
            r.add(a.get(k));
        }
        setRoute(a, r);
        caption = "参考路线（离线）· " + a.size() + " 个打卡点 · 尚未开跑";
        invalidate();
    }

    public void setRoute(List<double[]> allPoints, List<double[]> r) {
        synchronized (this) {
            all.clear();
            if (allPoints != null) for (double[] p : allPoints) all.add(new double[]{p[0], p[1]});
            route.clear();
            if (r != null) for (double[] p : r) route.add(new double[]{p[0], p[1]});
            trace.clear();
            hasCur = false;
            hit = 0;
        }
        postInvalidate();
    }

    /** 跑步过程中的实时更新 */
    public void setProgress(List<double[]> allPoints, List<double[]> r,
                            double lat, double lon, int hitCount) {
        synchronized (this) {
            if (allPoints != null && !allPoints.isEmpty()
                    && (all.size() != allPoints.size() || !same(all, allPoints))) {
                all.clear();
                for (double[] p : allPoints) all.add(new double[]{p[0], p[1]});
            }
            if (r != null && !r.isEmpty()) {
                route.clear();
                for (double[] p : r) route.add(new double[]{p[0], p[1]});
            }
            curLat = lat;
            curLon = lon;
            hasCur = true;
            hit = hitCount;
            trace.add(new double[]{lat, lon});
            if (trace.size() > 600) trace.remove(0);
        }
        postInvalidate();
    }

    private static boolean same(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (Math.abs(a.get(i)[0] - b.get(i)[0]) > 1e-9
                    || Math.abs(a.get(i)[1] - b.get(i)[1]) > 1e-9) return false;
        return true;
    }

    // ---------------------------------------------------------------- 绘制
    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        cv.drawColor(pBg.getColor());

        List<double[]> pts = new ArrayList<>();
        synchronized (this) {
            pts.addAll(all);
            pts.addAll(route);
            pts.addAll(trace);
            if (hasCur) pts.add(new double[]{curLat, curLon});
        }
        if (pts.isEmpty()) { cv.drawText("暂无路线数据", dp(10), dp(20), pTxt); return; }

        // ① 经纬度 -> 平面（等距圆柱 + cos 纬度修正）
        double lat0 = 0, lon0 = 0;
        for (double[] p : pts) { lat0 += p[0]; lon0 += p[1]; }
        lat0 /= pts.size();
        lon0 /= pts.size();
        double kx = Math.cos(Math.toRadians(lat0));

        double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
        for (double[] p : pts) {
            double x = (p[1] - lon0) * kx, y = (p[0] - lat0);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        double spanX = Math.max(maxX - minX, 1e-6), spanY = Math.max(maxY - minY, 1e-6);
        double pad = dp(16);
        double availW = Math.max(1, w - 2 * pad), availH = Math.max(1, h - 2 * pad - dp(14));
        double s = Math.min(availW / spanX, availH / spanY);
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        double ox = w / 2.0, oy = (h - dp(14)) / 2.0;

        // ② 网格（每 ~1/6 屏一格，纯装饰，帮助判断疏密）
        for (int i = 1; i < 6; i++) {
            float gx = (float) (i * w / 6.0), gy = (float) (i * h / 6.0);
            cv.drawLine(gx, 0, gx, h, pGrid);
            cv.drawLine(0, gy, w, gy, pGrid);
        }

        float[] xy = new float[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            xy[2 * i] = (float) (((pts.get(i)[1] - lon0) * kx - cx) * s + ox);
            xy[2 * i + 1] = (float) (-((pts.get(i)[0] - lat0) - cy) * s + oy);
        }

        // ③ 全部打卡点（灰点 + 编号）
        synchronized (this) {
            int nAll = all.size(), nRoute = route.size();
            for (int i = 0; i < nAll; i++) {
                double[] p = all.get(i);
                float x = (float) (((p[1] - lon0) * kx - cx) * s + ox);
                float y = (float) (-((p[0] - lat0) - cy) * s + oy);
                cv.drawCircle(x, y, dp(2.4f), pDot);
                String tag = i < CAMPUS_ID.length ? String.valueOf(CAMPUS_ID[i] % 100) : "";
                if (tag.length() > 0) cv.drawText(tag, x + dp(4), y - dp(3), pDotTxt);
            }

            // ④ 主路线（白描边 + 绿线）
            if (nRoute >= 2) {
                Path path = new Path();
                for (int i = 0; i < nRoute; i++) {
                    double[] p = route.get(i);
                    float x = (float) (((p[1] - lon0) * kx - cx) * s + ox);
                    float y = (float) (-((p[0] - lat0) - cy) * s + oy);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                cv.drawPath(path, pRouteHalo);
                cv.drawPath(path, pRoute);
            }

            // ⑤ 实跑轨迹（橙）
            if (trace.size() >= 2) {
                Path tp = new Path();
                for (int i = 0; i < trace.size(); i++) {
                    double[] p = trace.get(i);
                    float x = (float) (((p[1] - lon0) * kx - cx) * s + ox);
                    float y = (float) (-((p[0] - lat0) - cy) * s + oy);
                    if (i == 0) tp.moveTo(x, y); else tp.lineTo(x, y);
                }
                cv.drawPath(tp, pTrace);
            }

            // ⑥ 起点 / 当前位置
            if (nRoute >= 1) {
                double[] a = route.get(0);
                float x = (float) (((a[1] - lon0) * kx - cx) * s + ox);
                float y = (float) (-((a[0] - lat0) - cy) * s + oy);
                pMark.setColor(Color.parseColor("#2E7D32"));
                cv.drawCircle(x, y, dp(4.5f), pMark);
                cv.drawCircle(x, y, dp(2.0f), pRouteHalo);
            }
            if (nRoute >= 2) {
                double[] b = route.get(nRoute - 1);
                float x = (float) (((b[1] - lon0) * kx - cx) * s + ox);
                float y = (float) (-((b[0] - lat0) - cy) * s + oy);
                pMark.setColor(Color.parseColor("#C62828"));
                cv.drawCircle(x, y, dp(4.0f), pMark);
            }
            if (hasCur) {
                float x = (float) (((curLon - lon0) * kx - cx) * s + ox);
                float y = (float) (-((curLat - lat0) - cy) * s + oy);
                pCur.setAlpha(60);
                cv.drawCircle(x, y, dp(8f), pCur);
                pCur.setAlpha(255);
                cv.drawCircle(x, y, dp(3.5f), pCur);
            }
        }

        // ⑦ 说明文字 + 比例尺
        String cap = caption;
        if (hasCur) cap = "实跑轨迹 · 已打卡 " + hit + "/" + Math.max(1, route.size())
                + " · 全程约 " + String.format(java.util.Locale.US, "%.2f", routeLen()) + " km";
        cv.drawText(cap, dp(8), h - dp(4), pTxt);

        // 比例尺：找一个好看的整数米
        double mPerPx = 1.0 / (s * 111320.0);           // 每像素多少米
        double barM = niceMeters(mPerPx * dp(48));
        float barPx = (float) (barM / mPerPx);
        float bx = w - dp(8) - barPx, by = dp(14);
        cv.drawLine(bx, by, bx + barPx, by, pTxt);
        cv.drawLine(bx, by - dp(3), bx, by + dp(3), pTxt);
        cv.drawLine(bx + barPx, by - dp(3), bx + barPx, by + dp(3), pTxt);
        cv.drawText(barM >= 1000 ? (barM / 1000) + " km" : (int) barM + " m",
                bx, by - dp(4), pDotTxt);
    }

    /** 主路线总长（km） */
    private double routeLen() {
        double m = 0;
        synchronized (this) {
            for (int i = 1; i < route.size(); i++) m += hav(route.get(i - 1), route.get(i));
        }
        return m / 1000.0;
    }

    private static double hav(double[] a, double[] b) {
        double R = 6371000, dLat = Math.toRadians(b[0] - a[0]),
                dLon = Math.toRadians(b[1] - a[1]);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    private static double niceMeters(double m) {
        double[] cand = {50, 100, 200, 300, 500, 1000, 2000, 5000};
        for (double c : cand) if (m <= c) return c;
        return 5000;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
