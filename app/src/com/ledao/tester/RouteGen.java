package com.ledao.tester;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * RouteGen —— 生成一条「像人跑的」校园路线。
 *
 * 上一版的做法是打卡点之间**走直线**，两个毛病：
 *   ① 笔直，一眼假； ② 直接穿湖、穿楼。
 *
 * 这一版：
 *   ① 用 Catmull-Rom 样条把打卡点串成平滑曲线（不再是折线）
 *   ② 叠加两层正弦「横向摆动」（波长 ~220m / ~73m，振幅 ~8m）—— 像人自然地绕开
 *   ③ 用 WaterGrid 把落进湖里的点推到岸上
 *   ④ 最后做一遍轻度平滑，去掉推挤产生的折角
 *   ⑤ 距离自动 ping-pong 展开到目标里程
 *
 * 输出是一条带累计距离的密集折线，跑步时按里程取点即可。
 */
public class RouteGen {

    public static class Path {
        public final List<double[]> pts = new ArrayList<>();   // {lat, lon}
        public final List<Double> cum = new ArrayList<>();     // 累计米数
        public double length = 0;

        /** 取里程 m 处的位置（线性插值），超出末端取末端 */
        public double[] at(double m) {
            if (pts.isEmpty()) return new double[]{0, 0};
            if (m <= 0) return pts.get(0);
            if (m >= length) return pts.get(pts.size() - 1);
            int lo = 0, hi = cum.size() - 1;
            while (lo < hi - 1) {
                int mid = (lo + hi) >>> 1;
                if (cum.get(mid) <= m) lo = mid; else hi = mid;
            }
            double a = cum.get(lo), b = cum.get(hi);
            double f = (b - a) < 1e-9 ? 0 : (m - a) / (b - a);
            double[] p = pts.get(lo), q = pts.get(hi);
            return new double[]{p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f};
        }
    }

    /**
     * 把路线两端沿**道路方向**各向外延长 25~60 米，当作起跑位 / 收尾位。
     *
     * 用户要求「起点和终点不要直接就是某个打卡点，要随机些」。但打卡点本身必须被
     * **踩到** —— 所以不能把它挪走，只能往外接一小段：延长之后打卡点变成路径上的
     * 内部点，命中判定照样 0 米命中，而首尾已经离打卡点 25~60 米了。
     */
    public static void extendEnds(Path p, Random rnd) {
        if (p == null || p.pts.size() < 3) return;
        List<double[]> pts = p.pts;

        double[] a0 = pts.get(0), a1 = pts.get(1);
        double d0 = dist(a0, a1);
        if (d0 > 1e-6) {
            double k = (20 + rnd.nextDouble() * 20) / d0;
            pts.add(0, new double[]{a0[0] + (a0[0] - a1[0]) * k,
                                    a0[1] + (a0[1] - a1[1]) * k});
        }
        int n = pts.size();
        double[] b1 = pts.get(n - 1), b0 = pts.get(n - 2);
        double d1 = dist(b0, b1);
        if (d1 > 1e-6) {
            double k = (20 + rnd.nextDouble() * 20) / d1;
            pts.add(new double[]{b1[0] + (b1[0] - b0[0]) * k,
                                 b1[1] + (b1[1] - b0[1]) * k});
        }

        p.cum.clear();
        p.cum.add(0.0);
        double acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            acc += dist(pts.get(i - 1), pts.get(i));
            p.cum.add(acc);
        }
        p.length = acc;
    }

    // ---------------------------------------------------------------- 几何工具
    public static double dist(double[] a, double[] b) {
        double R = 6371000.0;
        double dLat = Math.toRadians(b[0] - a[0]), dLon = Math.toRadians(b[1] - a[1]);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
    }

    /** 一米对应多少度（校园尺度用固定值就够，误差 < 0.1%） */
    private static double degPerMeterLat(double lat) { return 1.0 / 111320.0; }

    private static double degPerMeterLon(double lat) {
        return 1.0 / (111320.0 * Math.cos(Math.toRadians(lat)));
    }

    // ------------------------------------------------------- 街道版路线（推荐）
    /**
     * 在校园路网上生成一条「只沿街道跑」的路线。
     *
     * 和旧 build() 的区别：旧的是在打卡点之间直接拉样条 —— 穿湖穿楼走直线。
     * 这个是先在路网上 A* 找出真实可走的街道，再用切角法（Chaikin）把栅格台阶磨圆，
     * 得到的就是一条人跑出来的、拐角带弧度的街道轨迹。
     *
     * @param grid    路网（为 null 时返回 null，调用方回落旧方案）
     * @param all     跑区全部打卡点
     * @param ia,ib   本次「必过」的两个打卡点下标（起点/终点）
     * @param targetM 目标里程（米）
     * @param info    非 null 时写入决策过程，便于日志
     */
    public static Path buildStreet(RoadGrid grid, List<double[]> all, int ia, int ib,
                                   double targetM, Random rnd, StringBuilder info) {
        if (grid == null || all == null || all.size() < 2) return null;
        int n = all.size();
        int[][] cell = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] c = grid.toCell(all.get(i)[0], all.get(i)[1]);
            int[] s = grid.snap(c[0], c[1], 30);
            cell[i] = s != null ? s : c;      // 打卡点已被脚本凿通到路网，通常直接返回自身
        }
        if (cell[ia] == null || cell[ib] == null) return null;

        // ---- 单程长度缓存（A* 对称，所以按无序对缓存两条方向）
        final java.util.HashMap<Long, List<int[]>> pathCache = new java.util.HashMap<>();
        final java.util.HashMap<Long, Double> lenCache = new java.util.HashMap<>();

        java.util.List<int[]> chain = new ArrayList<>();
        chain.add(cell[ia]);
        chain.add(cell[ib]);
        double total = legLen(grid, cell[ia], cell[ib], pathCache, lenCache);
        if (total < 0) return null;

        // ---- 贪心插点：链太短就再串几个打卡点，让单程长度逼近目标里程
        int guard = 0;
        while (total < targetM * 0.92 && chain.size() < 8 && guard++ < 10) {
            int bestPos = -1, bestIdx = -1;
            double bestScore = Math.abs(total - targetM), bestTotal = total;
            for (int t = 0; t < 5; t++) {                     // 每轮抽查 5 个候选点
                int cand = rnd.nextInt(n);
                if (cand == ia || cand == ib || cell[cand] == null) continue;
                boolean used = false;
                for (int[] c : chain) if (c == cell[cand]) { used = true; break; }
                if (used) continue;
                for (int p = 1; p < chain.size(); p++) {
                    double a = legLen(grid, chain.get(p - 1), cell[cand], pathCache, lenCache);
                    double b = legLen(grid, cell[cand], chain.get(p), pathCache, lenCache);
                    if (a < 0 || b < 0) continue;
                    double c0 = legLen(grid, chain.get(p - 1), chain.get(p), pathCache, lenCache);
                    double nt = total - c0 + a + b;
                    if (nt > 5000) continue;                   // 别超过里程上限
                    double sc = Math.abs(nt - targetM);
                    if (sc < bestScore - 1e-6) {
                        bestScore = sc; bestPos = p; bestIdx = cand; bestTotal = nt;
                    }
                }
            }
            if (bestPos < 0) break;
            chain.add(bestPos, cell[bestIdx]);
            total = bestTotal;
        }

        // ---- 拼装折线
        List<double[]> poly = new ArrayList<>();
        for (int p = 0; p + 1 < chain.size(); p++) {
            List<int[]> leg = astarCached(grid, chain.get(p), chain.get(p + 1), pathCache);
            if (leg == null) return null;
            List<int[]> seg = RoadGrid.collapse(leg);
            for (int k = 0; k < seg.size(); k++) {
                if (p > 0 && k == 0) continue;                 // 接点去重
                int[] c = seg.get(k);
                poly.add(grid.toLL(c[0], c[1]));
            }
        }
        if (poly.size() < 2) return null;
        // ★ 关键：把链首尾**精确**换成打卡点本身的经纬度。打卡点可能在人行道上，
        //   离车行道几十米；命中判定半径只有 22 米，不钉死就会漏打卡。
        poly.set(0, new double[]{all.get(ia)[0], all.get(ia)[1]});
        poly.set(poly.size() - 1, new double[]{all.get(ib)[0], all.get(ib)[1]});

        List<double[]> sm = chaikin(poly, 3);
        Path path = new Path();
        double acc = 0;
        path.pts.add(sm.get(0));
        path.cum.add(0.0);
        for (int i = 1; i < sm.size(); i++) {
            acc += dist(sm.get(i - 1), sm.get(i));
            path.pts.add(sm.get(i));
            path.cum.add(acc);
        }
        path.length = acc;
        if (info != null) {
            info.append("街道链 ").append(chain.size()).append(" 点，单程 ")
                .append((int) acc).append(" 米（目标 ").append((int) targetM).append("）");
        }
        return path;
    }

    // ------------------------------------------------------- 闭环版路线（真机就是这个形状）
    /**
     * ★★★ 闭环版路线：**起点 ≈ 终点**，两个打卡点落在环的中段。
     *
     * 为什么改成闭环（2026-10-01 金样本实证，docs/22）：
     *   真机三份长轨迹的首末点距离分别是 **0 m / 4 m / 3.8 m** —— 全是回到起点的环线；
     *   而金样本（2026-09-30 18:51 那次打卡成功的校园跑）首点 (36.658909,114.602074)
     *   与末点 (36.658943,114.602074) 只差 3.8 m。我们以前是 A→B 的单程线，
     *   起点正好压在打卡点上、终点也是打卡点 —— 既不像人，也让两个打卡点落在
     *   轨迹的第 0 个点和最后一个点上（真机三份长轨迹里 `p` 从不落在 idx 0）。
     *
     * 构造：随机街道点 S → 打卡点 A → 打卡点 B → 回到 S，闭合成环。
     *   S 取「跑区质心附近随机 0~300 米」再吸附到路网 —— 于是起点/终点既不是
     *   打卡点，又天然落在能跑的街道上（用户要求「起点和终点不要直接就是某个打卡点」）。
     *   环太短就贪心再串几个打卡点当途经点，把周长顶到目标里程。
     *
     * @param startLL 起跑位置 {lat, lon}（会被吸附到最近的路网格）
     * @return 闭环路径；路网不可达时返回 null，调用方回落旧方案
     */
    public static Path buildLoop(RoadGrid grid, List<double[]> all, int ia, int ib,
                                 double[] startLL, double targetM, Random rnd,
                                 StringBuilder info) {
        if (grid == null || all == null || all.size() < 2 || startLL == null) return null;
        int n = all.size();
        // 打卡点吸附半径逐轮放宽：路网在某些点附近可能被跑区掩码切得只剩孤岛，
        // 30 m 吸不到就试 60 / 100 m（实测能救回大半失败的组合）
        int[] radii = {30, 60, 100};
        // ★ 起跑点候选：先是仿真起跑点，再退到「两个必过点的中点」「全部打卡点质心」。
        //   A* 在某些角落会被跑区掩码切成孤岛，单一候选会有 1/3 的组合直接失败。
        List<double[]> starts = new ArrayList<>();
        starts.add(startLL);
        starts.add(new double[]{(all.get(ia)[0] + all.get(ib)[0]) / 2,
                                (all.get(ia)[1] + all.get(ib)[1]) / 2});
        double clat = 0, clon = 0;
        for (double[] p : all) { clat += p[0]; clon += p[1]; }
        starts.add(new double[]{clat / all.size(), clon / all.size()});
        // ★★★ 起跑点优先级排序（2026-10-07，界面优化 #3/#5 的根因修复）：
        //   实测（scripts/route_diag4.py）：起跑点吸到「最近的道路格」时，
        //   **52% 会落到 A* 走不到的孤立小块上**（跑区内可跑格被切成 15 块）。
        //   那一落，整条 buildLoop 就返回 null，回落到样条 —— 于是瞬移、穿楼。
        //   所以这里改成：**先把主连通块算出来，起跑点只吸到主块**。
        //   同一个脚本实测：改成主块之后 182/182 个打卡点组合全部可达。
        int[] mainStart = grid.nearestMain(startLL[0], startLL[1], 200);
        if (mainStart != null) {
            starts.add(0, grid.toLL(mainStart[0], mainStart[1]));   // 最优：就在主块上
            if (info != null) {
                double off = dist(startLL, starts.get(0));
                info.append("起跑点吸附到主连通块（挪 ").append((int) off).append(" m）");
            }
        } else if (info != null) {
            info.append("⚠ 200 格内找不到主连通块的路");
        }
        for (int attempt = 0; attempt < radii.length; attempt++) {
            int[][] cell = new int[n][];
            for (int i = 0; i < n; i++) {
                int[] c = grid.toCell(all.get(i)[0], all.get(i)[1]);
                int[] s = grid.snap(c[0], c[1], radii[attempt]);
                cell[i] = s != null ? s : c;
            }
            if (cell[ia] == null || cell[ib] == null) continue;
            for (int si = 0; si < starts.size(); si++) {
                Path p = tryLoop(grid, all, cell, ia, ib, starts.get(si), targetM, rnd,
                        attempt, si, info);
                if (p != null) return p;
            }
        }
        return null;
    }

    /** buildLoop 的单次尝试（给定一套已吸附的格坐标 + 一个起跑点候选） */
    private static Path tryLoop(RoadGrid grid, List<double[]> all, int[][] cell,
                                int ia, int ib, double[] startLL, double targetM,
                                Random rnd, int attempt, int startIdx, StringBuilder info) {
        int n = all.size();
        int[] s0 = grid.toCell(startLL[0], startLL[1]);
        // ★★ 起跑格必须吸到**主连通块**，不能只吸「最近的路」。
        //   scripts/route_diag4.py 实测：只吸最近道路时 52% 落在孤立小块上，
        //   之后每一段 A* 都必然失败（起点就出不去），整条路线回落成样条直线。
        int[] sn = grid.snapMain(s0[0], s0[1], 200);
        if (sn == null) sn = grid.snap(s0[0], s0[1], 40 + attempt * 30);
        if (sn == null) return null;
        // ★★ 起跑格**不能**和某个必过点的格重合！
        //   重合时 A* 的第一段退化成单点（poly 只有一个点），而钉打卡点是按
        //   「该链节点的下标」钉的 —— 下标 0 正好是闭环的首点，一钉就把
        //   首点挪走，闭合处豁开几百米（实测 #0 豁 369.8 m、#9 豁 749.6 m）。
        //   所以这里先把它挤到隔壁一个可走的道路上。
        if (sameCell(sn, cell[ia]) || sameCell(sn, cell[ib])) {
            int[] alt = null;
            for (int r = 2; r < 40 && alt == null; r++) {
                for (int a = 0; a < 24; a++) {
                    double ang = a * Math.PI * 2 / 24;
                    int nx = sn[0] + (int) Math.round(Math.cos(ang) * r);
                    int ny = sn[1] + (int) Math.round(Math.sin(ang) * r);
                    if (!grid.road(nx, ny)) continue;
                    int[] c = new int[]{nx, ny};
                    if (sameCell(c, cell[ia]) || sameCell(c, cell[ib])) continue;
                    alt = c;
                    break;
                }
            }
            if (alt != null) sn = alt;
        }

        final java.util.HashMap<Long, List<int[]>> pc = new java.util.HashMap<>();
        final java.util.HashMap<Long, Double> lc = new java.util.HashMap<>();

        java.util.List<int[]> chain = new ArrayList<>();
        chain.add(sn);                 // 起跑
        chain.add(cell[ia]);           // 打卡点 A
        chain.add(cell[ib]);           // 打卡点 B
        chain.add(sn);                 // 回到起点 → 闭环
        double total = 0;
        for (int p = 0; p + 1 < chain.size(); p++) {
            double l = legLen(grid, chain.get(p), chain.get(p + 1), pc, lc);
            if (l < 0) {
                if (info != null) info.setLength(0);
                if (info != null) info.append("A* 不通（吸附半径第 ").append(attempt + 1).append(" 轮）");
                return null;
            }
            total += l;
        }

        // ---- 贪心插点：环太短就再串几个打卡点，把周长顶到目标里程。
        //   只插在 chain[1..size-2] 之间，保证「起点还在链首、终点还是同一个起点」。
        //
        // ★★★ 2026-10-08 改过一轮，原因是实测（work/looptest/PlanProbe.java）：
        //   旧写法「total < targetM*0.92 就一直插、插到超了为止」有两个毛病 ——
        //     ① 短目标会被顶穿：目标 2.1 km 生成出 3.77 km（1.80×），
        //        因为两个打卡点之间一段路就可能一两公里，一插就过头；
        //     ② 反过来也可能欠账：目标 2.1 km 只生成 1.95 km ——
        //        比服务端的 min_distance(2.00 km) 还短，那一局直接白跑。
        //   现在：地板抬到 MIN_LOOP_M（2.2 km，给 2.00 km 留安全余量），
        //   并且**优先挑「不超过目标 1.15 倍」的候选**；实在没有这种候选才允许超。
        final double floorM = Math.max(targetM, MIN_LOOP_M);
        int guard = 0;
        while (total < floorM && chain.size() < 8 && guard++ < 12) {
            int bestPos = -1, bestIdx = -1;
            double bestScore = Double.MAX_VALUE, bestTotal = total;
            int overPos = -1, overIdx = -1;                  // 超目标的备胎
            double overScore = Double.MAX_VALUE, overTotal = total;
            for (int t = 0; t < 6; t++) {                    // 每轮抽查 6 个候选点
                int cand = rnd.nextInt(n);
                if (cand == ia || cand == ib || cell[cand] == null) continue;
                boolean used = false;
                for (int[] c : chain) if (c == cell[cand]) { used = true; break; }
                if (used) continue;
                for (int p = 1; p <= chain.size() - 1; p++) {
                    if (p >= chain.size()) break;
                    double a = legLen(grid, chain.get(p - 1), cell[cand], pc, lc);
                    if (a < 0) continue;
                    double b = legLen(grid, cell[cand], chain.get(p), pc, lc);
                    if (b < 0) continue;
                    double c0 = legLen(grid, chain.get(p - 1), chain.get(p), pc, lc);
                    if (c0 < 0) continue;
                    double nt = total - c0 + a + b;
                    if (nt > MAX_LOOP_M) continue;           // 硬上限（服务端 max_distance 10 km）
                    double sc = Math.abs(nt - targetM);
                    if (nt <= targetM * 1.15) {              // 优先：不超太多
                        if (sc < bestScore - 1e-6) {
                            bestScore = sc; bestPos = p; bestIdx = cand; bestTotal = nt;
                        }
                    } else if (sc < overScore - 1e-6) {      // 备胎：宁可超，也不能欠
                        overScore = sc; overPos = p; overIdx = cand; overTotal = nt;
                    }
                }
            }
            if (bestPos < 0) { bestPos = overPos; bestIdx = overIdx; bestTotal = overTotal; }
            if (bestPos < 0) break;
            chain.add(bestPos, cell[bestIdx]);
            total = bestTotal;
        }

        // ---- 拼装折线，并**逐段记下每个链节点落在 poly 的哪个下标**。
        //   ⚠ 这里必须按下标钉，不能「找离打卡点最近的顶点」—— 那样在起点
        //     恰好离打卡点更近时会把首点也挪过去，闭环就断了（实测出现过 342 m 的豁口）。
        List<double[]> poly = new ArrayList<>();
        List<Integer> nodeAt = new ArrayList<>();            // nodeAt[i] = chain[i+1] 的下标
        for (int p = 0; p + 1 < chain.size(); p++) {
            List<int[]> leg = astarCached(grid, chain.get(p), chain.get(p + 1), pc);
            if (leg == null) return null;
            // ★★ 这里**不能**用 RoadGrid.collapse() 把逐格路径压成折线段！
            //   collapse 会把路径变成「长直段 + 拐角」，而后面 chaikin() 是按
            //   **相邻两点的 1/4、3/4** 切角的 —— 段越长，拐角被切掉越多。
            //   实测：200 米的段直角拐弯时第一轮就切掉 50 米，三轮下来曲线
            //   离原来钉住的打卡点能有 32.7 米，直接漏打卡。
            //   改用逐格路径（每格 1.92 米），切角量降到 1 米以内，
            //   钉住的打卡点在切角后仍在 30 米命中半径内。
            for (int k = 0; k < leg.size(); k++) {
                if (p > 0 && k == 0) continue;               // 接点去重
                poly.add(grid.toLL(leg.get(k)[0], leg.get(k)[1]));
            }
            nodeAt.add(poly.size() - 1);
        }
        if (poly.size() < 8) return null;

        // ---- 把两个打卡点所在的链节点**精确钉**到打卡点自身坐标。
        //   打卡点可能在人行道上、离车行道几十米，不钉死就会漏打卡（命中半径只有 30 m）。
        //   若该点恰好压在跑区多边形**外**（真机数据里 28994 就超出北边界约 15 m），
        //   就沿「跑区质心 → 该点」方向拉回边界内 —— 只挪十几米，仍远在 30 m 命中半径内，
        //   但 is_running_area_valid 就不会被这一下搞成 false。
        for (int i = 0; i < chain.size(); i++) {
            if (i == 0 || i == chain.size() - 1) continue;   // 首尾都是起跑点，不动
            int which = (chain.get(i) == cell[ia]) ? ia
                      : (chain.get(i) == cell[ib]) ? ib : -1;
            if (which < 0) continue;
            int at = nodeAt.get(i - 1);
            if (at <= 0 || at >= poly.size() - 1) continue;  // 兜底：绝不动闭环的首/末点
            double[] t = all.get(which);
            if (CAMPUS_ZONE != null) t = RoadGrid.clampInto(t[0], t[1], CAMPUS_ZONE);
            poly.set(at, new double[]{t[0], t[1]});
        }

        // ★ 闭环不变量：末点必须**就是**首点（同一个坐标）。
        //   真机三份长轨迹首末相距 0 m / 4 m / 3.8 m，闭环是硬要求；
        //   这里再兜一道，任何上游退化都不会让起点和终点分家。
        if (poly.size() >= 2) poly.set(poly.size() - 1, poly.get(0));

        List<double[]> sm = chaikin(poly, 2);                // 切角（逐格路径，切角量 <1 米）
        Path path = new Path();
        double acc = 0;
        path.pts.add(sm.get(0));
        path.cum.add(0.0);
        for (int i = 1; i < sm.size(); i++) {
            acc += dist(sm.get(i - 1), sm.get(i));
            path.pts.add(sm.get(i));
            path.cum.add(acc);
        }
        path.length = acc;
        // ★ 几何校验门：出现长直线段就判定这条不能用，让 buildLoop 去试下一组候选
        //   （用户明确要求「不要出现瞬移现象」——这里把它做成硬约束）
        Audit aud = audit(path);
        if (!aud.ok) {
            if (info != null) {
                info.setLength(0);
                info.append("候选被几何校验拒掉：").append(aud.reason);
            }
            return null;
        }
        if (info != null) {
            info.append("闭环 ").append(chain.size() - 1).append(" 段，周长 ")
                .append((int) acc).append(" 米（目标 ").append((int) targetM)
                .append("；吸附半径第 ").append(attempt + 1)
                .append(" 轮，起跑候选 #").append(startIdx + 1)
                .append("；").append(aud).append("）");
        }
        return path;
    }

    /** 两个格坐标是否同一个（用于「起跑格不能压在打卡点格上」的判断） */
    private static boolean sameCell(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1];
    }

    // ==================================================================
    //  ★★★ 路线几何校验门（2026-10-07 界面优化 #3/#5）
    //
    //  用户的原话：「这个路线怎么有点跳跃」「不要在开始跑的时候出现瞬移现象」。
    //  跳跃 = 轨迹里有一段**长直线**（两点之间几十上百米直线相连）。
    //  正常沿街道走出来的路线，点距中位 7.7 m（真机 09-29 那次实测），
    //  平滑之后没有理由出现 >60 m 的直连段 —— 出现了就说明混进了
    //  「打卡点之间拉直线」的那段（样条回落 / 链节点没接上）。
    //
    //  这里把它变成一道**硬闸**：算一遍最大直连段，超限就判定这条路线不能用，
    //  让调用方去用别的方案。宁可换一条路线，也不给用户一条会瞬移的。
    // ==================================================================

    /** 单段直连的上限（米）—— 超过它就算「跳跃」 */
    public static final double MAX_GAP_M = 60.0;

    /**
     * 闭环路线长度的**地板**（米）。
     *
     * 服务端规则 `min_distance = 2.00`，低于它这一局直接不计分
     * （见 docs/ 里 beforeRunV260 的 run_line_info）。生成器偶尔会欠账 ——
     * PlanProbe 实测出现过「目标 2.1 km 只生成 1.95 km」，那就白跑了。
     * 所以地板抬到 2.2 km，给 2.00 km 留安全余量。
     */
    public static final double MIN_LOOP_M = 2200.0;

    /** 闭环路线长度的硬上限（米）—— 服务端 max_distance = 10 km，这里再保守一点 */
    public static final double MAX_LOOP_M = 8000.0;

    /** 路线体检结果 */
    public static class Audit {
        public double maxGap;          // 最大相邻点距离（米）
        public int maxGapAt;           // 出现在第几个点
        public double length;          // 总长（米）
        public boolean ok;             // 是否通过
        public String reason = "";     // 不通过的原因

        @Override public String toString() {
            if (ok) return String.format(java.util.Locale.US,
                    "几何✔ 长 %.0f m，最大直连段 %.1f m", length, maxGap);
            return String.format(java.util.Locale.US,
                    "几何✘ %s（最大直连段 %.1f m @第%d点，总长 %.0f m）",
                    reason, maxGap, maxGapAt, length);
        }
    }

    /** 给一条折线做体检 */
    public static Audit audit(List<double[]> pts) {
        Audit a = new Audit();
        if (pts == null || pts.size() < 2) {
            a.ok = false;
            a.reason = "点数不足";
            return a;
        }
        double acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            double d = dist(pts.get(i - 1), pts.get(i));
            acc += d;
            if (d > a.maxGap) { a.maxGap = d; a.maxGapAt = i; }
        }
        a.length = acc;
        if (a.maxGap > MAX_GAP_M) {
            a.ok = false;
            a.reason = "有 " + String.format(java.util.Locale.US, "%.0f", a.maxGap)
                    + " m 的直线段（上限 " + (int) MAX_GAP_M + " m）—— 会看起来像瞬移";
        } else {
            a.ok = true;
        }
        return a;
    }

    /** 给一个 Path 做体检（内部用） */
    public static Audit audit(Path p) {
        Audit a = audit(p == null ? null : p.pts);
        if (a.ok && p != null && Math.abs(a.length - p.length) > 1.0) {
            // 累计表与几何长度不一致 —— 说明 cum 有问题，取点会错位
            a.ok = false;
            a.reason = String.format(java.util.Locale.US,
                    "累计里程表对不上：cum 末值 %.1f m，几何长度 %.1f m", p.length, a.length);
        }
        return a;
    }

    /**
     * 跑区多边形缓存 —— 由 setCampusZone() 注入（调用方从 beforeRunV260 的
     * run_zone_latlng 解析）。为空时不做任何裁剪。
     */
    private static List<double[]> CAMPUS_ZONE = null;

    /** ★ 注入跑区多边形（{lat, lon} 顶点）。传 null / 空表即关闭限制。 */
    public static void setCampusZone(List<double[]> poly) {
        CAMPUS_ZONE = (poly == null || poly.size() < 3)
                ? null : new ArrayList<>(poly);
    }

    private static double legLen(RoadGrid g, int[] a, int[] b,
                                 java.util.HashMap<Long, List<int[]>> pc,
                                 java.util.HashMap<Long, Double> lc) {
        List<int[]> p = astarCached(g, a, b, pc);
        if (p == null) return -1;
        double s = 0;
        for (int i = 1; i < p.size(); i++) {
            int dx = p.get(i)[0] - p.get(i - 1)[0], dy = p.get(i)[1] - p.get(i - 1)[1];
            s += Math.sqrt(dx * dx + dy * dy);
        }
        // ★★ 单位坑：上面累加出来的是「格数」，而 targetM 是「米」。
        //    不乘这一下，贪心插点会把 2500 米当成 2500 格（≈4800 米），
        //    结果预览里里程直接飙到 5.5 公里，超过 5 公里上限。
        s *= g.metersPerCell();
        Long key = key(a, b);
        lc.put(key, s);
        return s;
    }

    private static List<int[]> astarCached(RoadGrid g, int[] a, int[] b,
                                           java.util.HashMap<Long, List<int[]>> pc) {
        Long key = key(a, b);
        List<int[]> p = pc.get(key);
        if (p != null) return p;
        p = g.astar(a, b);
        if (p == null) {                                   // 反向再试一次
            List<int[]> r = g.astar(b, a);
            if (r != null) {
                java.util.Collections.reverse(r);
                p = r;
            }
        }
        if (p != null) {
            pc.put(key, p);
            // ★★ 反向缓存必须存**反向的列表**，不能把正向列表直接挂在 (b,a) 上。
            //   挂错了，后面只要有一个 leg 按 (b,a) 取，拿到的就是一条「从 a 走到 b」
            //   的折线 —— 拼出来的环会在那里直接断开几百米
            //   （实测豁口 279.8 / 369.8 / 749.6 m，三个失败样本全是这么来的）。
            List<int[]> rev = new ArrayList<>(p);
            java.util.Collections.reverse(rev);
            pc.put(key(b, a), rev);
        }
        return p;
    }

    private static Long key(int[] a, int[] b) {
        return ((long) a[0] << 40) | ((long) a[1] << 20) | ((long) b[0] << 10) | b[1];
    }

    /** Chaikin 切角：把 A* 的直角台阶磨成自然的圆角，比 Catmull-Rom 更贴路 */
    public static List<double[]> chaikin(List<double[]> in, int passes) {
        List<double[]> pts = in;
        for (int it = 0; it < passes; it++) {
            if (pts.size() < 3) break;
            List<double[]> out = new ArrayList<>();
            out.add(pts.get(0));
            for (int i = 0; i + 1 < pts.size(); i++) {
                double[] p = pts.get(i), q = pts.get(i + 1);
                out.add(new double[]{p[0] * 0.75 + q[0] * 0.25, p[1] * 0.75 + q[1] * 0.25});
                out.add(new double[]{p[0] * 0.25 + q[0] * 0.75, p[1] * 0.25 + q[1] * 0.75});
            }
            out.add(pts.get(pts.size() - 1));
            pts = out;
        }
        return pts;
    }

    // ---------------------------------------------------------------- 主流程
    /**
     * @param cps      打卡点（按顺序走）
     * @param targetM  目标里程（米）
     * @param water    水面位图（可为 null）
     * @param random   true=随机摆动（防风控），false=几乎笔直（对照实验用）
     */
    public static Path build(List<double[]> cps, double targetM, WaterGrid water,
                             Random rnd, boolean random) {
        Path path = new Path();
        if (cps == null || cps.size() < 2) return path;

        // ★ 起点/终点不能正好压在打卡点上（用户实测真机就是这样）：
        //   从打卡点沿随机方向偏出去 20~70 米当作起跑位，终点同样处理。
        double[] startPt = offsetFrom(cps.get(0), cps.get(1), rnd, 20 + rnd.nextDouble() * 50);
        double[] endPt = offsetFrom(cps.get(cps.size() - 1),
                cps.get(cps.size() - 2), rnd, 20 + rnd.nextDouble() * 50);

        // ---- ① 把打卡点展开成 ping-pong 的 via 链，直到够长
        List<double[]> chain = new ArrayList<>();
        chain.add(startPt);
        for (double[] p : cps) chain.add(p);
        chain.add(endPt);

        double oneWay = 0;
        for (int i = 1; i < chain.size(); i++) oneWay += dist(chain.get(i - 1), chain.get(i));
        if (oneWay < 30) return path;

        List<double[]> via = new ArrayList<>();
        int laps = Math.max(1, (int) Math.ceil(targetM / (oneWay * 2)) + 1);
        for (int lap = 0; lap < laps; lap++) {
            boolean fwd = (lap % 2 == 0);
            for (int i = 0; i < chain.size(); i++) {
                double[] p = chain.get(fwd ? i : chain.size() - 1 - i);
                if (!via.isEmpty() && dist(via.get(via.size() - 1), p) < 1.0) continue;
                via.add(new double[]{p[0], p[1]});
            }
        }
        if (via.size() < 2) return path;

        // ---- ② Catmull-Rom 采样成平滑曲线
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < via.size() - 1; i++) {
            double[] p0 = via.get(Math.max(0, i - 1));
            double[] p1 = via.get(i);
            double[] p2 = via.get(i + 1);
            double[] p3 = via.get(Math.min(via.size() - 1, i + 2));
            double segLen = dist(p1, p2);
            int steps = Math.max(3, (int) (segLen / 5.0));      // 每 ~5 米一个点
            for (int s = 0; s < steps; s++) {
                pts.add(cr(p0, p1, p2, p3, s / (double) steps));
            }
        }
        pts.add(new double[]{via.get(via.size() - 1)[0], via.get(via.size() - 1)[1]});

        // ---- ③ 横向摆动：两层正弦，波长/相位都随机 —— 这是「像人」的关键
        double amp = random ? 9.0 : 2.0;
        double w1 = 2 * Math.PI / (random ? (160 + rnd.nextDouble() * 140) : 260);
        double w2 = 2 * Math.PI / (random ? (52 + rnd.nextDouble() * 45) : 90);
        double ph1 = rnd.nextDouble() * Math.PI * 2, ph2 = rnd.nextDouble() * Math.PI * 2;
        List<double[]> wob = new ArrayList<>(pts.size());
        double run = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) run += dist(pts.get(i - 1), pts.get(i));
            double[] a = pts.get(Math.max(0, i - 1));
            double[] b = pts.get(Math.min(pts.size() - 1, i + 1));
            double dLat = b[0] - a[0], dLon = b[1] - a[1];
            double len = Math.hypot(dLat, dLon);
            if (len < 1e-12) { wob.add(new double[]{pts.get(i)[0], pts.get(i)[1]}); continue; }
            // 法向量
            double nLat = -dLon / len, nLon = dLat / len;
            double off = amp * (0.62 * Math.sin(w1 * run + ph1) + 0.38 * Math.sin(w2 * run + ph2));
            // ★ 关键：靠近打卡点时把摆动收敛到 0。
            //   否则路会被摆到离打卡点 10 米开外，而服务端要求「经过打卡点」，
            //   实测步长 20 米时贴着摆动的点采样，会直接判成没打卡（points=0，不计分）。
            double nearVia = Double.MAX_VALUE;
            for (double[] v : via) nearVia = Math.min(nearVia, dist(pts.get(i), v));
            double taper = Math.min(1.0, Math.max(0.0, (nearVia - 5.0) / 35.0));
            off *= taper;
            double[] p = pts.get(i);
            wob.add(new double[]{
                    p[0] + nLat * off * degPerMeterLat(p[0]),
                    p[1] + nLon * off * degPerMeterLon(p[0])});
        }

        // ---- ④ 把落水的点推回岸上
        if (water != null && water.isLoaded()) {
            int pushed = 0;
            for (int i = 0; i < wob.size(); i++) {
                double[] p = wob.get(i);
                if (water.isWater(p[0], p[1])) {
                    double[] q = water.pushOut(p[0], p[1], 40);
                    wob.set(i, q);
                    pushed++;
                }
            }
            if (pushed > 0) wob = smooth(wob, 3 + pushed / 20);
        }

        // ---- ⑤ 裁到目标里程
        path.pts.add(wob.get(0));
        path.cum.add(0.0);
        double acc = 0;
        for (int i = 1; i < wob.size(); i++) {
            acc += dist(wob.get(i - 1), wob.get(i));
            path.pts.add(wob.get(i));
            path.cum.add(acc);
            if (acc >= targetM) break;
        }
        path.length = acc;
        // ★ 界面优化 #3/#5：样条方案也过一遍几何校验。
        //   这条路线是「打卡点之间拉样条」的回落方案，本来就可能穿楼；
        //   再加上避湖时把点推回岸上（最远 40 m），很容易出现长直连段。
        //   校验不过就返回空 Path，让调用方知道「这条路不能用」，
        //   在界面上明确报出来 —— 而不是悄悄给用户一条会瞬移的线。
        if (!audit(path).ok) {
            return new Path();
        }
        return path;
    }

    /** 从 base 沿「背离 toward 的方向 + 随机侧偏」挪出 dist 米，用作起跑/收尾位置 */
    private static double[] offsetFrom(double[] base, double[] toward, Random rnd, double m) {
        double dLat = base[0] - toward[0], dLon = base[1] - toward[1];
        double ang = Math.atan2(dLat, dLon) + (rnd.nextDouble() - 0.5) * 1.4;   // ±40°
        double kLat = degPerMeterLat(base[0]), kLon = degPerMeterLon(base[0]);
        // 在「米」的坐标系里走，再换回度
        double dx = Math.cos(ang) * m * kLon, dy = Math.sin(ang) * m * kLat;
        return new double[]{base[0] + dy, base[1] + dx};
    }

    private static double[] cr(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] o = new double[2];
        for (int k = 0; k < 2; k++) {
            o[k] = 0.5 * ((2 * p1[k])
                    + (-p0[k] + p2[k]) * t
                    + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        }
        return o;
    }

    /** 轻度滑动平均，去掉推挤造成的折角 */
    private static List<double[]> smooth(List<double[]> in, int passes) {
        List<double[]> cur = in;
        for (int k = 0; k < passes; k++) {
            List<double[]> out = new ArrayList<>(cur.size());
            out.add(cur.get(0));
            for (int i = 1; i < cur.size() - 1; i++) {
                double[] a = cur.get(i - 1), b = cur.get(i), c = cur.get(i + 1);
                out.add(new double[]{(a[0] + 2 * b[0] + c[0]) / 4.0,
                                     (a[1] + 2 * b[1] + c[1]) / 4.0});
            }
            if (cur.size() > 1) out.add(cur.get(cur.size() - 1));
            cur = out;
        }
        return cur;
    }
}
