package com.ledao.tester;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * RoadGrid —— 校园「可跑街道」栅格 + A* 寻路。
 *
 * 为什么要有这个类：之前几版的路线是用样条曲线在打卡点之间硬连的，
 * 结果是**穿湖、穿楼、走直线**，一眼就假。用户的原话是「跑步只能按街道跑」。
 *
 * 路网来源：高德 style=8（纯路网图层）在 z=18 下的瓦片，按「非白即路」提取，
 * 每 4x4 像素合并成一格（≈1.92 米）。那层图天生只有道路，没有建筑和水面，
 * 正好就是「只沿街道跑」需要的约束。
 *
 * 生成脚本见 scripts/mk_roadgrid.py，产物是 assets/roadgrid.bin + roadgrid.json。
 * 加载失败时整个类退化为「没有路网」，调用方回落到旧的样条方案，不会崩。
 */
public class RoadGrid {

    private final int w, h;
    private final byte[] cells;          // 1 = 可跑道路
    private final double lon0, lon1, lat0, lat1;
    /** ★ 跑区掩码：非 0 = 该格在 run_zone_latlng 多边形内。null = 不做限制 */
    private byte[] zoneMask = null;

    private RoadGrid(int w, int h, byte[] cells,
                     double lon0, double lon1, double lat0, double lat1) {
        this.w = w; this.h = h; this.cells = cells;
        this.lon0 = lon0; this.lon1 = lon1; this.lat0 = lat0; this.lat1 = lat1;
    }

    /** 读 assets；任何异常都返回 null（调用方回落到样条路线） */
    public static RoadGrid load(Context ctx) {
        try {
            String js = new String(readAll(ctx.getAssets().open("roadgrid.json")), "UTF-8");
            JSONObject m = new JSONObject(js);
            int w = m.getInt("w"), h = m.getInt("h");
            byte[] packed = readAll(ctx.getAssets().open("roadgrid.bin"));
            if (packed.length < (w * h + 7) / 8) return null;
            byte[] cells = new byte[w * h];
            for (int i = 0; i < cells.length; i++)
                cells[i] = (byte) ((packed[i >> 3] >> (i & 7)) & 1);
            return new RoadGrid(w, h, cells,
                    m.getDouble("lon_left"), m.getDouble("lon_right"),
                    m.getDouble("lat_top"), m.getDouble("lat_bottom"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 从**文件系统目录**加载（给桌面离线测试用）。
     *
     * 手机上走 {@link #load(android.content.Context)} 读 assets；桌面回归测试
     * （work/looptest/RouteTest.java）直接指到 {@code app/assets} 目录，
     * 这样测试用的路网和打进 APK 的是**同一份字节**，不会出现「测试绿了但手机上是另一份数据」。
     */
    public static RoadGrid loadFromDir(String dir) {
        try {
            String js = new String(readAll(new java.io.FileInputStream(
                    new java.io.File(dir, "roadgrid.json"))), "UTF-8");
            JSONObject m = new JSONObject(js);
            int w = m.getInt("w"), h = m.getInt("h");
            byte[] packed = readAll(new java.io.FileInputStream(
                    new java.io.File(dir, "roadgrid.bin")));
            if (packed.length < (w * h + 7) / 8) return null;
            byte[] cells = new byte[w * h];
            for (int i = 0; i < cells.length; i++)
                cells[i] = (byte) ((packed[i >> 3] >> (i & 7)) & 1);
            return new RoadGrid(w, h, cells,
                    m.getDouble("lon_left"), m.getDouble("lon_right"),
                    m.getDouble("lat_top"), m.getDouble("lat_bottom"));
        } catch (Throwable t) {
            return null;
        }
    }

    public int getW() { return w; }
    public int getH() { return h; }

    /** 一格实际多少米（纬度方向）—— A* 的步数是「格」，换算里程必须乘它 */
    public double metersPerCell() {
        return (lat0 - lat1) * 111320.0 / h;
    }

    public boolean road(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        if (cells[y * w + x] == 0) return false;
        // ★ 跑区限制：服务端下发的 run_zone_latlng 就是「允许跑步的范围」，
        //   跑出这个多边形的轨迹会让 is_running_area_valid 变 false。
        //   A* 直接把界外的格当成不可走，路线自然全程留在跑区内。
        if (zoneMask != null && zoneMask[y * w + x] == 0) return false;
        return true;
    }

    /**
     * ★ 设定跑区多边形（{lat, lon} 顶点，来自 beforeRunV260 的 run_zone_latlng）。
     *   调用一次就预计算好掩码 —— A* 里每格都要判，绝不能现算多边形。
     *   poly 为空/顶点不足 3 个时清除限制。
     */
    public void setZone(List<double[]> poly) {
        comp = null; compSize = null; mainComp = -1;      // ★ 掩码变了，连通块作废
        centerPull = null;                                // ★ 中线代价也要重算
        if (poly == null || poly.size() < 3) { zoneMask = null; return; }
        byte[] mk = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] ll = toLL(x, y);
                mk[y * w + x] = (byte) (pointInPoly(ll[0], ll[1], poly) ? 1 : 0);
            }
        }
        zoneMask = mk;
    }

    public boolean hasZone() { return zoneMask != null; }

    // ==================================================================
    //  ★★★ 连通块（2026-10-07 界面优化 #3/#5 的根因修复）
    //
    //  实测（scripts/route_diag4.py / route_diag5.py）：跑区内可跑格被切成 15 块，
    //  14 个打卡点**全在主块**（27629 格），可起跑点是「跑区质心 + 随机 0~300 m」
    //  再 snap(40 格) 得到的 —— **52% 的起跑格落在孤立小块上或 40 格内根本没路**。
    //  于是 A* 必然不通 → RouteGen.buildLoop 返回 null → 回落到样条 ping-pong，
    //  而样条是在打卡点之间**走直线**的 —— 这就是用户看到的「瞬移 + 穿楼」。
    //  182 个打卡点组合里 69 个（38%）中招。
    //
    //  修法：先把「跑区内可跑格」的连通块标出来，起跑点**只吸到主块**。
    //  实测 182/182 全通，200 次随机起跑点 200 次都找得到落点
    //  （被挪动的距离中位 68 m、p90 132 m、max 182 m）。
    // ==================================================================
    private int[] comp = null;          // 每格所属连通块编号，-1 = 不可走
    private int[] compSize = null;      // 每个块多少格
    private int mainComp = -1;          // 最大块的编号

    /** 惰性标一次连通块（4/8 邻接都用 8 —— A* 就是 8 邻接） */
    private void ensureComp() {
        if (comp != null) return;
        int n = w * h;
        int[] lab = new int[n];
        for (int i = 0; i < n; i++) lab[i] = -1;
        int[] sizes = new int[64];
        int nc = 0;
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (lab[s] >= 0 || !road(s % w, s / w)) continue;
            if (nc == sizes.length) {
                int[] bigger = new int[sizes.length * 2];
                System.arraycopy(sizes, 0, bigger, 0, sizes.length);
                sizes = bigger;
            }
            int sp = 0;
            stack[sp++] = s;
            lab[s] = nc;
            int cnt = 0;
            while (sp > 0) {
                int c = stack[--sp];
                cnt++;
                int cx = c % w, cy = c / w;
                for (int k = 0; k < 8; k++) {
                    int nx = cx + DX8[k], ny = cy + DY8[k];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int ni = ny * w + nx;
                    if (lab[ni] >= 0 || !road(nx, ny)) continue;
                    lab[ni] = nc;
                    stack[sp++] = ni;
                }
            }
            sizes[nc] = cnt;
            nc++;
        }
        // 裁到实际块数
        if (nc < sizes.length) {
            int[] t = new int[nc];
            System.arraycopy(sizes, 0, t, 0, nc);
            sizes = t;
        }
        int best = -1, bestSz = -1;
        for (int i = 0; i < nc; i++) if (sizes[i] > bestSz) { bestSz = sizes[i]; best = i; }
        comp = lab;
        compSize = sizes;
        mainComp = best;
    }

    private static final int[] DX8 = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY8 = {0, 0, 1, -1, 1, -1, 1, -1};

    /** 某格所属连通块编号（-1 = 不可走） */
    public int component(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return -1;
        ensureComp();
        return comp[y * w + x];
    }

    /** 主连通块（能跑的路网里最大的那块）的编号 */
    public int mainComponent() {
        ensureComp();
        return mainComp;
    }

    /** 该格是不是在主连通块里 */
    public boolean inMain(int x, int y) {
        int c = component(x, y);
        return c >= 0 && c == mainComp;
    }

    /** 主块有多少格（≈ 多少米，乘以 metersPerCell） */
    public int mainComponentSize() {
        ensureComp();
        return mainComp >= 0 ? compSize[mainComp] : 0;
    }

    /**
     * 吸到最近的**主连通块**格 —— 起跑点用这个，A* 就一定走得通。
     *
     * 和 {@link #snap} 的区别：snap 只看「是不是路」，吸到孤岛上就废了；
     * 这里只认能跟打卡点连通的那些格。半径给大一点（200 格 ≈ 383 m）也没关系，
     * 因为里面每一格都是可用的。
     *
     * @return 主块格坐标；半径内没有就返回 null
     */
    public int[] snapMain(int x, int y, int maxr) {
        ensureComp();
        if (mainComp < 0) return null;
        if (inMain(x, y)) return new int[]{x, y};
        for (int r = 1; r < maxr; r++) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI * 2 / 24;
                int nx = x + (int) Math.round(Math.cos(ang) * r);
                int ny = y + (int) Math.round(Math.sin(ang) * r);
                if (inMain(nx, ny)) return new int[]{nx, ny};
            }
        }
        return null;
    }

    /**
     * 用「离给定点最近」的方式在主块里挑一个落点 —— 起跑点被挪动太远时用它兜底。
     * 与 snapMain 同义，单独留个名字是为了调用处读起来清楚。
     */
    public int[] nearestMain(double lat, double lon, int maxr) {
        int[] c = toCell(lat, lon);
        return snapMain(c[0], c[1], maxr);
    }

    /** 射线法：点 (lat,lon) 是否在多边形 poly（{lat,lon} 顶点）内 */
    public static boolean pointInPoly(double lat, double lon, List<double[]> poly) {
        boolean in = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly.get(i)[0], xi = poly.get(i)[1];
            double yj = poly.get(j)[0], xj = poly.get(j)[1];
            if (((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                in = !in;
            }
        }
        return in;
    }

    /** 把多边形外的点沿「质心 → 该点」方向拉回边界内（用于打卡点恰好压在跑区边上时） */
    public static double[] clampInto(double lat, double lon, List<double[]> poly) {
        if (poly == null || poly.size() < 3 || pointInPoly(lat, lon, poly))
            return new double[]{lat, lon};
        double clat = 0, clon = 0;
        for (double[] p : poly) { clat += p[0]; clon += p[1]; }
        clat /= poly.size(); clon /= poly.size();
        double lo = 0, hi = 1;
        for (int it = 0; it < 28; it++) {
            double mid = (lo + hi) / 2;
            if (pointInPoly(clat + (lat - clat) * mid, clon + (lon - clon) * mid, poly)) lo = mid;
            else hi = mid;
        }
        return new double[]{clat + (lat - clat) * lo, clon + (lon - clon) * lo};
    }

    public int[] toCell(double lat, double lon) {
        int x = (int) ((lon - lon0) / (lon1 - lon0) * w);
        int y = (int) ((lat0 - lat) / (lat0 - lat1) * h);
        if (x < 0) x = 0; if (x >= w) x = w - 1;
        if (y < 0) y = 0; if (y >= h) y = h - 1;
        return new int[]{x, y};
    }

    public double[] toLL(int x, int y) {
        return new double[]{
                lat0 - (y + 0.5) / h * (lat0 - lat1),
                lon0 + (x + 0.5) / w * (lon1 - lon0)};
    }

    /** 把点吸到最近的道路格 —— 打卡点常在人行道/广场上，不在车行道正中间 */
    public int[] snap(int x, int y, int maxr) {
        if (road(x, y)) return new int[]{x, y};
        for (int r = 1; r < maxr; r++) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI * 2 / 24;
                int nx = x + (int) Math.round(Math.cos(ang) * r);
                int ny = y + (int) Math.round(Math.sin(ang) * r);
                if (road(nx, ny)) return new int[]{nx, ny};
            }
        }
        return null;
    }

    /**
     * ★★★ 2026-10-08「路线有些错位、没有匹配到街道上」的根因修复。
     *
     * 路网格是把高德路网层按「非白即路」提取出来的 —— 拿到的是**道路的填充色块**，
     * 不是中心线。A* 求最短路，自然贴着色块的内侧边缘走。实测（work/edge_check.py，
     * 5 条 4.5 km 真实路线）：处在宽路/路口附近的顶点里，偏离局部中线的
     *   **中位 3.8~5.7 米、均值 4.6~6.5 米、最大 13.4 米**，59~84% 偏 ≥3.8 米。
     * 用户看到的就是「路线没对准街道」。
     *
     * 修法：**不动路网数据**，只改 A* 的代价 —— 给每一格算一个「离局部中线差几格」：
     *   edge[i]     = 该格到最近「不可走格」的格数（多源 4 连通 BFS 距离变换）
     *   localMax[i] = edge 在 9×9 窗口内的最大值（可分离的最大值滤波，两趟）
     *   pull[i]     = clamp(localMax[i] - edge[i], 0, 6)
     * 于是：窄路上 pull 恒为 0（本来就没有中线可走，不引入任何偏差）；
     *       宽路上越靠边 pull 越大 —— 路径自动滑到路中间。
     * 关键：宽路和窄路的**中心线上 pull 都是 0**，所以不存在「为了走大马路而绕远」
     * 的倾向，路线形状基本不变，只是横向归中。
     */
    private byte[] centerPull = null;
    /** 中线代价的权重：pull 每差 1 格，该步代价乘 (1 + CENTER_W) */
    private static final float CENTER_W = 0.5f;
    /** 局部中线的搜索窗口半径（格）—— 也是 pull 的上限 */
    private static final int CENTER_R = 5;

    private void ensureCenterPull() {
        if (centerPull != null) return;
        final int n = w * h, R = CENTER_R;
        byte[] edge = new byte[n];
        int[] q = new int[n];
        int qh = 0, qt = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (road(x, y)) {
                    edge[i] = 100;
                } else {
                    edge[i] = 0;
                    q[qt++] = i;
                }
            }
        }
        // 多源 BFS：单位权 + FIFO ⇒ 每格第一次被赋值就是最短距离，最多入队一次
        while (qh < qt) {
            int i = q[qh++];
            int d = edge[i] + 1;
            if (d > 100) continue;
            int x = i % w, y = i / w;
            if (x > 0 && edge[i - 1] > d && qt < n) { edge[i - 1] = (byte) d; q[qt++] = i - 1; }
            if (x < w - 1 && edge[i + 1] > d && qt < n) { edge[i + 1] = (byte) d; q[qt++] = i + 1; }
            if (y > 0 && edge[i - w] > d && qt < n) { edge[i - w] = (byte) d; q[qt++] = i - w; }
            if (y < h - 1 && edge[i + w] > d && qt < n) { edge[i + w] = (byte) d; q[qt++] = i + w; }
        }
        // 可分离最大值滤波：先横后纵
        byte[] tmp = new byte[n];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int mx = 0, x0 = Math.max(0, x - R), x1 = Math.min(w - 1, x + R);
                for (int xx = x0; xx <= x1; xx++) {
                    int v = edge[row + xx];
                    if (v > mx) mx = v;
                }
                tmp[row + x] = (byte) mx;
            }
        }
        centerPull = new byte[n];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int mx = 0, y0 = Math.max(0, y - R), y1 = Math.min(h - 1, y + R);
                for (int yy = y0; yy <= y1; yy++) {
                    int v = tmp[yy * w + x];
                    if (v > mx) mx = v;
                }
                int pen = mx - edge[y * w + x];
                if (pen < 0) pen = 0;
                if (pen > CENTER_R) pen = CENTER_R;
                centerPull[y * w + x] = (byte) pen;
            }
        }
    }

    /**
     * A* 寻路，8 邻接。
     * ★ 加了「贴路沿惩罚」：4 邻域里道路越少，说明这一格越靠路边缘，
     *   代价抬高一点，路径就会自然走在路中间，不会贴着一边走出锯齿。
     * ★★ 2026-10-08 又叠了一层**中线代价**（见 {@link #ensureCenterPull}）——
     *    旧那层只看 4 邻域，对宽路几乎无效（宽路边缘照样有 3~4 个道路邻居），
     *    这才是「路线偏出街道好几米」的真正原因。
     *
     * ★★ 生成一条路线要调 A* 几十次（每个候选插点都要试），所以这里
     *    **不能**每次 new 几个 69 万长度的数组 —— 那样光 GC 就够呛。
     *    改成实例级的 scratch + 访问戳（visitId），只有戳等于本次的才有效，
     *    省掉了每次 Arrays.fill 的 200 万次写入。
     */
    private int[] sGs;            // 到该点的代价（float 位）
    private int[] sCame;          // 父节点
    private int[] sStamp;         // 访问戳
    private int[] hNode;          // 二叉堆：节点号
    private float[] hF;           // 二叉堆：f 值
    private int visitId = 0;
    private final Object lock = new Object();

    public List<int[]> astar(int[] s, int[] t) {
        if (s == null || t == null) return null;
        if (s[0] == t[0] && s[1] == t[1]) {
            List<int[]> one = new ArrayList<>();
            one.add(s);
            return one;
        }
        synchronized (lock) {
            int n = w * h;
            if (sGs == null) {
                sGs = new int[n];
                sCame = new int[n];
                sStamp = new int[n];
                int cap = Math.min(n, 1 << 20);
                hNode = new int[cap];
                hF = new float[cap];
            }
            final int cap = hNode.length;
            ensureCenterPull();
            final byte[] cp = centerPull;
            visitId++;
            final int vid = visitId;
            final float INF = Float.MAX_VALUE;
            final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
            final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

            int si = s[1] * w + s[0];
            sStamp[si] = vid; sGs[si] = Float.floatToIntBits(0f); sCame[si] = -1;
            hNode[0] = si; hF[0] = hcost(s[0], s[1], t);
            int size = 1;
            final int ti = t[1] * w + t[0];

            while (size > 0) {
                int cur = hNode[0];
                size--;
                if (size > 0) {
                    hNode[0] = hNode[size]; hF[0] = hF[size];
                    int i = 0;
                    while (true) {
                        int l = 2 * i + 1, r = l + 1, m = i;
                        if (l < size && hF[l] < hF[m]) m = l;
                        if (r < size && hF[r] < hF[m]) m = r;
                        if (m == i) break;
                        int tn = hNode[i]; hNode[i] = hNode[m]; hNode[m] = tn;
                        float tf = hF[i]; hF[i] = hF[m]; hF[m] = tf;
                        i = m;
                    }
                }
                if (cur == ti) {
                    List<int[]> path = new ArrayList<>();
                    int c = cur;
                    while (c != -1) {
                        path.add(new int[]{c % w, c / w});
                        c = sCame[c];
                    }
                    java.util.Collections.reverse(path);
                    return path;
                }
                int px = cur % w, py = cur / w;
                float gc = Float.intBitsToFloat(sGs[cur]);
                for (int k = 0; k < 8; k++) {
                    int nx = px + DX[k], ny = py + DY[k];
                    if (!road(nx, ny)) continue;
                    int ni = ny * w + nx;
                    float step = (DX[k] != 0 && DY[k] != 0) ? 1.41421356f : 1.0f;
                    int nb = 0;
                    if (road(nx + 1, ny)) nb++;
                    if (road(nx - 1, ny)) nb++;
                    if (road(nx, ny + 1)) nb++;
                    if (road(nx, ny - 1)) nb++;
                    step *= (1.0f + 0.35f * (4 - nb) / 4.0f);
                    // ★ 中线代价：越靠宽路的边，这一步越贵 —— 路径于是滑到路中间
                    if (cp[ni] > 0) step *= (1.0f + CENTER_W * cp[ni]);
                    float ng = gc + step;
                    if (sStamp[ni] == vid && Float.intBitsToFloat(sGs[ni]) <= ng) continue;
                    sStamp[ni] = vid;
                    sGs[ni] = Float.floatToIntBits(ng);
                    sCame[ni] = cur;
                    if (size >= cap) continue;      // 堆满就丢弃（实测远到不了）
                    int i = size++;
                    hNode[i] = ni; hF[i] = ng + hcost(nx, ny, t);
                    while (i > 0) {
                        int p = (i - 1) >> 1;
                        if (hF[p] <= hF[i]) break;
                        int tn = hNode[p]; hNode[p] = hNode[i]; hNode[i] = tn;
                        float tf = hF[p]; hF[p] = hF[i]; hF[i] = tf;
                        i = p;
                    }
                }
            }
            return null;
        }
    }

    private static float hcost(int x, int y, int[] t) {
        int dx = Math.abs(x - t[0]), dy = Math.abs(y - t[1]);
        return (dx + dy) + 0.41421356f * Math.min(dx, dy);
    }

    /** 相邻路格合并成一段（把 A* 的逐格路径压成线段），顺便抹掉单格抖动 */
    public static List<int[]> collapse(List<int[]> path) {
        List<int[]> out = new ArrayList<>();
        if (path == null || path.isEmpty()) return out;
        out.add(path.get(0));
        int ldx = Integer.MIN_VALUE, ldy = Integer.MIN_VALUE;
        for (int i = 1; i < path.size(); i++) {
            int dx = Integer.signum(path.get(i)[0] - path.get(i - 1)[0]);
            int dy = Integer.signum(path.get(i)[1] - path.get(i - 1)[1]);
            if (dx != ldx || dy != ldy) {
                if (i > 1) out.add(path.get(i - 1));
                ldx = dx; ldy = dy;
            }
        }
        out.add(path.get(path.size() - 1));
        return out;
    }
}
