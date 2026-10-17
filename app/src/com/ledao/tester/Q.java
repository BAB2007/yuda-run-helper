package com.ledao.tester;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Q —— 「Q 版」界面零件（2026-10-17 全面改版的皮肤层）。
 *
 * <p>用户要求界面改成 Q 版：奶油底色、大圆角白卡片、糖果色胶囊按钮、满屏 emoji。
 * 这一层只做**样子**，不做业务 —— 页面代码只管摆控件。
 *
 * <p>和 {@link UiKit} 的分工：
 * <ul>
 *   <li>{@code Q.*} —— 皮肤（颜色、圆角、胶囊按钮、标题、徽章）；</li>
 *   <li>{@link UiKit} —— 零件（dp / row / col / 头像缩略图 / 路线缩略图），
 *       它的 {@code btn/card} 也已经被改成 Q 版形状，老代码不用动。</li>
 * </ul>
 */
public final class Q {

    private Q() { }

    // ==================================================================
    //  糖果色板
    // ==================================================================
    /** 奶油底 */
    public static final int BG = 0xFFFFF7F1;
    /** 卡片白 */
    public static final int PANEL = 0xFFFFFFFF;
    /** 浅奶油（次级块） */
    public static final int PANEL2 = 0xFFFFF1E6;
    /** 主文字（暖褐） */
    public static final int INK = 0xFF4A3B33;
    /** 次文字（灰褐） */
    public static final int INK2 = 0xFFA08D81;
    /** 卡片描边 */
    public static final int STROKE = 0xFFF3E3D7;
    /** 主色：蜜桃橘 */
    public static final int CORAL = 0xFFFF8A5B;
    /** 薄荷 */
    public static final int MINT = 0xFF57C99A;
    /** 天蓝 */
    public static final int SKY = 0xFF63AEF0;
    /** 柠檬 */
    public static final int LEMON = 0xFFFFC94D;
    /** 樱花粉 */
    public static final int PINK = 0xFFFF9DB4;
    /** 葡萄紫 */
    public static final int GRAPE = 0xFFAE97F0;
    /** 危险红 */
    public static final int DANGER = 0xFFEF6C6C;
    /** 灰胶囊 */
    public static final int GRAY = 0xFFEDE1D8;

    /** 胶囊/卡片圆角（dp） */
    public static final int R_CARD = 20;
    public static final int R_PILL = 24;
    public static final int R_TAG = 10;

    // ==================================================================
    //  颜色小工具
    // ==================================================================
    /** 底上应该用黑字还是白字（Q 版里浅色胶囊很多，必须自动选）。 */
    public static int on(int color) {
        double l = 0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return l > 165 ? INK : 0xFFFFFFFF;
    }

    /** 调透明度。 */
    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (((int) (255 * a)) << 24);
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    // ==================================================================
    //  形状
    // ==================================================================
    public static GradientDrawable roundRect(Activity a, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(a, radiusDp));
        if (stroke != 0) g.setStroke(dp(a, 2), stroke);
        return g;
    }

    /** 胶囊底（按钮用）。 */
    public static GradientDrawable pillBg(Activity a, int color) {
        return roundRect(a, color, 0, R_PILL);
    }

    // ==================================================================
    //  零件
    // ==================================================================
    /** Q 版卡片：白底、大圆角、淡描边、四周留白。 */
    public static LinearLayout card(Activity a) {
        LinearLayout c = UiKit.col(a);
        c.setBackground(roundRect(a, PANEL, STROKE, R_CARD));
        c.setPadding(dp(a, 13), dp(a, 11), dp(a, 13), dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 次级块（奶油底，放在白卡片里面）。 */
    public static LinearLayout soft(Activity a) {
        LinearLayout c = UiKit.col(a);
        c.setBackground(roundRect(a, PANEL2, 0, 14));
        c.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 7);
        c.setLayoutParams(lp);
        return c;
    }

    /** 卡片标题：emoji + 字。 */
    public static TextView title(Activity a, String text) {
        TextView t = UiKit.label(a, text, 14.5f);
        t.setTextColor(INK);
        t.setPadding(0, 0, 0, dp(a, 7));
        return t;
    }

    /** 小字说明。 */
    public static TextView sub(Activity a, String text) {
        TextView t = UiKit.label(a, text, 11.5f);
        t.setTextColor(INK2);
        t.setPadding(0, dp(a, 2), 0, dp(a, 3));
        return t;
    }

    public static TextView text(Activity a, String text, float size) {
        TextView t = UiKit.label(a, text, size);
        t.setTextColor(INK);
        return t;
    }

    /**
     * 胶囊按钮。{@code kind} 取色板常量之一（CORAL/MINT/SKY/LEMON/GRAY/DANGER/…）。
     *
     * <p>文字颜色按底色亮度自动选（浅色胶囊用褐字，深色胶囊用白字），
     * 这样柠檬黄这种"白字看不清"的坑不会出现。
     */
    public static Button btn(Activity a, String text, int kind) {
        return btn(a, text, kind, 12.5f);
    }

    public static Button btn(Activity a, String text, int kind, float size) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTag(Integer.valueOf(kind));       // 记原色：repaint 时按它重涂
        b.setBackground(pillBg(a, kind));
        b.setTextColor(on(kind));
        b.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 8));
        b.setMinHeight(dp(a, 38));
        b.setMinimumHeight(dp(a, 38));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(a, 7);
        lp.bottomMargin = dp(a, 3);
        b.setLayoutParams(lp);
        return b;
    }

    /** 一行的按钮（自动带间距、可换行由调用方决定）。 */
    public static LinearLayout row(Activity a, View... vs) {
        LinearLayout r = UiKit.row(a);
        for (View v : vs) r.addView(v);
        return r;
    }

    /** 徽章：小圆角标签。 */
    public static TextView tag(Activity a, String text, int color) {
        TextView t = UiKit.label(a, text, 10.5f);
        t.setTextColor(on(color));
        t.setBackground(roundRect(a, color, 0, R_TAG));
        t.setPadding(dp(a, 8), dp(a, 3), dp(a, 8), dp(a, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(a, 6);
        lp.topMargin = dp(a, 2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 细分割线。 */
    public static View divider(Activity a) {
        View v = new View(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
        lp.topMargin = dp(a, 7);
        lp.bottomMargin = dp(a, 7);
        v.setLayoutParams(lp);
        v.setBackgroundColor(STROKE);
        return v;
    }

    /** 「键  值」一行（Q 版：键灰、值褐、值可着色）。 */
    public static LinearLayout kv(Activity a, String k, String v) {
        return kv(a, k, v, INK);
    }

    public static LinearLayout kv(Activity a, String k, String v, int valColor) {
        LinearLayout r = UiKit.row(a);
        TextView kk = UiKit.label(a, k, 12f);
        kk.setTextColor(INK2);
        kk.setMinWidth(dp(a, 68));
        TextView vv = UiKit.label(a, v == null ? "—" : v, 12f);
        vv.setTextColor(valColor);
        r.addView(kk);
        r.addView(vv, UiKit.weight());
        r.setPadding(0, dp(a, 2), 0, dp(a, 2));
        return r;
    }

    /** 头像（圆角方块 + 淡描边；没有照片就画一个占位 emoji 框）。 */
    public static ImageView avatar(Activity a, File f, int dpSize) {
        ImageView iv = new ImageView(a);
        int s = dp(a, dpSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(a, 9);
        iv.setLayoutParams(lp);
        Bitmap bm = UiKit.faceThumb(f, s);
        if (bm != null) iv.setImageBitmap(bm);
        else {
            iv.setBackground(roundRect(a, PANEL2, STROKE, 16));
            iv.setImageDrawable(null);
        }
        return iv;
    }

    /** 大号统计数字块（账号 / 已跑 / 出口组 …）。 */
    public static LinearLayout stat(Activity a, String num, String label, int color) {
        LinearLayout c = UiKit.col(a);
        c.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);
        TextView n = UiKit.label(a, num, 19f);
        n.setTextColor(color);
        n.setGravity(Gravity.CENTER);
        TextView t = UiKit.label(a, label, 10.5f);
        t.setTextColor(INK2);
        t.setGravity(Gravity.CENTER);
        c.addView(n);
        c.addView(t);
        return c;
    }

    /** 给普通 View 加顶部间距（页面里排版用）。 */
    public static void gap(View v, int dpTop) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).topMargin = dpTop;
        }
    }

    // ==================================================================
    //  弹窗也要 Q 版
    // ==================================================================
    /**
     * 把系统 {@code AlertDialog} 的方角白底换成 Q 版圆角卡片。
     *
     * <p>必须在 {@code show()} **之后**调（show 之前拿不到 Window 的真实装饰视图）。
     * 顺手留一圈内边距 —— 否则标题会贴着圆角。
     */
    public static void style(Activity a, android.app.AlertDialog dlg) {
        if (a == null || dlg == null) return;
        try {
            android.view.Window w = dlg.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(roundRect(a, PANEL, STROKE, R_CARD));
            View v = w.getDecorView();
            int p = dp(a, 8);
            v.setPadding(p, p, p, p);
        } catch (Throwable ignore) { }
    }
}
