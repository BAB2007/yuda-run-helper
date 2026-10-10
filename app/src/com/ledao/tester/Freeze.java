package com.ledao.tester;

import java.util.Locale;

/**
 * Freeze —— 「这一场跑步被系统冻住了多久」的账本（v1.0.30，docs/62）。
 *
 * <h3>它要解决什么</h3>
 * 跑步循环是靠 {@code Thread.sleep(计划间隔)} 推进的，而配速是拿**墙上时钟**算的。
 * 国产 ROM 对"没放开后台"的应用会**整进程冻结**（不是杀）：前台服务还在、
 * 常驻通知还挂着、进程也还活着，但一条指令都不执行 —— 用户看到的现象就是
 * 「切到别的 App 跑步就停了，只有屏幕停在助手上才跑」，切回来日志中间是空的。
 *
 * <p>一冻，睡眠就"睡过头"：计划睡 6 秒，醒来时墙上时钟已经走了 60 秒。
 * 这 54 秒里模拟推进一寸没动 —— 直接上报就是"用时多了一大截、里程没变"，
 * 配速被拖到服务端 480 s/km 的上限之外，整场白跑。
 *
 * <p>所以冻住的时段必须**从用时和轨迹时间轴里剔掉**：冻住期间本来就没跑，
 * 虚拟时间接着走才对。这个类就管这本账，判断依据是**睡眠睡过头**（见
 * {@link #overshoot}）—— 不是"这一拍算得慢"（那是网络，仍然归 Ledao 的
 * {@code slowTicks} 管）。
 *
 * <p>它同时是"这台机器有没有放开助手后台"的证据：日志里那行
 * 「⚠ 系统把助手冻住了 X 秒」就是指纹。
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
                    "      ⚠ 系统把助手冻住了 %.1f 秒（第 %d 次）—— 这段时间不算用时、"
                  + "也不进轨迹时间轴，配速不会被拖坏；"
                  + "想让后台不被冻，点首页「🔋 后台运行设置」",
                    over / 1000.0, times);
        return null;
    }

    /** 收尾那行总结；一次都没冻过就返回 null（不打扰） */
    public String summary() {
        if (times == 0) return null;
        return String.format(Locale.US,
                "      ★ 本场被系统冻了 %d 次、共 %.1f 秒（最长一次 %.1f 秒）——"
              + " 已经全部从用时里剔除。冻住 = 这台机器没放开助手的后台，"
              + "见首页「🔋 后台运行设置」（可做 90 秒后台体检）",
                times, ms / 1000.0, maxMs / 1000.0);
    }

    /**
     * ★ 虚拟时钟：把"已经冻掉的时间"从真实时刻里减掉。
     *
     * <p>写进轨迹时间轴、打卡时刻、{@code end_time} / {@code used_time} 的都是它。
     * 这样冻过的场次在服务端读起来仍然是一条**连续**的轨迹、一个正常的配速，
     * 而不是"多出一段几十秒的空档 + 一个被拖慢的配速"。
     */
    public long virtualAt(long wallMs) {
        return wallMs - ms;
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
