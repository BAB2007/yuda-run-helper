package com.ledao.tester;

import java.util.Locale;

/**
 * Freeze —— 「这一场跑步被系统冻住了多久」的账本（v1.0.30，docs/62；v1.0.39 改口径，docs/82）。
 *
 * <h3>它要解决什么</h3>
 * 跑步循环是靠 {@code Thread.sleep(计划间隔)} 推进的，而配速是拿**墙上时钟**算的。
 * 国产 ROM 对"没放开后台"的应用会**整进程冻结**（不是杀）：前台服务还在、
 * 常驻通知还挂着、进程也还活着，但一条指令都不执行 —— 用户看到的现象就是
 * 「切到别的 App 跑步就停了，只有屏幕停在助手上才跑」，切回来日志中间是空的。
 *
 * <p>一冻，睡眠就"睡过头"：计划睡 6 秒，醒来时墙上时钟已经走了 60 秒。
 * 这 54 秒里模拟推进一寸没动。判断依据是**睡眠睡过头**（见 {@link #overshoot}）——
 * 不是"这一拍算得慢"（那是网络，仍然归 Ledao 的 {@code slowTicks} 管）。
 *
 * <h3>★★★ v1.0.39：这个类**不再提供虚拟时钟**</h3>
 * v1.0.30 的做法是 {@code virtualAt(wallMs) = wallMs - ms}：把冻掉的秒数从
 * "用时"里减掉，本地看起来配速正常。但那正是 docs/71 §三.1 点出来的
 * 「时间轴与真实到达不一致」：{@code start_time} 是服务端自己签的，
 * 收尾包什么时候到也是它记的，两边一减就知道真实过了多久 —— 这个谎补不上。
 *
 * <p>现在的口径是「人在跑、机器没记录」：冻住的秒数**照常进用时**，
 * 同时按当时的速度把**路程**补上（{@link #catchUpM}），轨迹上留下一段
 * 没有采样点的直线缺口 —— 和真机 GPS 断档是同一个形状（docs/57 有实测对照）。
 * 于是 {@code distance} / {@code used_time} / {@code end_time} 三者自洽，
 * 而"这一场被冻过"这件事在日志和轨迹上都能如实看出来。
 */
public final class Freeze {

    /**
     * 判定"被冻住了"而不是普通抖动的下限：睡过头超过这么多毫秒才记账。
     *
     * <p>2 秒是量出来的口径 —— 正常的一拍（一次 HTTP + 推进）抖动在几十毫秒，
     * GC 或者一次慢网络也就几百毫秒；整段没被调度才会一次差出好几秒。
     */
    public static final long MIN_MS = 2000;

    private long ms = 0;        // 累计被冻住的毫秒
    private int times = 0;      // 冻了几次
    private long maxMs = 0;     // 最长的一次
    private int logged = 0;     // 已经打过几行日志（节流用）

    /**
     * 记一次"睡过头"。
     *
     * @param over 实际比计划多睡了多久（毫秒）；小于 {@link #MIN_MS} 算普通抖动，不记
     * @return 需要打给用户看的那行日志；不需要打就返回 null（前 3 次 + 之后每 50 次）
     */
    public String add(long over) {
        if (over < MIN_MS) return null;
        ms += over;
        times++;
        if (over > maxMs) maxMs = over;
        logged++;
        if (times <= 3 || times % 50 == 0)
            return String.format(Locale.US,
                    "      ⚠ 系统把助手冻住了 %.1f 秒（第 %d 次）—— 这段时间照常算用时，"
                  + "人还在跑就把路程补回来（轨迹上会留一段没有采样点的直线缺口）；"
                  + "想让后台不被冻，到首页把「🪟 迷你小窗」开上（切走时右上角挂个小窗）",
                    over / 1000.0, times);
        return null;
    }

    /** 收尾那行总结；一次都没冻过就返回 null（不打扰） */
    public String summary() {
        if (times == 0) return null;
        return String.format(Locale.US,
                "      ★ 本场被系统冻了 %d 次、共 %.1f 秒（最长一次 %.1f 秒）——"
              + " 那几段人还在跑：用时如实计入、路程按当时速度补回，"
              + "轨迹上对应几处没有采样点的缺口（真机 GPS 断档同形状）。"
              + "冻住 = 这台机器没放开助手的后台：先把「🪟 迷你小窗」开上"
              + "（多数机器有可见窗口就不冻了），还不行就在那个向导里点第 ④ 个按钮，"
              + "把系统那层自启动全打开",
                times, ms / 1000.0, maxMs / 1000.0);
    }

    /**
     * ★ v1.0.39：冻住的这 overMs 毫秒里，**人应该往前推进多少米**。
     *
     * <p>这就是替代 {@code virtualAt()} 的那个数：不把钟往回拨，而是把路程补上。
     * 补出来的那段**没有采样点**（进程没被调度，传感器/GPS 回调也没触发），
     * 所以在轨迹上是一条直线缺口 —— 真机同样形状（docs/57 §一）。
     *
     * @param v      冻住前的瞬时速度（m/s）；小于 0.2 按 0.2 算（别把"站着"也放大成跑步）
     * @param overMs 睡过头的毫秒数
     * @param roomM  路线上还剩多少米；补不过去就夹住（不然会冲出路线终点）
     * @return 该补的路程（米，0 ~ roomM）
     */
    public static double catchUpM(double v, long overMs, double roomM) {
        if (overMs <= 0 || roomM <= 0) return 0;
        double gap = overMs / 1000.0 * Math.max(0.2, v);
        return Math.min(gap, roomM);
    }

    /** 计划睡 plannedMs、实际睡了 actualMs ⇒ 多睡了多少（可能是负数 = 提前醒） */
    public static long overshoot(long actualMs, long plannedMs) {
        return actualMs - plannedMs;
    }

    public long ms() { return ms; }

    public int times() { return times; }

    public long maxMs() { return maxMs; }

    /** 这一场冻过没有 */
    public boolean frozen() { return times > 0; }

    /** 冻掉的整秒数（界面用它决定要不要提醒用户） */
    public int frozenSec() { return (int) (ms / 1000); }
}
