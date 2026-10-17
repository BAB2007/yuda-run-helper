package com.ledao.tester;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * UiKit —— 并发版的界面零件（全部照 app2 的做法手写，没有一行 XML 布局）。
 *
 * <p>抄的是 app2 {@code MainActivity} 里那几个用了很久的小工具
 * （{@code mkBtn / label / round / weight / dp}），只是拆出来给四个页面共用 ——
 * 目的只有一个：**新页面和原版看起来是同一个软件**。
 */
public final class UiKit {

    private UiKit() { }

    // ==================================================================
    //  基础零件
    // ==================================================================
    public static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    /**
     * 按钮：颜色记在 tag 上（重涂时按它），字号按 sp。
     *
     * <p>★ 2026-10-17 Q 版改版：所有按钮一律**胶囊形**，文字颜色按底色亮度自动选
     * （浅底用暖褐字、深底用白字）—— 这样几十处老调用点不用改就一起变 Q 版。
     */
    public static Button btn(Activity a, String text, String color, float size) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTag(color);
        b.setBackgroundColor(Color.TRANSPARENT);
        paintBtn(b, color);
        b.setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 8));
        b.setMinHeight(dp(a, 38));
        b.setMinimumHeight(dp(a, 38));
        try { b.setStateListAnimator(null); } catch (Throwable ignore) { }
        return b;
    }

    public static TextView label(Activity a, String text, float size) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(size);
        return t;
    }

    /** 重涂一个按钮（原色留在 tag 上 —— 和 app2 的 paintBtn 一个规矩；Q 版：保持胶囊形）。 */
    public static void paintBtn(Button b, String color) {
        if (b == null || color == null) return;
        b.setTag(color);
        try {
            int c = Color.parseColor(color);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setColor(c);
            Activity a = (Activity) b.getContext();
            g.setCornerRadius(dp(a, Q.R_PILL));
            b.setBackground(g);
            b.setTextColor(Q.on(c));
        } catch (Throwable ignore) { }
    }

    /** 圆角卡片底（半透明由调用方给颜色，和 app2 的 panel() 一套）。 */
    public static GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    public static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 一张卡片（Q 版：大圆角 + 淡描边 + 间距）。 */
    public static LinearLayout card(Activity a, int fill, int stroke) {
        LinearLayout c = col(a);
        c.setBackground(round(fill, stroke, dp(a, Q.R_CARD)));
        c.setPadding(dp(a, 13), dp(a, 11), dp(a, 13), dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 10);
        c.setLayoutParams(lp);
        return c;
    }

    public static void margin(View v, int l, int t, int r, int b) {
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
        m.leftMargin = l;
        m.topMargin = t;
        m.rightMargin = r;
        m.bottomMargin = b;
    }

    // ==================================================================
    //  小图：头像缩略图 / 路线缩略图（总信息库那一屏用）
    // ==================================================================

    /** 头像缩略图（解码时就缩放，别把 8 MB 的原图整个读进内存）。 */
    public static Bitmap faceThumb(File f, int sizePx) {
        try {
            if (f == null || !f.exists() || f.length() == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int scale = 1;
            while (o.outWidth / scale > sizePx && o.outHeight / scale > sizePx) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 路线缩略图：只画折线（不拉地图瓦片 —— 列表里几十张图，拉瓦片会把网络打满）。 */
    public static Bitmap routeThumb(List<double[]> pts, int w, int h, int line, int bg) {
        try {
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.drawColor(bg);
            if (pts == null || pts.size() < 2) return bm;
            double minLat = 1e9, maxLat = -1e9, minLon = 1e9, maxLon = -1e9;
            for (double[] p : pts) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            double dLat = Math.max(1e-9, maxLat - minLat), dLon = Math.max(1e-9, maxLon - minLon);
            double pad = 0.06;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, w / 60f));
            p.setColor(line);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            for (int i = 0; i < pts.size(); i++) {
                double[] q = pts.get(i);
                float x = (float) ((q[1] - minLon) / dLon * (1 - 2 * pad) * w + pad * w);
                float y = (float) ((maxLat - q[0]) / dLat * (1 - 2 * pad) * h + pad * h);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, p);
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一行「键：值」，键灰、值正。 */
    public static LinearLayout kv(Activity a, String k, String v, int keyColor, int valColor) {
        LinearLayout r = row(a);
        TextView kk = label(a, k, 11.5f);
        kk.setTextColor(keyColor);
        kk.setMinWidth(dp(a, 62));
        TextView vv = label(a, v == null ? "-" : v, 11.5f);
        vv.setTextColor(valColor);
        r.addView(kk);
        r.addView(vv, weight());
        return r;
    }
}
