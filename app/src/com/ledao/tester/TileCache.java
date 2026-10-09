package com.ledao.tester;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TileCache —— 高德栅格瓦片的「内存 → 磁盘 → 网络」三级缓存。
 *
 * 为什么用高德：步道乐跑自己就是高德底图（它上传的跑步截图就是高德瓦片拼的），
 * 所以服务端下发的打卡点坐标可以直接铺在高德瓦片上，一次对齐，不用做坐标纠偏。
 *
 * 离线也能用：走过的区域会落在磁盘缓存里，下次打开直接读本地。
 */
public class TileCache {

    public static final int TILE = 256;
    /** style=7 是高德的标准地图（带路名和 POI），和步道乐跑里看到的一致 */
    private static final String URL_FMT =
            "https://wprd0%d.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x=%d&y=%d&z=%d";

    private final File dir;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(160) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b == null ? 0 : b.getByteCount() / 1024; }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> inflight = Collections.synchronizedSet(new HashSet<String>());
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean networkOK = true;

    public TileCache(Context ctx) {
        File base = ctx.getExternalCacheDir();
        if (base == null) base = ctx.getCacheDir();
        dir = new File(base, "amap");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private static String key(int z, int x, int y) { return z + "/" + x + "/" + y; }

    /** 只看缓存，不发网络请求 —— 供 onDraw 同步调用 */
    public Bitmap peek(int z, int x, int y) {
        if (z < 3 || z > 19) return null;
        int n = 1 << z;
        if (x < 0 || y < 0 || x >= n || y >= n) return null;
        String k = key(z, x, y);
        Bitmap b = mem.get(k);
        if (b != null) return b;
        File f = new File(dir, z + "_" + x + "_" + y + ".png");
        if (f.exists()) {
            try {
                Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (d != null) { mem.put(k, d); return d; }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Throwable ignore) { }
        }
        return null;
    }

    /** 请求一张瓦片；拿到后回主线程调 done，让 View 重绘 */
    public void request(final int z, final int x, final int y, final Runnable done) {
        if (peek(z, x, y) != null) return;
        int n = 1 << z;
        if (z < 3 || z > 19 || x < 0 || y < 0 || x >= n || y >= n) return;
        final String k = key(z, x, y);
        if (!inflight.add(k)) return;
        pool.execute(() -> {
            try {
                Bitmap b = download(z, x, y);
                if (b != null) {
                    mem.put(k, b);
                    File f = new File(dir, z + "_" + x + "_" + y + ".png");
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    } catch (Throwable ignore) { }
                    ui.post(done);
                }
            } finally {
                inflight.remove(k);
            }
        });
    }

    private Bitmap download(int z, int x, int y) {
        if (!networkOK) return null;
        HttpURLConnection c = null;
        try {
            URL u = new URL(String.format(java.util.Locale.US, URL_FMT,
                    ((x + y) % 4) + 1, x, y, z));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
            c.setRequestProperty("Referer", "https://www.amap.com/");
            int code = c.getResponseCode();
            if (code != 200) { networkOK = false; return null; }
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 把经纬度换成高德瓦片编号 */
    public static int[] tileOf(double lat, double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double s = Math.sin(Math.toRadians(lat));
        int y = (int) Math.floor((0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n);
        return new int[]{x, y};
    }
}
