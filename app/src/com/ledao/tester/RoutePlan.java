package com.ledao.tester;

import java.util.Arrays;
import java.util.HashMap;

/**
 * RoutePlan —— 沿校园街道随机游走出一条「像人跑的」路线。
 *
 * <p>这是 {@code work/route/route_lib.js} 里 {@code generateRoute()} 的 Java 移植版，
 * <b>算法与参数默认值一行没改</b>。改任何一边都要同步另一边，
 * 并重跑 {@code work/route/verify.js} + {@code selftest.js} + {@code javacheck.js}。
 *
 * <h3>为什么不是"打卡点之间找条最短路"</h3>
 * 服务端要 {@code min_distance=2.00km}，而两个打卡点常常只隔四五百米。
 * 纯最短路只有 1.8 km 不达标；为了凑里程在附近来回绕，就会糊成一团
 * （用户截图里那种"打结"）。所以分两段走：
 *
 * <ol>
 *   <li><b>第一段｜去打卡</b>：每步以 {@code pGoal=0.90} 的概率朝"还没打的那个点"走。
 *       这个 0.90 是整条链上最关键的一个数 —— 0.5 的时候会花 2571 m 走完
 *       最短只要 1782 m 的打卡路线，富余里程在乱晃中被耗光，
 *       第二段没事可做只能原地绕。</li>
 *   <li><b>第二段｜凑里程</b>：打完卡后挑一个<b>远处的目标点</b>把游走引过去，
 *       到点了再挑下一个（最多 15 个）。牵引必须用 Dijkstra 的<b>图距离</b>，
 *       不能用直线距离 —— 校园中间有湖，隔着湖直线再近也走不过去，
 *       用直线引导会在岸边来回蹭（实测最热一格被踩 38 遍）。</li>
 * </ol>
 *
 * <h3>拟人化（{@link #humanize}）</h3>
 * <ul>
 *   <li>靠右行驶：每一"趟"一个 0.35~2.1 m 的横向偏移，往返两趟自然分开</li>
 *   <li>三个正弦横向游走：波长 180~420 / 45~95 / 9~22 m —— 大方向漂移 + 换道 + 步态</li>
 *   <li>转角抹圆：半径 1~6 m；转角 &gt; 140° 时跳过（掉头的进出两段是同一条街，
 *       抹圆会把整个掉头压成零半径尖点，实测中位半径 0.55 m = 原地转身）</li>
 *   <li>起点随机偏移 3~12 m，再走到最近的街口 —— 起点不必正好在路口上</li>
 *   <li>末尾两遍 1-2-1 平滑</li>
 * </ul>
 *
 * <h3>输出</h3>
 * 直接给出 {@link RouteGen.Path}（{lat,lon} 折线 + 累计里程），
 * 下游的 {@code Ledao} 跑动循环、地图绘制、上传流程<b>一行都不用改</b>。
 * 配速/计时仍交给原来的 {@link SpeedModel}（那套按真机定标过，
 * route_lib.js 里的 makeTiming 不搬过来）。
 */
public class RoutePlan {

    // ==================================================================
    //  参数（与 route_lib.js 的 DEFAULTS 一一对应）
    // ==================================================================
    public static class Opts {
        public double minLenM = 2000;        // 服务端 min_distance = 2.00 km
        public double maxLenM = 10000;       // 服务端 max_distance = 10.00 km
        public double lengthBias = 1.7;      // 目标里程在 [下限,上限] 里的偏置指数
        public double lengthSpread = 1.55;
        public int maxTries = 8;
        public double lenFloorFactor = 1.10; // 起点里程下限 = minLenM × 这个系数
                                             // （抹圆会缩短 2~7%，留够余量才保证 ≥2.00km）
        public double pGoal = 0.90;          // 每步朝"还没打卡的点"走的概率
        public double pGoalFar = 0.86;       // 打完卡后朝"凑里程目标点"走的概率
        public double pBack = 0.42;          // 掉头权重（允许返程，但不是常态）
        public double reusePow = 2.0;        // 重复走同一条街的惩罚指数
        public int recentWindow = 12;        // 最近 N 段内走过的街，再走要重罚
        public double recentPenalty = 0.10;
        /**
         * ★★ 治打结唯一真正有效的两个旋钮（1000 条实测，见 docs/52 与 route_lib.js 的注释）。
         *
         * <p>旧版 {@code 1/(1+nk)^1.5} 对"第 2 次到同一个街口"只降到 0.35，太软 ——
         * 一条路线能在同一个路口来回穿十几次（P4→P14 那条：节点 #435 被访问 10 次，
         * 506 m 里程全花在 70×60 m 一小块里）。指数提到 4 之后：
         * 糊掉 p50 0.47→0.35%、p90 2.77→2.01%、p99 6.00→5.70%、最糊 9.96→7.52%，
         * 而两两重叠（12.2→12.3%）、覆盖率（99%）、自重叠（5.3→5.0%）全都没退。
         */
        public double nodeReusePow = 4.0;    // 同一个街口反复到的惩罚指数（旧版 1.5）
        public double nodeKnotPow = 2.0;     // 某街口被踩 ≥3 次之后的额外陡罚指数
        public boolean selfAvoid = true;     // 空间自避让（治"打结"）
        public double visR = 60;             // 视作"同一片地"的半径（米）★ 原 40
        public double visPMin = 0.04;        // ★ 原 0.06
        public double visPow = 2.2;          // ★ 原 1.6
        public double startRadiusM = 120;    // 节点密度统计半径
        public int wpMax = 10;               // 一条路线最多挑几个目标点（旧版写死 15）
        public int wpRetryGap = 25;          // 没挑到目标点时，隔多少步再试
        public double startOffsetMinM = 3;   // 起点随机偏移
        public double startOffsetMaxM = 12;
        public double dsResampleM = 1.0;     // 几何重采样间隔
        public boolean wander = true;
        public boolean keepRight = true;
        public boolean roundCorners = true;

        /**
         * 指定目标里程（米）。NaN = 让生成器自己按 lengthBias 抽。
         *
         * <p>App 用这个把「预览看到的里程」和「真跑生成的里程」锁在一起：
         * 预览抽一个目标 → 生成 → 拿真实长度当 planKm 显示 → 开跑时把这个里程传回来。
         * 只落在 [下限, 上限] 内才被采纳 —— 绝不为了迁就一个数去生成里程不合规的路线。
         * ★ 传了提示值时不会消耗那次随机抽样，所以随机序列与不传时不同（这是有意的）。
         */
        public double targetM = Double.NaN;
    }

    public static class Result {
        public boolean ok;
        public String why = "";
        public long seed;
        public RouteGen.Path path;           // 直接喂给现有流程
        public double lengthM;               // 最终折线长度（米）—— 合规判定用的就是这个
        public double lenRawM;               // 游走累计的路网长度（未含抹圆/平滑的影响）
        public double targetM, floorM, ceilM, dShort0;
        public int cpA = -1, cpB = -1, nodeA = -1, nodeB = -1;
        public int startNode = -1, startNodeSampled = -1;
        public int tries = 1;
        public int backtracks;
        public double repeatLenM;
        public int[] edges = new int[0];
        public int[] visitOrder = new int[0];   // 1=A, 2=B，打卡顺序

        public String summary() {
            return String.format(java.util.Locale.US,
                    "%.0f m / %d 点 / 目标 %.0f m / 起点街口 %d / 掉头 %d / 重走 %.0f m",
                    lengthM, path == null ? 0 : path.pts.size(), targetM, startNode,
                    backtracks, repeatLenM);
        }
    }

    // ==================================================================
    //  随机源：mulberry32（与 route_lib.js 逐位相同）
    // ==================================================================
    /**
     * mulberry32。这里有个**必须记住的 Java/JS 差异**：
     * JS 的 {@code x >>> 0} 会把 32 位整数**转成无符号**，而 Java 的
     * {@code int >>> 0} 是**恒等操作**（int 本来就是 32 位有符号，没有"无符号视图"）。
     * 所以最后一步不能照抄 {@code >>> 0}，要显式 {@code & 0xFFFFFFFFL}
     * 再除以 2^32 —— 否则最高位为 1 时结果会变成负数
     * （实测第一个数就出问题：JS 0.62707…，Java 就变成 -0.37292… = 0.62707-1）。
     * 后果是 {@code acc = rng.u() * tot} 为负 → 起点抽样永远选到 pool[0]，
     * 且 {@code Math.pow(负数, 1.7)} = NaN → 目标里程 NaN。
     * work/route/java/PlanCheck.java 里有一项专门盯着这 5 个数。
     */
    public static final class Rng {
        private int a;
        public Rng(long seed) { a = (int) seed; }
        public double u() {
            a = a + 0x6D2B79F5;
            int t = a;
            t = (t ^ (t >>> 15)) * (1 | t);
            t = t + (t ^ (t >>> 7)) * (61 | t) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
        public double range(double lo, double hi) { return lo + (hi - lo) * u(); }
        public boolean chance(double p) { return u() < p; }
    }

    // ==================================================================
    //  Dijkstra 用的二叉堆（与 route_lib.js 的 MinHeap 同一套上下滤）
    // ==================================================================
    static final class MinHeap {
        double[] k = new double[64];
        int[] v = new int[64];
        int n = 0;
        double rk; int rv;                     // pop() 的结果

        void push(double key, int val) {
            if (n == k.length) {
                int c = n * 2;
                k = Arrays.copyOf(k, c); v = Arrays.copyOf(v, c);
            }
            k[n] = key; v[n] = val;
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (k[p] <= k[i]) break;
                double tk = k[p]; k[p] = k[i]; k[i] = tk;
                int tv = v[p]; v[p] = v[i]; v[i] = tv;
                i = p;
            }
        }

        void pop() {
            int last = n - 1;
            rk = k[0]; rv = v[0];
            k[0] = k[last]; v[0] = v[last];
            n = last;
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, m = i;
                if (l < last && k[l] < k[m]) m = l;
                if (r < last && k[r] < k[m]) m = r;
                if (m == i) break;
                double tk = k[m]; k[m] = k[i]; k[i] = tk;
                int tv = v[m]; v[m] = v[i]; v[i] = tv;
                i = m;
            }
        }
    }

    // ==================================================================
    //  可增长的折线（单位：米）
    // ==================================================================
    static final class Pts {
        double[] x = new double[256], y = new double[256];
        int n;
        void add(double px, double py) {
            if (n == x.length) {
                int c = n * 2;
                x = Arrays.copyOf(x, c); y = Arrays.copyOf(y, c);
            }
            x[n] = px; y[n] = py; n++;
        }
        double len() {
            double L = 0;
            for (int i = 1; i < n; i++) {
                double dx = x[i] - x[i - 1], dy = y[i] - y[i - 1];
                L += Math.sqrt(dx * dx + dy * dy);
            }
            return L;
        }
    }

    static double d2(double ax, double ay, double bx, double by) {
        double dx = ax - bx, dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }

    // ==================================================================
    //  按固定弧长重采样，输出等间距点（含首尾）
    // ==================================================================
    static Pts densifyUniform(Pts in, double ds) {
        Pts out = new Pts();
        if (in.n < 2) { for (int i = 0; i < in.n; i++) out.add(in.x[i], in.y[i]); return out; }
        out.add(in.x[0], in.y[0]);
        double acc = 0, curX = in.x[0], curY = in.y[0];
        int i = 1;
        while (i < in.n) {
            double nx = in.x[i], ny = in.y[i];
            double segLen = d2(nx, ny, curX, curY);
            if (segLen < 1e-9) { i++; continue; }
            double need = ds - acc;
            if (segLen >= need) {
                double f = need / segLen;
                curX += (nx - curX) * f;
                curY += (ny - curY) * f;
                out.add(curX, curY);
                acc = 0;
                // 不推进 i —— 同一段里可能还要再切一个点
            } else {
                acc += segLen; curX = nx; curY = ny; i++;
            }
        }
        double lastX = in.x[in.n - 1], lastY = in.y[in.n - 1];
        if (d2(lastX, lastY, out.x[out.n - 1], out.y[out.n - 1]) > 1e-6) out.add(lastX, lastY);
        return out;
    }

    // ==================================================================
    //  入口
    // ==================================================================
    /**
     * @param cpA  打卡点序号 n（1..14，即 GetZonePoints 的顺序）
     * @param cpB  另一个打卡点序号
     * @param seed 整数种子，同种子同结果
     * @return 成功时一定满足 minLenM ≤ 里程 ≤ maxLenM
     */
    public static Result generate(RouteNet G, int cpA, int cpB, long seed, Opts opts) {
        Opts o = opts != null ? opts : new Opts();
        int tries = Math.max(1, o.maxTries);
        Result last = null;
        for (int t = 0; t < tries; t++) {
            long s = (seed + (long) t * 1000003L) & 0xFFFFFFFFL;
            Result r = generateOnce(G, cpA, cpB, o, new Rng(s), s);
            if (r.ok && r.lengthM >= o.minLenM && r.lengthM <= o.maxLenM) { r.tries = t + 1; return r; }
            if (r.ok) {
                Result bad = new Result();
                bad.ok = false;
                bad.seed = s;
                bad.why = String.format(java.util.Locale.US, "里程越界（%.0f m）", r.lengthM);
                bad.lengthM = r.lengthM;
                last = bad;
            } else last = r;
        }
        return last != null ? last : new Result();
    }

    // ==================================================================
    //  走子 —— 游走状态都放这儿，省得用一堆静态字段传出
    // ==================================================================
    private static final class Walker {
        final RouteNet G;
        final Opts o;
        final Rng rng;
        final double[] dA, dB, dMinAt, dens;
        final int nA, nB, N;
        boolean needA, needB;

        // 空间自避让：格子 -> 走过的节点链表
        final HashMap<Integer, Integer> visHead = new HashMap<>();
        final int[] visNext = new int[20005], visNode = new int[20005];
        int visN = 0;

        // 走过的边序列
        final int[] seqE = new int[20005], seqFrom = new int[20005], seqTo = new int[20005];
        int seqN = 0;

        final int[] useCount;
        final int[] nodeUse;
        final int[] recent;
        int recentN = 0;
        final boolean[] inRecent;

        int cur, prev = -1;
        final int startNode;
        double len = 0;

        // 凑里程的目标点
        int waypoint = -1, wpCount = 0, wpRetryAt = 0;
        double[] dW = null;
        double dirAnchorX = Double.NaN, dirAnchorY = Double.NaN;
        final StringBuilder order = new StringBuilder();

        Walker(RouteNet G, Opts o, Rng rng, int nA, int nB, double[] dA, double[] dB,
               double[] dMinAt, double[] dens, int startNode) {
            this.G = G; this.o = o; this.rng = rng;
            this.nA = nA; this.nB = nB; this.N = G.nNodes;
            this.dA = dA; this.dB = dB; this.dMinAt = dMinAt; this.dens = dens;
            this.useCount = new int[G.nEdges];
            this.nodeUse = new int[N];
            this.recent = new int[Math.max(2, o.recentWindow + 2)];
            this.inRecent = new boolean[G.nEdges];
            this.needA = true; this.needB = true;
            this.startNode = startNode;
            if (startNode == nA) { needA = false; order.append("A"); }
            if (startNode == nB) { needB = false; if (order.length() > 0) order.append(","); order.append("B"); }
            if (nA == nB) needB = false;
            this.cur = startNode;
            markVisited(startNode);
        }

        private int cellKey(int id) {
            int cx = (int) Math.floor(G.nodeX[id] / o.visR);
            int cy = (int) Math.floor(G.nodeY[id] / o.visR);
            return cx * 100000 + cy;
        }

        private void markVisited(int id) {
            if (visN >= visNext.length) return;
            int k = cellKey(id);
            Integer h = visHead.get(k);
            visNode[visN] = id;
            visNext[visN] = h != null ? h : -1;
            visHead.put(k, visN);
            visN++;
        }

        /**
         * 候选街口旁边如果已经有走过的街口，就按最近距离降权，权重 ∈ [visPMin, 1]。
         *
         * <p>前面那些惩罚只认"同一条街"，绕一圈换几条街回到原处照样不罚，
         * 于是就有了截图里那种在同一小片地来回绕的疙瘩。这里直接盯空间。
         * 用软权重而不是禁止 —— 掉头、死胡同、以及"里程要求远超最短可行"时仍然走得通。
         */
        double selfAvoid(int u) {
            double puX = G.nodeX[u], puY = G.nodeY[u];
            int ix = (int) Math.floor(puX / o.visR), iy = (int) Math.floor(puY / o.visR);
            double best = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Integer h = visHead.get((ix + dx) * 100000 + (iy + dy));
                    if (h == null) continue;
                    for (int q = h; q >= 0; q = visNext[q]) {
                        int w = visNode[q];
                        if (w == cur || w == prev) continue;
                        double d = d2(G.nodeX[w], G.nodeY[w], puX, puY);
                        if (d < best) best = d;
                    }
                }
            }
            if (best == Double.MAX_VALUE) return 1;
            double t = Math.min(1, best / o.visR);
            return o.visPMin + (1 - o.visPMin) * Math.pow(t, o.visPow);
        }

        double remainingMilestoneDist(int u) {
            double best = Double.MAX_VALUE;
            if (needA) best = Math.min(best, dA[u]);
            if (needB) best = Math.min(best, dB[u]);
            if (Double.isInfinite(best)) best = 0;      // 都打完了 -> 没目标
            return best;
        }

        /**
         * 给富余的里程挑一个远处的目标点，把游走往那边引。
         *
         * <p>只挑"大致朝前"的候选，否则每次凑里程都是"去一趟再原路回来"，
         * 会在同一个路口扇出一排平行线 —— 那也是打结。
         * 方向用<b>锚点法</b>：锚点每推进 120 m 才更新一次。
         * 不能拿"上一个目标点到当前位置"当方向 —— 走到目标点的那一刻两者重合，
         * 方向变成零向量、约束静默失效，下一步就原路折回（这个坑踩过）。
         *
         * <h3>★ 2026-10-09 第二轮治打结：这一段**故意保持原样**</h3>
         *
         * <p>尾部（糊掉 p99 5.6%、最糊 11.6%）的成因找到了，但不在这个函数里：
         * 逐节点打访问日志看到的是「节点 #435 被访问 10 次，506 m 里程全花在一个
         * 70×60 m 的小方块里」。于是按 route_lib.js 那边试了四类改法
         * （真实图距离候选池 / 放宽带子到剩余里程量级 / 把"朝目标"改成按距离加权 /
         * 卡住脱困），**单独或组合都不如原版** —— 带子一放宽，游走每走几百米就能
         * 挑到一个"侧后方"的目标点，于是不停折返，折返制造的打结比治好的更多。
         * 原版这个窄带 + 紧锥体看似笨，实际是在压制折返。
         *
         * <p>真正有效的是 {@link Opts#nodeReusePow} / {@link Opts#nodeKnotPow}
         * 两个惩罚指数（在主循环里），对照数据见 docs/52。
         */
        void pickWaypoint() {
            double pending = target - len;
            if (pending < 220 || wpCount >= o.wpMax) { waypoint = -1; dW = null; return; }
            double pcX = G.nodeX[cur], pcY = G.nodeY[cur];
            double dx = 0, dy = 0;
            if (Double.isNaN(dirAnchorX)) { dirAnchorX = pcX; dirAnchorY = pcY; }
            double ax = pcX - dirAnchorX, ay = pcY - dirAnchorY;
            double dl = Math.sqrt(ax * ax + ay * ay);
            if (dl > 120) { dirAnchorX = pcX; dirAnchorY = pcY; dx = ax / dl; dy = ay / dl; }

            double loD = Math.max(130, pending * 0.30), hiD = Math.max(loD + 80, pending * 0.75);
            int[] pool = new int[N];
            double[] wts = new double[N];
            int np = 0;
            double tot = 0;
            for (int v = 0; v < N; v++) {
                if (Double.isInfinite(dMinAt[v])) continue;
                double vx = G.nodeX[v] - pcX, vy = G.nodeY[v] - pcY;
                double st = Math.sqrt(vx * vx + vy * vy);
                if (st < 1) continue;
                double est = st * 1.32;
                if (est < loD || est > hiD) continue;
                if (dx != 0 || dy != 0) {
                    if ((vx * dx + vy * dy) / st < 0.15) continue;   // 夹角 > 81° 的不要
                }
                pool[np] = v;
                double wv = Math.max(1, dens[v]);
                wts[np] = wv; tot += wv; np++;
            }
            if (np == 0) { waypoint = -1; dW = null; return; }
            double acc = rng.u() * tot;
            int k = 0;
            for (; k < np - 1; k++) { acc -= wts[k]; if (acc <= 0) break; }
            waypoint = pool[k];
            dW = G.dijkstra(waypoint).dist;              // 真正的最短路，不会有局部极小
            wpCount++;
        }

        double target;                                   // 目标里程（由 generateOnce 填）
    }

    // ==================================================================
    //  单次生成
    // ==================================================================
    // ==================================================================
    //  6.1~6.4：从两个打卡点算出「起点 + 目标里程」
    // ==================================================================

    /** {@link #probeStart} 的产物 */
    public static class Probe {
        public boolean ok;
        public String why = "";
        public int cpA = -1, cpB = -1, nA = -1, nB = -1;
        public double distAB, dShort0, Lfloor, Lceil, target, sbd, maxLen;
        public double[] dA, dB, dMinAt, dens;
        public int startNodeSampled = -1, startNode = -1;
        public double startX, startY;
    }

    /**
     * 6.1~6.4：把「两个打卡点 → 起点 + 目标里程」这一段单独抽出来。
     *
     * <p>抽出来是为了<b>能被对拍</b>：work/route/route_lib.js 里同名函数是 Node 侧
     * 的实现，而 {@code generateOnce} 也真的调它 —— 也就是说两端测的都是
     * 自己在跑的那段代码，不是一份会悄悄跑偏的副本。
     * 两端都把这里的量导出来逐位比（work/route/java/PlanCheck.java）。
     *
     * <p>★ 这一段是<b>确定性的</b>：只有加减乘除和一次 rng 抽样，
     * 没有 sin/cos/pow 参与"选择"，所以两端的 startNode 必须完全一致。
     * 改动这里 = 必须同步改 route_lib.js 的 probeStart，然后跑 javacheck。
     */
    public static Probe probeStart(RouteNet G, int cpAn, int cpBn, Rng rng, Opts o) {
        Probe P = new Probe();
        P.cpA = cpAn; P.cpB = cpBn;
        P.maxLen = o.maxLenM;
        int N = G.nNodes;

        // ---- 6.1 两个打卡点（按 GetZonePoints 里的序号 n = 1..14） ----
        int ia = G.indexOfN(cpAn), ib = G.indexOfN(cpBn);
        if (ia < 0 || ib < 0) { P.why = "打卡点没有接到路网上"; return P; }
        if (G.cpNode[ia] < 0 || G.cpNode[ib] < 0) { P.why = "打卡点没有接到路网上"; return P; }
        if (cpAn == cpBn) { P.why = "两个打卡点相同"; return P; }
        P.nA = G.cpNode[ia]; P.nB = G.cpNode[ib];

        // ---- 6.2 两个打卡点的最短路（按源点缓存，1000 条路线也只算十来次） ----
        RouteNet.SP spA = G.dijkstra(P.nA), spB = G.dijkstra(P.nB);
        P.distAB = spA.dist[P.nB];
        if (Double.isInfinite(P.distAB)) { P.why = "两个打卡点在路网上不连通"; return P; }
        P.dA = spA.dist; P.dB = spB.dist;
        P.dMinAt = new double[N];
        for (int i = 0; i < N; i++) P.dMinAt[i] = Math.min(P.dA[i], P.dB[i]);

        // ---- 6.3 起点：密度加权 + 随机偏移 ----
        P.dens = G.nodeDensity(o.startRadiusM);
        int[] pool = new int[N];
        double[] weight = new double[N];
        int np = 0;
        for (int v = 0; v < N; v++) {
            if (Double.isInfinite(P.dMinAt[v])) continue;              // 走不到打卡点
            if (P.distAB + P.dMinAt[v] > P.maxLen * 0.98) continue;    // 起步就超距
            pool[np] = v;
            weight[np] = Math.max(1, P.dens[v]);
            np++;
        }
        if (np == 0) { P.why = "没有满足里程约束的起点"; return P; }
        double tot = 0;
        for (int i = 0; i < np; i++) tot += weight[i];
        int pickV = pool[0];
        double acc = rng.u() * tot;
        for (int i = 0; i < np; i++) { acc -= weight[i]; if (acc <= 0) { pickV = pool[i]; break; } pickV = pool[i]; }
        P.startNodeSampled = pickV;

        double spx = G.nodeX[pickV], spy = G.nodeY[pickV];
        double ang = rng.range(0, Math.PI * 2);
        double rad = rng.range(o.startOffsetMinM, o.startOffsetMaxM);
        P.startX = spx + Math.cos(ang) * rad;
        P.startY = spy + Math.sin(ang) * rad;
        // 「然后到达最近的 node 节点」—— 用真的最近节点，不一定是最先抽到的那个
        int startNode = 0;
        double sbd = Double.MAX_VALUE;
        for (int n = 0; n < N; n++) {
            double dd = d2(G.nodeX[n], G.nodeY[n], P.startX, P.startY);
            if (dd < sbd) { sbd = dd; startNode = n; }
        }
        if (Double.isInfinite(P.dMinAt[startNode])) { startNode = pickV; sbd = rad; }
        P.startNode = startNode;
        P.sbd = sbd;

        // ---- 6.4 目标里程 ----
        P.dShort0 = P.distAB + P.dMinAt[startNode];
        P.Lfloor = Math.max(P.dShort0 * 1.03, o.minLenM * o.lenFloorFactor);
        P.Lceil = Math.min(P.maxLen * 0.96, Math.max(P.Lfloor * o.lengthSpread, P.Lfloor + 350));
        if (P.Lceil < P.Lfloor) P.Lceil = P.Lfloor;
        if (!Double.isNaN(o.targetM)) {
            // App 指定了里程（预览↔真跑对齐）。超出合规区间就夹回来，绝不为了
            // 迁就一个数去生成一条里程不合规的路线 —— 那会让这一局白跑。
            // ★ 这条分支不消耗那次 rng.u()，所以随机序列与自动抽时不同（在有意的）。
            P.target = clamp(o.targetM, P.Lfloor, P.Lceil);
        } else {
            P.target = P.Lfloor + (P.Lceil - P.Lfloor) * Math.pow(rng.u(), o.lengthBias);
        }
        P.ok = true;
        return P;
    }

    // ==================================================================
    //  单次生成
    // ==================================================================
    static Result generateOnce(RouteNet G, int cpA, int cpB, Opts o, Rng rng, long seed) {
        Result R = new Result();
        R.cpA = cpA; R.cpB = cpB; R.seed = seed;

        Probe pr = probeStart(G, cpA, cpB, rng, o);
        if (!pr.ok) { R.why = pr.why; return R; }
        int nA = pr.nA, nB = pr.nB;
        double[] dA = pr.dA, dB = pr.dB, dMinAt = pr.dMinAt;
        double[] dens = pr.dens;
        double maxLen = pr.maxLen;
        int startNode = pr.startNode;
        double startX = pr.startX, startY = pr.startY, sbd = pr.sbd;
        double target = pr.target;
        R.nodeA = nA; R.nodeB = nB;
        R.startNode = startNode; R.startNodeSampled = pr.startNodeSampled;
        R.dShort0 = pr.dShort0; R.floorM = pr.Lfloor; R.ceilM = pr.Lceil;
        R.targetM = target;

        // ---- 6.5 随机游走 ----
        Walker W = new Walker(G, o, rng, nA, nB, dA, dB, dMinAt, dens, startNode);
        W.target = target;
        walk(G, o, rng, W, maxLen);

        if (W.seqN == 0) { R.why = "游走没有产生任何街段"; return R; }
        if (W.needA || W.needB) { R.why = "里程约束下没能走到两个打卡点"; return R; }
        R.lenRawM = W.len;

        // ---- 6.6 拼出折线 ----
        Pts full = new Pts();
        for (int si = 0; si < W.seqN; si++) {
            int e = W.seqE[si];
            boolean fwd = (G.edgeA[e] == W.seqFrom[si]);
            int o0 = G.edgeOff[e], cnt = G.edgeCnt[e];
            for (int j = 0; j < cnt; j++) {
                int idx = fwd ? o0 + j : o0 + cnt - 1 - j;
                double px = G.ptX[idx], py = G.ptY[idx];
                if (full.n > 0 && d2(full.x[full.n - 1], full.y[full.n - 1], px, py) < 1e-6) continue;
                full.add(px, py);
            }
        }
        // 起点偏移：从 startPt 走到最近街口，再顺着路走
        Pts withHead = new Pts();
        withHead.add(startX, startY);
        if (sbd > 0.5) {
            int nSteps = Math.max(2, Math.min(6, (int) Math.round(sbd / 2)));
            for (int hs = 1; hs < nSteps; hs++) {
                double f2 = hs / (double) nSteps;
                withHead.add(startX + (full.x[0] - startX) * f2, startY + (full.y[0] - startY) * f2);
            }
        }
        for (int i = 0; i < full.n; i++) withHead.add(full.x[i], full.y[i]);

        // ---- 6.7 拟人化 ----
        Pts geo = humanize(G, withHead, W.seqE, W.seqFrom, W.seqTo, W.seqN, rng, o);

        // ---- 6.8 输出 ----
        RouteGen.Path path = new RouteGen.Path();
        double cum = 0;
        for (int i = 0; i < geo.n; i++) {
            if (i > 0) cum += d2(geo.x[i], geo.y[i], geo.x[i - 1], geo.y[i - 1]);
            path.pts.add(G.m2ll(geo.x[i], geo.y[i]));
            path.cum.add(cum);
        }
        path.length = cum;

        R.ok = true;
        R.path = path;
        R.lengthM = cum;
        R.edges = Arrays.copyOf(W.seqE, W.seqN);
        R.backtracks = countBacktracks(W.seqE, W.seqN);
        R.repeatLenM = repeatLength(G, W.seqE, W.seqN);
        R.visitOrder = orderChars(W.order);
        return R;
    }

    /** 游走主循环。走到自然结束（或撞上保护上限）返回。 */
    private static void walk(RouteNet G, Opts o, Rng rng, Walker W, double maxLen) {
        int[] candE = new int[16], candTo = new int[16];
        double[] candW = new double[16], candL = new double[16];
        int[] filtE = new int[16], filtTo = new int[16];
        double[] filtW = new double[16], filtL = new double[16];
        int guard = 0;

        while (guard++ < 20000) {
            boolean allDone = (!W.needA && !W.needB);
            if (allDone && W.len >= W.target) break;
            if (W.len >= maxLen * 0.98 && allDone) break;

            int adOff = G.adjOff[W.cur], adN = G.adjOff[W.cur + 1] - adOff;
            if (adN <= 0) break;

            int nc = 0;
            for (int ai = 0; ai < adN; ai++) {
                int ei = G.adjEdge[adOff + ai];
                int to = G.adjTo(W.cur, ai);
                double el = G.edgeLen[ei];
                if (W.len + el > maxLen * 0.995) continue;          // 不许越界
                double w = 1;
                if (to == W.prev) w *= o.pBack;                     // 掉头：允许，但少
                int k2 = W.useCount[ei];
                if (k2 > 0) w *= 1 / Math.pow(1 + k2, o.reusePow);   // 回头路：允许，降权
                if (W.inRecent[ei]) w *= o.recentPenalty;            // 刚走过的街：重罚
                int nk = W.nodeUse[to];
                if (nk > 0) w *= 1 / Math.pow(1 + nk, o.nodeReusePow);   // 同一个街口反复到：降权
                if (nk >= 3) w *= 1 / Math.pow(nk - 1, o.nodeKnotPow);   // ★ 已经踩烂的街口：额外陡罚
                if (o.selfAvoid) w *= W.selfAvoid(to);               // 空间自避让：治打结
                if (G.adjOff[to + 1] - G.adjOff[to] == 1 && !allDone) w *= 0.35;  // 别扎进死胡同
                candE[nc] = ei; candTo[nc] = to; candW[nc] = w; candL[nc] = el; nc++;
            }
            if (nc == 0) break;

            // 目标牵引：还没打完卡时朝打卡点走；都打完了就朝"远处的凑里程目标点"走
            boolean pullGoal = false;
            double dHere = 0;
            if (!allDone) {
                dHere = W.remainingMilestoneDist(W.cur);
                boolean budgetTight = (W.len + dHere) > W.target * 1.02;
                pullGoal = budgetTight || rng.chance(o.pGoal);
            } else {
                if (W.waypoint >= 0 && W.cur == W.waypoint) {
                    W.pickWaypoint();
                    if (W.waypoint == W.cur) W.waypoint = -1;
                } else if (W.waypoint < 0 && guard >= W.wpRetryAt) {
                    W.wpRetryAt = guard + o.wpRetryGap;
                    W.pickWaypoint();
                }
                if (W.waypoint >= 0 && W.dW != null) {
                    dHere = W.dW[W.cur];
                    pullGoal = rng.chance(o.pGoalFar);
                }
            }
            if (pullGoal) {
                int nf = 0;
                for (int i = 0; i < nc; i++) {
                    double dt = allDone ? W.dW[candTo[i]] : W.remainingMilestoneDist(candTo[i]);
                    if (dt < dHere - 1e-6) {
                        filtE[nf] = candE[i]; filtTo[nf] = candTo[i];
                        filtW[nf] = candW[i]; filtL[nf] = candL[i]; nf++;
                    }
                }
                if (nf > 0) {
                    System.arraycopy(filtE, 0, candE, 0, nf);
                    System.arraycopy(filtTo, 0, candTo, 0, nf);
                    System.arraycopy(filtW, 0, candW, 0, nf);
                    System.arraycopy(filtL, 0, candL, 0, nf);
                    nc = nf;
                }
            }

            double t2 = 0;
            for (int i = 0; i < nc; i++) t2 += candW[i];
            int pick = nc - 1;          // 权重和因浮点误差略小于 rr 时的兜底
            double rr = rng.u() * t2;
            for (int i = 0; i < nc; i++) { rr -= candW[i]; if (rr <= 0) { pick = i; break; } }

            int stepE = candE[pick];
            W.prev = W.cur; W.cur = candTo[pick]; W.len += candL[pick];
            W.useCount[stepE]++;
            W.nodeUse[W.cur]++;
            W.markVisited(W.cur);
            /* recent：环形缓冲 + 集合。★ 刻意复刻 JS 那个
             * "同一个边号在窗口内进两次时，早的那份被挤出会把集合里晚的那份
             * 一并清掉"的行为 —— 这是参考实现已经验收过的语义，
             * 移植时不"顺手修好"，否则两端的统计口径就对不上了。 */
            if (W.recentN > o.recentWindow) {
                int evict = W.recent[0];
                System.arraycopy(W.recent, 1, W.recent, 0, W.recentN - 1);
                W.recentN--;
                W.inRecent[evict] = false;
            }
            W.recent[W.recentN++] = stepE;
            W.inRecent[stepE] = true;
            if (W.seqN < W.seqE.length) {
                W.seqE[W.seqN] = stepE; W.seqFrom[W.seqN] = W.prev; W.seqTo[W.seqN] = W.cur;
                W.seqN++;
            }
            if (W.needA && W.cur == W.nA) { W.needA = false; append(W.order, "A"); }
            if (W.needB && W.cur == W.nB) { W.needB = false; append(W.order, "B"); }
            if (W.cur == W.startNode && W.seqN > 2 && rng.chance(0.002)) break;  // 极小概率原地收工
        }
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(",");
        sb.append(s);
    }

    /** 诊断用：把 "A,B" 变成 {1,2} */
    private static int[] orderChars(StringBuilder s) {
        if (s.length() == 0) return new int[0];
        String[] parts = s.toString().split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].equals("A") ? 1 : 2;
        return out;
    }

    private static int countBacktracks(int[] seqE, int n) {
        int c = 0;
        for (int i = 1; i < n; i++) if (seqE[i] == seqE[i - 1]) c++;
        return c;
    }

    private static double repeatLength(RouteNet G, int[] seqE, int n) {
        boolean[] seen = new boolean[G.nEdges];
        double rep = 0;
        for (int i = 0; i < n; i++) {
            if (seen[seqE[i]]) rep += G.edgeLen[seqE[i]]; else seen[seqE[i]] = true;
        }
        return rep;
    }

    // ==================================================================
    //  7. 拟人化几何
    // ==================================================================
    static Pts humanize(RouteNet G, Pts full, int[] seqE, int[] seqFrom, int[] seqTo, int seqN,
                        Rng rng, Opts o) {
        /* 7.1 街口 = 相邻两趟的接缝。第 j 个接缝的节点是 seq[j].to。
         *     转角 = 进入方向 与 离开方向 的夹角。0 = 直行，π = 原地掉头。 */
        int m = Math.max(0, seqN - 1);
        double[] jx = new double[m], jy = new double[m], turns = new double[m];
        boolean[] valid = new boolean[m];
        for (int j = 0; j < m; j++) {
            int jn = seqTo[j];
            if (seqFrom[j + 1] != jn) continue;            // 理论上不会发生
            double ndx = G.nodeX[jn], ndy = G.nodeY[jn];
            double pvx = G.nodeX[seqFrom[j]], pvy = G.nodeY[seqFrom[j]];
            double nxx = G.nodeX[seqTo[j + 1]], nxy = G.nodeY[seqTo[j + 1]];
            double v1x = ndx - pvx, v1y = ndy - pvy;
            double v2x = nxx - ndx, v2y = nxy - ndy;
            double n1 = Math.sqrt(v1x * v1x + v1y * v1y), n2 = Math.sqrt(v2x * v2x + v2y * v2y);
            jx[j] = ndx; jy[j] = ndy;
            valid[j] = true;
            if (n1 < 1e-6 || n2 < 1e-6) { turns[j] = 0; continue; }
            turns[j] = Math.acos(clamp((v1x * v2x + v1y * v2y) / (n1 * n2), -1, 1));
        }
        // 把 full 上这些街口点按顺序标出来（只有 valid 的才算数）
        int[] jIdx = new int[m];
        double[] turnOut = new double[m];
        int nJ = 0;
        int searchFrom = 0;
        for (int jp = 0; jp < m; jp++) {
            if (!valid[jp]) continue;
            int best = searchFrom;
            double bd = Double.MAX_VALUE;
            for (int q = searchFrom; q < full.n; q++) {
                double d = d2(full.x[q], full.y[q], jx[jp], jy[jp]);
                if (d < bd) { bd = d; best = q; }
                if (d < 0.02) break;
            }
            jIdx[nJ] = best;
            turnOut[nJ] = turns[jp];
            nJ++;
            searchFrom = best;
        }

        // 7.2 等距重采样
        Pts Q = densifyUniform(full, o.dsResampleM);
        double[] cumFull = new double[full.n];
        for (int i = 1; i < full.n; i++)
            cumFull[i] = cumFull[i - 1] + d2(full.x[i], full.y[i], full.x[i - 1], full.y[i - 1]);
        double[] cumQ = new double[Q.n];
        for (int i = 1; i < Q.n; i++)
            cumQ[i] = cumQ[i - 1] + d2(Q.x[i], Q.y[i], Q.x[i - 1], Q.y[i - 1]);
        int[] jIdxQ = new int[nJ];
        for (int z = 0; z < nJ; z++) {
            double sv = cumFull[jIdx[z]];
            int lo = 0, hi = cumQ.length - 1;
            while (lo < hi) { int mid = (lo + hi) >> 1; if (cumQ[mid] < sv) lo = mid + 1; else hi = mid; }
            jIdxQ[z] = lo;
        }
        double headM = nJ > 0 ? cumFull[Math.min(jIdx[0], cumFull.length - 1)] : 0;

        double ds = o.dsResampleM;
        Pts P = Q;

        /* 7.3 转角抹圆：人不会在街口原地折 90°。
         *     所有窗口都从**抹圆之前**的快照里读，否则相邻街口的窗口会互相吃对方的结果。
         *     掉头（转角接近 180°）必须跳过：掉头的进出两段是同一条街，
         *     抹圆窗口两端 A、B 会落在同一个物理位置上，控制点又和它们共线，
         *     二次贝塞尔退化成一条直线 —— 结果不是"抹圆"，是把整个掉头压成一个
         *     零半径的尖点（实测中位半径 0.55 m，等于原地转身）。
         *     掉头交给 7.4 的"靠右 + 横向游走"去分开。 */
        if (o.roundCorners) {
            double[] s0x = Arrays.copyOf(P.x, P.n), s0y = Arrays.copyOf(P.y, P.n);
            for (int ci = 0; ci < nJ; ci++) {
                int jc = jIdxQ[ci];
                double ang2 = turnOut[ci];
                if (ang2 > 2.44) continue;                          // 140°
                double rM = clamp(1.0 + ang2 * 3.0, 1.0, 6.0);      // 转得越急，抹得越大
                int rN = Math.max(1, (int) Math.round(rM / ds));
                int lo = (ci > 0 ? jIdxQ[ci - 1] : 0) + 1;
                int hi = (ci + 1 < nJ ? jIdxQ[ci + 1] : P.n - 1) - 1;
                if (jc - rN < lo || jc + rN > hi) rN = Math.max(0, Math.min(jc - lo, hi - jc));
                int i0 = jc - rN, i1 = jc + rN;
                if (rN < 1 || i0 < 1 || i1 > P.n - 2) continue;
                double ax = s0x[i0], ay = s0y[i0], cx = s0x[jc], cy = s0y[jc],
                       bx = s0x[i1], by = s0y[i1];
                if (d2(bx, by, ax, ay) < 1.0) continue;             // A、B 几乎重合，抹圆没意义
                for (int t3 = 1; t3 < i1 - i0; t3++) {
                    double u3 = t3 / (double) (i1 - i0), mu = 1 - u3;
                    P.x[i0 + t3] = mu * mu * ax + 2 * mu * u3 * cx + u3 * u3 * bx;
                    P.y[i0 + t3] = mu * mu * ay + 2 * mu * u3 * cy + u3 * u3 * by;
                }
            }
        }

        /* 7.4 靠右 + 横向游走
         *     长直路段如果笔直一条线，一眼就看出来不是人跑的 —— 叠三个正弦，
         *     波长 200~400 m（大方向漂移）/ 45~95 m（换道）/ 9~22 m（步态摆动）。 */
        double[] wanderA = {rng.range(1.0, 3.2), rng.range(0.5, 1.4), rng.range(0.15, 0.5)};
        double[] wanderL = {rng.range(180, 420), rng.range(45, 95), rng.range(9, 22)};
        double[] wanderP = {rng.range(0, 6.28), rng.range(0, 6.28), rng.range(0, 6.28)};
        // 每一"趟"一个靠右偏移量；同一街段第二趟方向相反 -> 往返两趟自然分开几米
        double[] travBias = new double[Math.max(1, seqN)];
        for (int i = 0; i < seqN; i++) travBias[i] = rng.range(0.35, 2.1);

        if (o.wander || o.keepRight) {
            double[] cumR = new double[P.n];
            for (int i = 1; i < P.n; i++) cumR[i] = cumR[i - 1] + d2(P.x[i], P.y[i], P.x[i - 1], P.y[i - 1]);
            double[] s1x = Arrays.copyOf(P.x, P.n), s1y = Arrays.copyOf(P.y, P.n);
            int[] travOf = new int[P.n];
            double acc2 = -headM;
            int ti2 = 0;
            for (int i = 0; i < P.n; i++) {
                while (ti2 < seqN - 1 && acc2 > G.edgeLen[seqE[ti2]]) { acc2 -= G.edgeLen[seqE[ti2]]; ti2++; }
                travOf[i] = ti2;
                acc2 += ds;
            }
            for (int i = 1; i < P.n - 1; i++) {
                // 切线必须从**偏移之前**的位置算。在已经偏移过的点上算切线，
                // 前一个点被推歪 3 m，切线就转 50°，法线跟着转，然后越推越歪 —— 会失控。
                double pax = s1x[i - 1], pay = s1y[i - 1], pbx = s1x[i + 1], pby = s1y[i + 1];
                double tx = pbx - pax, ty = pby - pay;
                double tl = Math.sqrt(tx * tx + ty * ty);
                if (tl < 1e-9) continue;
                tx /= tl; ty /= tl;
                double nx2 = -ty, ny2 = tx;                         // 行进方向的右侧
                double off = 0;
                if (o.keepRight) off += travBias[travOf[i]];
                if (o.wander) {
                    for (int w2 = 0; w2 < 3; w2++)
                        off += wanderA[w2] * Math.sin(2 * Math.PI * cumR[i] / wanderL[w2] + wanderP[w2]);
                }
                off = clamp(off, -5.5, 5.5);
                P.x[i] = s1x[i] + nx2 * off;
                P.y[i] = s1y[i] + ny2 * off;
            }
        }

        // 7.5 轻度平滑，抹掉重采样留下的小台阶
        for (int pass = 0; pass < 2; pass++) {
            double[] sx = Arrays.copyOf(P.x, P.n), sy = Arrays.copyOf(P.y, P.n);
            for (int i = 1; i < P.n - 1; i++) {
                P.x[i] = (sx[i - 1] + 2 * sx[i] + sx[i + 1]) / 4;
                P.y[i] = (sy[i - 1] + 2 * sy[i] + sy[i + 1]) / 4;
            }
        }
        // 首点保持不动（起点是"按下开始"的那一下）
        if (P.n > 0 && full.n > 0) { P.x[0] = full.x[0]; P.y[0] = full.y[0]; }
        return P;
    }
}
