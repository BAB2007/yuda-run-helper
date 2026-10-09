package com.ledao.tester;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AppPrefs —— 全部本地设置（主题 / 运动图标 / 参数模式）。
 *
 * 用一个类集中管，避免 MainActivity 里到处散落 getSharedPreferences。
 */
public class AppPrefs {

    public static final String NAME = "ledao";

    // ---- 主题
    public static final String K_BG = "th_bg";            // 背景色
    public static final String K_FG = "th_fg";            // 字体色
    public static final String K_ACCENT = "th_accent";    // 主色（按钮/状态条）
    public static final String K_BGIMG = "th_bgimg";      // 本地背景图路径（可空）

    // ---- 运动图标
    public static final String K_MARKER = "marker";       // 见下
    public static final String K_CUSTOM = "marker_custom";// 用户上传的图标路径（可空）
    /** ★ #8（2026-10-07）：水滴定位图标里嵌的头像。""=不用头像（纯色水滴） */
    public static final String K_AVATAR = "marker_avatar";

    // ---- 参数
    public static final String K_AUTO = "auto_mode";      // true=系统自动设置
    public static final String K_DIST = "p_dist";         // 手动里程
    public static final String K_PACE_LO = "p_pace_lo";   // 配速下限（分钟/公里，数值小=快）
    public static final String K_PACE_HI = "p_pace_hi";   // 配速上限
    public static final String K_ROUTE_OPEN = "route_open";
    public static final String K_ROUTE_EDIT = "route_edit";// 路线手动调整过的点(JSON)

    // 内置默认配色方案
    public static final String[][] THEMES = {
            {"经典绿", "#F2F5F3", "#1F2A22", "#1B7A4B"},
            {"夜间黑", "#12161A", "#E6EDF3", "#2E9E6B"},
            {"暖阳橙", "#FFF7EF", "#4A3423", "#E06C1B"},
            {"海洋蓝", "#F0F6FF", "#17304D", "#1565C0"},
            {"樱花粉", "#FFF3F7", "#4A2433", "#D81B60"},
    };

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int bg(Context c) { return sp(c).getInt(K_BG, 0xFFF2F5F3); }
    public static int fg(Context c) { return sp(c).getInt(K_FG, 0xFF1F2A22); }
    public static int accent(Context c) { return sp(c).getInt(K_ACCENT, 0xFF1B7A4B); }
    public static String bgImage(Context c) { return sp(c).getString(K_BGIMG, ""); }

    public static boolean autoMode(Context c) { return sp(c).getBoolean(K_AUTO, true); }
    public static float dist(Context c) { return sp(c).getFloat(K_DIST, 2.5f); }
    public static float paceLo(Context c) { return sp(c).getFloat(K_PACE_LO, 4.3f); }  // 4'18"/km
    public static float paceHi(Context c) { return sp(c).getFloat(K_PACE_HI, 5.6f); }  // 5'36"/km

    /** 运动图标描述串：c:#RRGGBB=默认彩色圆点 · a:mNN=内置图片 · f:/path=用户上传 */
    public static String marker(Context c) { return sp(c).getString(K_MARKER, "c:#1B7A4B"); }
    public static String customMarker(Context c) { return sp(c).getString(K_CUSTOM, ""); }

    /** 头像描述串：""=不用头像 · a:v01=内置 · f:/path=用户上传 */
    public static String avatar(Context c) { return sp(c).getString(K_AVATAR, ""); }

    // ==================================================================
    //  ★★★ 配色计算 —— 全部是**纯位运算**，一个 Android API 都不碰。
    //
    //  以前这几行走的是 Color.red()/green()/blue()/argb()/parseColor()。
    //  语义完全一样（Color.red(c) 就是 (c>>16)&0xFF，Color.argb 就是移位或），
    //  但**离线跑不起来** —— android.jar 里那些方法体是 `throw new RuntimeException("Stub!")`，
    //  于是「设了背景图之后卡片到底是几成不透明」这种事只能靠眼睛看，
    //  没法写进回归。改成纯运算之后，work/looptest/AlertTest.java 能直接把
    //  每一档 alpha 断言出来。
    // ==================================================================

    /** 感知亮度（0~255）。onColor / card / panel 全用它，只留一处算法。 */
    public static double lum(int c) {
        return 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
    }

    /** 背景是不是深色 —— 决定卡片该"压白"还是"抬亮" */
    public static boolean dark(int bg) {
        return lum(bg) <= 150;
    }

    /** 取一个跟背景对比度够的深/浅色，用于自动配色 */
    public static int onColor(int bg) {
        return lum(bg) > 150 ? 0xFF1F2A22 : 0xFFE6EDF3;
    }

    /** 把颜色调暗一点（做边框/次级文字）—— alpha 原样保留 */
    public static int darken(int col, float f) {
        return ((col >>> 24) << 24)
                | (((int) (((col >> 16) & 0xFF) * f)) << 16)
                | (((int) (((col >> 8) & 0xFF) * f)) << 8)
                | ((int) ((col & 0xFF) * f));
    }

    /** 按比例混合两个颜色（f=0 取 a，f=1 取 b），结果不透明 */
    public static int blend(int a, int b, float f) {
        return 0xFF000000
                | (((int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f)) << 16)
                | (((int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f)) << 8)
                | ((int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f));
    }

    /**
     * 半透明白/黑，用于对话框一类必须挡住的底（不透明或接近不透明）。
     * 浅色主题 = 80% 白，深色主题 = 13% 白叠加。
     */
    public static int card(int bg) {
        return (dark(bg) ? 0x22FFFFFF : 0xCCFFFFFF);
    }

    /**
     * ★★★ 2026-10-10 用户第 5 条：设了背景照片之后，卡片要**半透明**，别把照片挡死。
     *
     * <p>用户原话：「这个按键还有框有点挡背景，能不能在设置背景后改成半透明的。」
     *
     * <p>三档不透明度是量出来的，不是拍的：
     * <ul>
     *   <li>普通卡片（身份框 / 参数框 / 路线框）—— 有照片时浅色主题降到 62%，
     *       照片明显透得出来，而 11.5sp 的小字在 62% 白底上仍然清楚。</li>
     *   <li>日志框（{@link #panelStrong}）—— 字最多、最密，降到 77% 就到极限了，
     *       再透一点就得上描边才能读，那反而更挡。</li>
     *   <li>深色主题走的是"白色叠加"，所以 Alpha 要往上加而不是往下减 ——
     *       深色主题下把白底降到 9% 会让卡片彻底消失。</li>
     * </ul>
     *
     * @param overPhoto 当前是不是铺了背景照片
     */
    public static int panel(int bg, boolean overPhoto) {
        int a = dark(bg) ? (overPhoto ? 0x42 : 0x30) : (overPhoto ? 0x9E : 0xF0);
        return (a << 24) | 0x00FFFFFF;
    }

    /** 日志框 / 输入框这类"字压在上面"的底 —— 比 panel 实一档，保证可读 */
    public static int panelStrong(int bg, boolean overPhoto) {
        int a = dark(bg) ? (overPhoto ? 0x70 : 0x50) : (overPhoto ? 0xC4 : 0xFA);
        return (a << 24) | 0x00FFFFFF;
    }

    /** 给一个颜色换掉 alpha（f=1 保持原样，0.7 就是七成不透明） */
    public static int alpha(int c, float f) {
        int a = Math.max(0, Math.min(255, Math.round(255 * f)));
        return (c & 0x00FFFFFF) | (a << 24);
    }

    /** #AARRGGBB —— Color.parseColor 认这个格式，而且**保留透明度**（旧代码 &0xFFFFFF 把它丢了） */
    public static String hex(int c) {
        // ★ 必须锁 Locale：%X 在个别语言下会输出非 ASCII 数字，Color.parseColor 直接抛异常
        return String.format(java.util.Locale.US, "#%08X", c);
    }

    public static final String[] PACES = {"3.5", "4.0", "4.5", "5.0", "5.5", "6.0"};
    public static final String[] DISTS = {"2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"};
}
