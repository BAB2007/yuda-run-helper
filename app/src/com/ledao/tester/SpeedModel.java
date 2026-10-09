package com.ledao.tester;

import java.util.Random;

/**
 * SpeedModel —— 一条「速度可导、随机、且不越界」的跑步速度曲线。
 *
 * <h3>为什么要重写（2026-10-07 界面优化 #6）</h3>
 * 旧代码是拍脑袋的：
 * <pre>
 *   double v = vMin + rnd.nextDouble() * (vMax - vMin);   // 每一拍独立均匀随机
 * </pre>
 * 每 6 秒在区间里重新抽一次 —— 速度曲线是**阶梯**，相邻两拍可以差
 * 1.5 m/s，加速度能到 0.25 m/s² 且是**跳变**（不连续），一眼就是机器生成的。
 *
 * <h3>定标依据：真机 2026-09-29 17:39 那次**计分成功**的校园跑</h3>
 * 数据：{@code work/tracks_plain/2026-09-29_17-42.RESPONSE_CBC.txt}（336 点 / 2688 m / 2.53 km）。
 * {@code scripts/ref_speed.py} 实测：
 * <pre>
 *   上报速度 s (m/s)   p5 1.20  p25 3.66  中位 3.81  p75 4.04  p95 4.42  max 4.67
 *   分布集中在        [3.5,4.5) = 268/336 点 (80%)
 *   相邻点 |Δs|       中位 0.030   p95 0.295   max 1.470  m/s
 *   加速度 |a|        中位 0.01    p95 0.09    max（去掉跳变点）3.08 m/s²
 *   |二阶差分s|       中位 0.0100  p95 0.0600          ← 很小 ⇒ 速度确实是**连续可导**的
 *   相邻点方向        升 106 / 降 82 / 几乎不变 147     ← 不是单调，是缓慢游走
 *   平均采样间隔      2.5 s/点，点距中位 7.67 m
 * </pre>
 * 也就是说真机的速度是一条**缓慢游走的平滑曲线**，不是每段跳一个随机值。
 *
 * <h3>模型</h3>
 * <pre>
 *   v(t) = mid + A1·sin(ω1·t + φ1) + A2·sin(ω2·t + φ2) + A3·sin(ω3·t + φ3)
 * </pre>
 * 正弦和**处处无穷可导**（导数有界），三个频率铺开让曲线看起来不像周期函数：
 * <ul>
 *   <li>ω1 周期 ≈ 整场时长的 0.8~1.4 倍 —— 大尺度起伏（前段慢、后段快）</li>
 *   <li>ω2 周期 ≈ 3.5~6 分钟 —— 中尺度</li>
 *   <li>ω3 周期 ≈ 40~70 秒 —— 小抖动（呼吸/步频级）</li>
 * </ul>
 * 然后再叠两件事：
 * <ol>
 *   <li><b>限幅</b>：投影到 [vLo, vHi]，用软限幅 v = lo + (hi-lo)·(tanh(x)+1)/2，
 *       硬边界绝不越界，且限幅函数本身可导（tanh 光滑）。</li>
 *   <li><b>起停包络</b>：开头 8~14 秒从静止平滑加速（半余弦斜坡，加速度有界
 *       ≈ π·v/(2T)），结尾 10~18 秒平滑减速。真机也是这么收的 ——
 *       09-29 那次从头 1.22 一路升到 3.9，末点掉到 0.66 才停表。</li>
 * </ol>
 * <b>加速度有界</b>：模型里额外做一次逐拍限幅 —— 每拍的速度变化不超过
 * {@code maxAccel × dt}，所以"可导"是**可验证的**，不是嘴上说说。
 * {@link #stats} 会把实测的 |Δs| / 加速度统计打回日志。
 */
public final class SpeedModel {

    private final double vLo, vHi;      // 允许的速度区间（m/s），硬边界
    private final double mid;           // 中心
    private final double a1, a2, a3;
    private final double w1, w2, w3;
    private final double p1, p2, p3;
    /** 每拍允许的最大加速度（m/s²）——「可导」的量化保证 */
    public final double maxAccel;
    private final double startRamp, endRamp;   // 起/停平滑时间（秒）
    private final double vStart, vEnd;         // 起跑瞬间 / 停表瞬间的速度
    /**
     * ★ 中途"刷脸"之后的复苏斜坡（2026-10-10）。
     *
     * <h3>为什么要它</h3>
     * 真机跑到服务端要求的 {@code middle_distance}（我校是 600 m）时会弹出人脸核验，
     * 人得停下来掏手机、举到脸前、等识别 —— 那几十秒**位置不动**，
     * 回看轨迹就是一段灰虚线（真机三份长轨迹里都有 34.8 / 162.7 / 283.7 m 的直线缺口），
     * 之后速度是从接近 0 慢慢爬回巡航的。
     *
     * <p>我们的助手以前是"瞬时"完成核验（发一个 HTTP 就过去了），轨迹上看不出任何痕迹。
     * 现在 {@code Ledao.run()} 会在核验那一刻冻结位置并停顿 9~20 秒，
     * 然后把 {@code resumeT} 告诉速度模型 —— 从那一刻起按
     * {@link #resumeRamp} 秒的半余弦斜坡从 {@link #resumeFrom} 附近爬到巡航速度。
     *
     * <p>{@code resumeT < 0} = 本场没有中途核验，行为与以前完全一致。
     *
     * <p>★ 这三个是**可变字段**，不是 final：{@link #withResume} 只改它们，
     * 不重建模型、不再抽随机数 —— 正弦的振幅/周期/相位仍然是构造时那一组。
     */
    private double resumeT = -1, resumeRamp = 0, resumeFrom = 0;

    /** 统计（跑完打日志用） */
    public double statMin = Double.MAX_VALUE, statMax = -Double.MAX_VALUE;
    public double statMaxAccel = 0, statSumDs = 0;
    public int statN = 0;
    public double statLastV = Double.NaN;

    private SpeedModel(double vLo, double vHi, Random rnd,
                       double startRamp, double endRamp, double vStart, double vEnd) {
        this.vLo = vLo;
        this.vHi = vHi;
        this.mid = (vLo + vHi) / 2.0;
        double half = (vHi - vLo) / 2.0;
        // 三个正弦的振幅加起来不超过半宽的 88% —— 留出余量，软限幅基本不吃掉形状
        this.a1 = half * (0.34 + rnd.nextDouble() * 0.10);
        this.a2 = half * (0.18 + rnd.nextDouble() * 0.09);
        this.a3 = half * (0.05 + rnd.nextDouble() * 0.05);
        // 周期：大 / 中 / 小
        this.w1 = 2 * Math.PI / (520 + rnd.nextDouble() * 460);      // 8.7~16.3 分
        this.w2 = 2 * Math.PI / (190 + rnd.nextDouble() * 170);      // 3.2~6.0 分
        this.w3 = 2 * Math.PI / (38 + rnd.nextDouble() * 34);        // 0.6~1.2 分
        this.p1 = rnd.nextDouble() * Math.PI * 2;
        this.p2 = rnd.nextDouble() * Math.PI * 2;
        this.p3 = rnd.nextDouble() * Math.PI * 2;
        this.maxAccel = 0.55 + rnd.nextDouble() * 0.45;              // 0.55~1.00 m/s²
        this.startRamp = startRamp;
        this.endRamp = endRamp;
        this.vStart = vStart;
        this.vEnd = vEnd;
        this.resumeT = resumeT;
        this.resumeRamp = resumeRamp;
        this.resumeFrom = resumeFrom;
    }

    /**
     * ★ 告诉速度模型「中途刷脸结束的时刻」——之后按复苏斜坡从接近 0 爬到巡航。
     *
     * <p>由 {@code Ledao.run()} 在中途人脸核验做完、位置解冻之后立刻调用。
     * 只写三个字段，**不重建模型、不再抽随机数** —— 正弦那一组参数原样保留。
     *
     * @param tSec    解冻时刻（秒，从起跑算起）
     * @param rampSec 复苏斜坡时长（秒），真机是几十秒级别
     */
    public void withResume(double tSec, double rampSec) {
        if (tSec <= 0 || rampSec <= 0) return;
        this.resumeT = tSec;
        this.resumeRamp = rampSec;
        // 人停下再起步，第一拍就是零点几 m/s
        this.resumeFrom = Math.max(0.15, vLo * 0.10);
    }

    /**
     * 按配速区间建模型。
     *
     * @param paceLoMin 快的一头（分钟/公里，数值小 = 快）
     * @param paceHiMin 慢的一头
     */
    public static SpeedModel forPace(double paceLoMin, double paceHiMin, Random rnd) {
        double vFast = 1000.0 / (paceLoMin * 60.0);
        double vSlow = 1000.0 / (paceHiMin * 60.0);
        /* ★ 2026-10-10：起跑斜坡从「8~14 s、vSlow 的 55%~90%」拉长成「35~65 s、
         *   绝对速度 0.40~0.85 m/s」。
         *
         *   定标依据是真机 09-29 17:39 那次**计分成功**的 2.53 km（336 点）：
         *     第 1 点 1.22 m/s → 第 11 点 2.0~2.8 m/s → 第 37 秒还在 2.5 m/s
         *     → 约 60 秒才稳定在 3.8~4.0 m/s
         *   旧参数下 vSlow≈3.0 时首点就落在 1.2~1.9，且 11 秒就爬到巡航 ——
         *   用户的原话是「最开始跑的时候也应该有低速向高速加速的过程」，
         *   旧参数确实太短了。
         *
         *   ⚠ 绝对速度而不是 vSlow 的比例：配速区间一变（2.78~4.76 m/s），
         *   比例式的起点速度会跟着飘；人的起步速度只跟身体有关，跟"这场跑多快"无关。 */
        double vs = 0.40 + rnd.nextDouble() * 0.45;
        double ve = vSlow * (0.45 + rnd.nextDouble() * 0.30);
        return new SpeedModel(vSlow, vFast, rnd,
                35 + rnd.nextDouble() * 30,        // 起跑加速 35~65 s（真机约 60 s）
                10 + rnd.nextDouble() * 8,         // 收尾减速 10~18 s（真机末点 0.66 m/s）
                vs, ve);
    }

    /** 纯正弦部分（不含起停包络、不含限幅） */
    private double raw(double t) {
        return mid + a1 * Math.sin(w1 * t + p1)
                   + a2 * Math.sin(w2 * t + p2)
                   + a3 * Math.sin(w3 * t + p3);
    }

    /** 软限幅到 [vLo, vHi]：tanh 光滑，导数处处存在 */
    private double clampSoft(double x) {
        double k = (x - mid) / ((vHi - vLo) / 2.0);
        double y = Math.tanh(k);
        return mid + y * (vHi - vLo) / 2.0;
    }

    /** 0→1 的半余弦斜坡（在 [0,T] 上 C¹ 连续） */
    private static double ramp(double t, double T) {
        if (t <= 0) return 0;
        if (t >= T) return 1;
        return 0.5 - 0.5 * Math.cos(Math.PI * t / T);
    }

    /**
     * 时刻 t（秒，从起跑算起）的**目标**速度。
     *
     * <p>包络顺序是「起跑 → 中途刷脸复苏 → 收尾」，后一层覆盖前一层。
     * {@code resumeT < 0} 时中间那一层整段跳过，行为与以前一模一样。
     */
    private double shape(double t, double totalT) {
        double v = clampSoft(raw(t));
        // 起跑包络：从 vStart 平滑升到 v
        double rs = ramp(t, startRamp);
        v = vStart + (v - vStart) * rs;
        // ★ 中途刷脸复苏包络：从 resumeFrom 平滑爬回 v
        /* ⚠ 必须判 t >= resumeT：ramp() 对负参数返回 0，不判的话
         *   **整个解冻之前的速度都会被拉到 resumeFrom** —— 那不是"之后爬升"，
         *   那是把前半场也改成慢跑（自检 S6a 当场抓到）。 */
        if (resumeT > 0 && t >= resumeT) {
            double rr = ramp(t - resumeT, resumeRamp);
            if (rr < 1) v = resumeFrom + (v - resumeFrom) * rr;
        }
        // 收尾包络：从 v 平滑降到 vEnd
        if (totalT > 0) {
            double left = totalT - t;
            if (left < endRamp) {
                double re = ramp(left, endRamp);
                v = vEnd + (v - vEnd) * re;
            }
        }
        return Math.max(0.2, Math.min(vHi, v));
    }

    /** {@link #shape} 的导数（解析式）—— 保留作参考，模拟走中点法即可，用不到它 */
    @SuppressWarnings("unused")
    private double dshape(double t, double totalT) {
        double d = a1 * w1 * Math.cos(w1 * t + p1)
                 + a2 * w2 * Math.cos(w2 * t + p2)
                 + a3 * w3 * Math.cos(w3 * t + p3);
        // 软限幅的导数：sech²(k) · dk
        double k = (raw(t) - mid) / ((vHi - vLo) / 2.0);
        double sech2 = 1.0 / (Math.cosh(k) * Math.cosh(k));
        d *= sech2;
        // 起跑包络
        if (t < startRamp) {
            double r = ramp(t, startRamp);
            double dr = 0.5 * Math.PI / startRamp * Math.sin(Math.PI * t / startRamp);
            double base = clampSoft(raw(t));
            d = d * r + (base - vStart) * dr;
        }
        return d;
    }

    /**
     * 走一拍：已知当前速度、本拍时长上限，返回本拍实际用的速度与时长。
     *
     * 时间方向是「按里程跑」——所以速度只受时间影响，与已跑里程无关，
     * 这样总时长不会失控。
     *
     * @param t      本拍开始时刻（秒）
     * @param dtMax  本拍时长上限（秒）——就是原来的 intervalSec（6 秒）
     * @param totalT 预计总时长（秒），用于收尾减速；&lt;=0 不管
     */
    public double nextSpeed(double t, double dtMax, double totalT) {
        return shape(t + dtMax / 2.0, totalT);      // 取拍中点，等价于中点法积分
    }

    /**
     * ★ 加速度限幅：把「上一拍速度 → 这一拍速度」的变化夹到 maxAccel·dt 以内。
     *
     * 这是「速度可导」的硬保证。真机实测 |Δs| 中位 0.03 m/s、p95 0.30 m/s，
     * 一拍 6 秒对应的加速度中位 0.01、p95 0.09 —— 上限给 1.0 m/s² 已经很宽松，
     * 但足以挡住正弦叠加偶尔产生的大跳。
     *
     * @param want 想要的速度
     * @param last 上一拍的速度（NaN 表示第一拍，不限）
     */
    public double limitAccel(double want, double last, double dt) {
        if (Double.isNaN(last) || dt <= 0) return want;
        double dv = want - last;
        double cap = maxAccel * dt;
        if (dv > cap) dv = cap;
        if (dv < -cap) dv = -cap;
        return last + dv;
    }

    /** 记一笔统计（跑完给日志看） */
    public void record(double v, double dt) {
        statN++;
        if (v < statMin) statMin = v;
        if (v > statMax) statMax = v;
        if (!Double.isNaN(statLastV) && dt > 0) {
            double dv = Math.abs(v - statLastV);
            statSumDs += dv;
            double a = dv / dt;
            if (a > statMaxAccel) statMaxAccel = a;
        }
        statLastV = v;
    }

    /** 一行统计，直接打进日志 —— 让「像不像人」这件事可核对 */
    public String stats(double totalKm, double usedSec) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US,
                "速度统计：%d 拍，%.2f~%.2f m/s（配速 %.1f~%.1f 分/公里）",
                statN, statMin, statMax,
                1000.0 / Math.max(0.1, statMax) / 60.0,
                1000.0 / Math.max(0.1, statMin) / 60.0));
        sb.append(String.format(java.util.Locale.US,
                "；相邻拍 |Δv| 均值 %.3f m/s；最大加速度 %.2f m/s²（上限 %.2f）",
                statN > 1 ? statSumDs / (statN - 1) : 0, statMaxAccel, maxAccel));
        if (usedSec > 1 && totalKm > 0.05) {
            sb.append(String.format(java.util.Locale.US, "；平均配速 %.2f 分/公里",
                    usedSec / 60.0 / totalKm));
        }
        return sb.toString();
    }

    /** 供离线单测：给定时刻的包络后速度（不推进状态） */
    public double peek(double t, double totalT) { return shape(t, totalT); }

    /**
     * 暂停期间「本该跑掉多少米」用的瞬时速度。
     *
     * <p>和 {@link #peek} 的区别：**不看复苏包络那一层**。
     * 刷脸暂停时人站着不动，我们要补的是"这段时间要是没停、按原来的配速该走的距离"，
     * 所以取的是没被复苏斜坡压低的那个速度。
     */
    public double peekTemp(double t, double totalT) {
        double saveT = resumeT, saveR = resumeRamp, saveF = resumeFrom;
        resumeT = -1; resumeRamp = 0; resumeFrom = 0;
        double v = shape(t, totalT);
        resumeT = saveT; resumeRamp = saveR; resumeFrom = saveF;
        return v;
    }

    /** 供离线单测：当前模型的加速度上限 */
    public double accelCap() { return maxAccel; }

    /** 供离线单测：速度区间 */
    public double[] bounds() { return new double[]{vLo, vHi}; }

    /** 末段减速的起点（总时长倒推）——真机是最后 10~20 秒收速 */
    public double endRampSec() { return endRamp; }

    /** 起跑加速段时长（秒）。★ 2026-10-10 起是 35~65 s，外部断言不要再写死 20 */
    public double startRampSec() { return startRamp; }

    /** 巡航段的起点（秒）= 起跑斜坡 + 5 s 余量 —— 和 {@link #selfCheck} 同一个口径 */
    public double cruiseFromSec() { return startRamp + 5.0; }

    /** 中途刷脸复苏的斜坡时长（秒）；0 = 本场没开 */
    public double resumeRampSec() { return resumeRamp; }

    /**
     * ★ 离线自检（给 {@code work/looptest/RouteTest.java} 用，也可在手机上跑）：
     * 按给定的拍长模拟一整场，返回若干统计量，用来验证
     * 「速度可导（|Δv|/dt 有界且连续）」和「不越界」。
     *
     * ⚠ 极值只统计**巡航段**（跳过起跑斜坡与收尾斜坡）：
     *   起停斜坡本来就要低于配速区间下沿 —— 真机 09-29 那次头一点 1.22 m/s、
     *   末点 0.66 m/s，都远低于巡航的 3.8。把它们算进「越界」是在冤枉模型。
     *   斜坡另外由「全程不冲破上沿」那条断言管。
     *
     * <p>★ 2026-10-10：巡航窗口改成**从模型自身的斜坡长度推**。
     * 以前写死 {@code cruiseFrom = 20}，但起跑斜坡已经拉长到 35~65 s ——
     * 写死的话前 40 秒的低速都会被当成"巡航越界"，把好好的模型判红。
     *
     * @param seed    随机种子
     * @param paceLo  快的一头（分/公里）
     * @param paceHi  慢的一头
     * @param totalSec 总时长
     * @param dt      拍长
     * @return {巡航最小速度, 巡航最大速度, 最大加速度, 最大二阶差分, 平均|Δv|, 拍数}
     */
    public static double[] selfCheck(long seed, double paceLo, double paceHi,
                                     double totalSec, double dt) {
        Random r = new Random(seed);
        SpeedModel m = forPace(paceLo, paceHi, r);
        // 斜坡各自留 5 s 余量；总时长太短时退化成"整场不管极值"（不会发生，但别炸）
        final double cruiseFrom = m.startRamp + 5.0;
        double cruiseTo = totalSec - m.endRamp - 5.0;
        if (cruiseTo <= cruiseFrom) cruiseTo = cruiseFrom;
        double v = Double.NaN, lastV = Double.NaN, last2 = Double.NaN;
        double mn = 1e9, mx = -1e9, maxA = 0, maxD2 = 0, sumAbsDv = 0;
        int n = 0;
        for (double t = 0; t < totalSec; t += dt) {
            double want = m.nextSpeed(t, dt, totalSec);
            v = m.limitAccel(want, lastV, dt);
            boolean cruise = t >= cruiseFrom && t <= cruiseTo;
            if (cruise) {
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            if (!Double.isNaN(lastV)) {
                double dv = Math.abs(v - lastV);
                if (cruise) sumAbsDv += dv;
                double a = dv / dt;
                if (a > maxA) maxA = a;
                if (!Double.isNaN(last2)) {
                    double d2 = Math.abs(v - 2 * lastV + last2);
                    if (d2 > maxD2) maxD2 = d2;
                }
            }
            last2 = lastV;
            lastV = v;
            n++;
        }
        return new double[]{mn, mx, maxA, maxD2, n > 1 ? sumAbsDv / (n - 1) : 0, n};
    }
}
