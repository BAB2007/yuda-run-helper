package com.ledao.tester;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RouteNet —— 校园路网（预编译），从 {@code assets/routenet.bin} 读进来。
 *
 * <h3>这张图是哪来的</h3>
 * 用户手画的街道笔画（{@code work/draw/graph.json}）经
 * {@code work/route/route_lib.js} 的 {@code buildGraph()} 处理成
 * 「街口 = 节点、街段 = 边」的平面图：笔画求交 → 切边 → 合并街口 →
 * 焊几何 → 嫁接断头路 → 去重边 → 打卡点接到最近的街上。
 * 1683 个街口 / 2871 条街段 / 59.5 km / 单连通。
 *
 * <h3>为什么把建图留在 Node 里，只把结果打进 APK</h3>
 * 建图是整条链上最容易出玄学的一段（求交的 t/u、切边的退化、
 * 焊几何的顺序都踩过坑），而它**只在你改笔画时才需要重跑**。
 * 预编译之后 Java 侧只剩「读图 + 选路」，两端代码量都砍一半，
 * 也不用担心两版建图算法悄悄跑偏。
 * 重新生成：{@code cd work/route && node mkasset.js}（133 KB）。
 *
 * <h3>★ 和 work/route/netasset.js 是同一份格式的两个读取器</h3>
 * 改任何一边，另一边必须同步，否则 Node 侧的验收结果就不能代表 App 的行为。
 * 两边共同遵守的硬约束：
 * <ul>
 *   <li>街口坐标按 float32 读 —— 2000 m 尺度上舍入误差 ≈ 6e-5 m，可忽略</li>
 *   <li>街段长度 {@code len} 和 {code cum} **不存盘**，从点重算。
 *       存盘再读会因为"存的是另一批数"而对不上；重算能保证两端拿到同一批数。</li>
 *   <li>邻接表按边下标升序展开 —— Dijkstra 的平局弹出顺序依赖入堆顺序，
 *       顺序不一致两端就对不上了</li>
 * </ul>
 *
 * 坐标系：**米**，y 向下（与地图像素同向）。经度方向按 cos(纬度) 校正，
 * x/y 各自标定（地图宽高比不等于经纬度比，共用一个系数会差 0.5%）。
 */
public class RouteNet {

    private static final int MAGIC = 0x4E52444C;      // "LDRN"
    private static final int VERSION = 2;
    private static final int HDR = 72;

    public static final double M_PER_DEG_LAT = 111132.0;

    // ---- 几何 ----
    public final float[] nodeX, nodeY;                 // 街口坐标（米）
    public final int[] edgeA, edgeB, edgeOff, edgeCnt; // 街段：两端街口 + 折线点在摊平数组里的位置
    public final float[] ptX, ptY;                     // 全部折线点，摊平
    public final double[] edgeLen;                     // 街段长度（从点重算）
    public final double[] cumFlat;                     // 摊平的"边内累计里程"，每条边自己从 0 起

    // ---- 拓扑（CSR：节点 -> 出边） ----
    public final int[] adjOff;                         // 长度 nNodes+1
    public final int[] adjEdge;                        // 长度 nEdges*2

    // ---- 打卡点 ----
    public final int[] cpN, cpNode;
    public final String[] cpId;
    public final double[] cpLon, cpLat;
    public final float[] cpX, cpY;

    // ---- 经纬度换算 ----
    public final double lonLeft, latTop, lonSpan, latSpan, widthPx, heightPx;
    public final double mx, my;

    public final int nNodes, nEdges, nPts, nCps;

    private RouteNet(ByteBuffer b) {
        lonLeft = b.getDouble();
        latTop = b.getDouble();
        lonSpan = b.getDouble();
        latSpan = b.getDouble();
        widthPx = b.getDouble();
        heightPx = b.getDouble();
        nNodes = b.getInt();
        nEdges = b.getInt();
        nPts = b.getInt();
        nCps = b.getInt();

        double latMid = latTop - latSpan / 2.0;
        double mPerDegLon = 111320.0 * Math.cos(latMid * Math.PI / 180.0);
        mx = lonSpan * mPerDegLon / widthPx;
        my = latSpan * M_PER_DEG_LAT / heightPx;

        nodeX = new float[nNodes];
        nodeY = new float[nNodes];
        for (int i = 0; i < nNodes; i++) { nodeX[i] = b.getFloat(); nodeY[i] = b.getFloat(); }

        edgeA = new int[nEdges];
        edgeB = new int[nEdges];
        edgeOff = new int[nEdges];
        edgeCnt = new int[nEdges];
        for (int e = 0; e < nEdges; e++) {
            edgeA[e] = b.getInt();
            edgeB[e] = b.getInt();
            edgeOff[e] = b.getInt();
            edgeCnt[e] = b.getInt();
        }

        ptX = new float[nPts];
        ptY = new float[nPts];
        for (int i = 0; i < nPts; i++) { ptX[i] = b.getFloat(); ptY[i] = b.getFloat(); }
        // 边的首尾强制钉在两端街口的坐标上（建图时就是这么焊的），
        // 保证"边的端点"和"街口坐标"永远是同一个数。
        for (int e = 0; e < nEdges; e++) {
            int o = edgeOff[e], n = edgeCnt[e];
            ptX[o] = nodeX[edgeA[e]]; ptY[o] = nodeY[edgeA[e]];
            ptX[o + n - 1] = nodeX[edgeB[e]]; ptY[o + n - 1] = nodeY[edgeB[e]];
        }

        edgeLen = new double[nEdges];
        cumFlat = new double[nPts];
        for (int e = 0; e < nEdges; e++) {
            int o = edgeOff[e], n = edgeCnt[e];
            cumFlat[o] = 0;
            for (int i = 1; i < n; i++) {
                double dx = ptX[o + i] - ptX[o + i - 1];
                double dy = ptY[o + i] - ptY[o + i - 1];
                cumFlat[o + i] = cumFlat[o + i - 1] + Math.sqrt(dx * dx + dy * dy);
            }
            edgeLen[e] = cumFlat[o + n - 1];
        }

        adjOff = new int[nNodes + 1];
        for (int i = 0; i <= nNodes; i++) adjOff[i] = b.getInt();
        adjEdge = new int[Math.max(1, nEdges * 2)];
        for (int i = 0; i < nEdges * 2; i++) adjEdge[i] = b.getInt();

        int n = b.getInt();
        cpN = new int[n];
        cpNode = new int[n];
        cpId = new String[n];
        cpLon = new double[n];
        cpLat = new double[n];
        cpX = new float[n];
        cpY = new float[n];
        for (int i = 0; i < n; i++) {
            cpN[i] = b.getInt();
            cpNode[i] = b.getInt();
            cpLon[i] = b.getDouble();
            cpLat[i] = b.getDouble();
            cpX[i] = b.getFloat();
            cpY[i] = b.getFloat();
            int idLen = b.getInt();
            byte[] id = new byte[idLen];
            b.get(id);
            cpId[i] = new String(id, java.nio.charset.Charset.forName("UTF-8"));
        }
        if (n != nCps) throw new IllegalStateException("打卡点数量对不上");
    }

    /** 某个街口的第 k 条出边连到哪个街口 */
    public int adjTo(int u, int k) {
        int e = adjEdge[adjOff[u] + k];
        return edgeA[e] == u ? edgeB[e] : edgeA[e];
    }

    // ==================================================================
    //  最短路
    // ==================================================================

    /** 一次 Dijkstra 的结果（与 route_lib.js 的返回结构一一对应） */
    public static final class SP {
        public final double[] dist;
        public final int[] prev, prevEdge;
        SP(double[] d, int[] p, int[] pe) { dist = d; prev = p; prevEdge = pe; }
    }

    /**
     * 最短路缓存。★ 加了上限：一条路线会为每个"凑里程目标点"算一次反查最短路
     * （最多 {@code Opts.wpMax} = 10 次），而每条 {@code SP} 是 1683 个 double +
     * 两个 int[1683] ≈ 27 KB。无上限时反复预览会一直堆着 ——
     * 放不下就直接丢弃整个缓存（Dijkstra 一次只几毫秒，不值得为它做 LRU）。
     */
    private static final int SP_CACHE_MAX = 32;
    private final java.util.HashMap<Integer, SP> spCache = new java.util.HashMap<>();
    private double[] densCache;
    private double densCacheR = -1;

    /**
     * 从 src 出发到全图的最短路。
     *
     * <p>结果按源点缓存 —— 1000 条路线里打卡点的最短路其实只会算十来次，
     * 这个缓存是"每条路线几毫秒"的主要来源。
     *
     * <p>★ 平局处理必须和 route_lib.js 一致：那边是
     * {@code if (nd < dist[v] - 1e-9)}，即"新距离不比旧的小 1e-9 就不更新"。
     * 少了这个 epsilon，等价路径会被反复入堆，两端的路径链可能不同。
     */
    public SP dijkstra(int src) {
        SP hit = spCache.get(src);
        if (hit != null) return hit;
        int n = nNodes;
        double[] dist = new double[n];
        int[] prev = new int[n], prevEdge = new int[n];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(prev, -1);
        java.util.Arrays.fill(prevEdge, -1);
        boolean[] done = new boolean[n];
        RoutePlan.MinHeap heap = new RoutePlan.MinHeap();
        dist[src] = 0;
        heap.push(0, src);
        while (heap.n > 0) {
            heap.pop();
            int u = heap.rv;
            if (done[u]) continue;
            done[u] = true;
            int off = adjOff[u], cnt = adjOff[u + 1] - off;
            double du = dist[u];
            for (int i = 0; i < cnt; i++) {
                int e = adjEdge[off + i];
                int v = adjTo(u, i);
                if (done[v]) continue;
                double nd = du + edgeLen[e];
                if (nd < dist[v] - 1e-9) {
                    dist[v] = nd; prev[v] = u; prevEdge[v] = e;
                    heap.push(nd, v);
                }
            }
        }
        SP sp = new SP(dist, prev, prevEdge);
        if (spCache.size() >= SP_CACHE_MAX) spCache.clear();     // 见 SP_CACHE_MAX 的注释
        spCache.put(src, sp);
        return sp;
    }

    /**
     * 每个街口在 radiusM 内有多少个街口 —— 起点权重用。
     * 街口密的地方（路网交叉密集区）成为起点的概率高，正是用户要的
     * 「node 节点多的地方起始点概率高」。
     */
    public double[] nodeDensity(double radiusM) {
        double R = radiusM > 0 ? radiusM : 120;
        if (densCache != null && densCacheR == R) return densCache;
        double cell = R;
        java.util.HashMap<Integer, java.util.ArrayList<Integer>> grid = new java.util.HashMap<>();
        for (int i = 0; i < nNodes; i++) {
            int k = cellKey(nodeX[i] / cell, nodeY[i] / cell);
            java.util.ArrayList<Integer> b = grid.get(k);
            if (b == null) { b = new java.util.ArrayList<>(4); grid.put(k, b); }
            b.add(i);
        }
        double[] out = new double[nNodes];
        double r2 = R * R;
        for (int n = 0; n < nNodes; n++) {
            double px = nodeX[n], py = nodeY[n];
            int ix2 = (int) Math.floor(px / cell), iy2 = (int) Math.floor(py / cell);
            int cnt = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    java.util.ArrayList<Integer> b = grid.get((ix2 + dx) * 8192 + (iy2 + dy));
                    if (b == null) continue;
                    for (int q = 0; q < b.size(); q++) {
                        int o = b.get(q);
                        double ddx = nodeX[o] - px, ddy = nodeY[o] - py;
                        if (ddx * ddx + ddy * ddy <= r2) cnt++;
                    }
                }
            }
            out[n] = cnt;
        }
        densCache = out;
        densCacheR = R;
        return out;
    }

    static int cellKey(double fx, double fy) {
        return ((int) Math.floor(fx)) * 8192 + ((int) Math.floor(fy));
    }

    /** 按 point_id 找打卡点下标；找不到返回 -1 */
    public int indexOfPid(String pid) {
        if (pid == null) return -1;
        String t = pid.trim();
        for (int i = 0; i < nCps; i++) if (cpId[i].equals(t)) return i;
        return -1;
    }

    /** 按打卡点序号 n（1..14，即 GetZonePoints 的顺序）找下标；找不到返回 -1 */
    public int indexOfN(int n) {
        for (int i = 0; i < nCps; i++) if (cpN[i] == n) return i;
        return -1;
    }

    /** 米 -> 经纬度 */
    public double[] m2ll(double x, double y) {
        return new double[]{
                latTop - (y / my) / heightPx * latSpan,
                lonLeft + (x / mx) / widthPx * lonSpan,
        };
    }

    /** 经纬度 -> 米 */
    public double[] ll2m(double lon, double lat) {
        return new double[]{
                (lon - lonLeft) / lonSpan * widthPx * mx,
                (latTop - lat) / latSpan * heightPx * my,
        };
    }

    // ==================================================================
    //  给地图用：整张路网摊成一条折线
    // ==================================================================

    /**
     * 全部街段的折线点，按 [纬度, 经度] 交替摊平（长度 nPts*2）。
     * 配合 {@link #netBreaks()} 用：每条街段是一段独立的子折线。
     */
    public float[] netLatLon() {
        float[] out = new float[nPts * 2];
        for (int i = 0; i < nPts; i++) {
            out[2 * i] = (float) (latTop - (ptY[i] / my) / heightPx * latSpan);
            out[2 * i + 1] = (float) (lonLeft + (ptX[i] / mx) / widthPx * lonSpan);
        }
        return out;
    }

    /** 每条街段在 {@link #netLatLon()} 里的起始下标，末尾补一个 nPts 当哨兵。 */
    public int[] netBreaks() {
        int[] out = new int[nEdges + 1];
        for (int e = 0; e < nEdges; e++) out[e] = edgeOff[e];
        out[nEdges] = nPts;
        return out;
    }

    /** 街口坐标摊成 [纬度, 经度]（画街口小圆点用），长度 nNodes*2 */
    public float[] nodeLatLon() {
        float[] out = new float[nNodes * 2];
        for (int i = 0; i < nNodes; i++) {
            out[2 * i] = (float) (latTop - (nodeY[i] / my) / heightPx * latSpan);
            out[2 * i + 1] = (float) (lonLeft + (nodeX[i] / mx) / widthPx * lonSpan);
        }
        return out;
    }

    // ==================================================================
    //  加载
    // ==================================================================
    public static RouteNet load(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("routenet.bin");
            byte[] b = readAll(in);
            in.close();
            return loadFromBytes(b);
        } catch (Throwable t) {
            android.util.Log.w("LedaoTester", "RouteNet.load 失败: " + Err.one(t));
            return null;
        }
    }

    public static RouteNet loadFromFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        byte[] b = readAll(in);
        in.close();
        return loadFromBytes(b);
    }

    public static RouteNet loadFromBytes(byte[] b) {
        if (b.length < HDR) throw new IllegalStateException("routenet.bin 太短");
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        if (magic != MAGIC) throw new IllegalStateException("不是 routenet.bin（magic 不符）");
        int ver = bb.getInt();
        if (ver != VERSION)
            throw new IllegalStateException("routenet.bin 版本 " + ver + "，这版 App 要 " + VERSION);
        return new RouteNet(bb);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 17);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Override
    public String toString() {
        double L = 0;
        for (double d : edgeLen) L += d;
        return String.format(java.util.Locale.US,
                "路网 %d 街口 / %d 街段 / %.1f km / %d 打卡点",
                nNodes, nEdges, L / 1000.0, nCps);
    }
}

