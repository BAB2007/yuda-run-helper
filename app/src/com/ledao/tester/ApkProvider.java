package com.ledao.tester;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * ApkProvider —— 把自己下载到 cache 里的 APK 交给系统安装器。
 *
 * <h3>为什么要自己写一个（而不是用 androidx 的 FileProvider）</h3>
 * <p>本工程是 aapt2 + javac + d8 手搓的，**没有任何依赖**（没有 Gradle，也就没有 androidx）。
 * 而 Android 7.0 起 {@code file://} 一律被 {@code FileUriExposedException} 拦下，
 * 装 APK 必须给安装器一个 {@code content://}。FileProvider 的全部工作其实就两件：
 * 「按名字打开一个文件」+「靠临时授权让别的进程读到它」—— 后者是系统的机制
 * （{@code FLAG_GRANT_READ_URI_PERMISSION}），前者三十行就够。所以这里自己实现。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>只在 {@code cacheDir/apk/} 里找文件，文件名必须是 {@code *.apk} 且只含
 *       {@code [A-Za-z0-9._-]} —— 不让 {@code ../} 这类路径跑出目录。</li>
 *   <li>{@code exported="false"}：没有临时授权的第三方应用读不到任何东西。</li>
 *   <li>只读，没有写接口（insert/delete/update 直接抛）。</li>
 * </ul>
 */
public class ApkProvider extends ContentProvider {

    /** 和 AndroidManifest.xml 里 provider 的 authorities 必须**逐字一致**。 */
    public static final String AUTH = "com.ledao.tester.apk";

    private static final String MIME = "application/vnd.android.package-archive";

    /** 下载目录（Updater 往这里写，这里往外套）。 */
    public static File dir(Context c) {
        return new File(c.getCacheDir(), "apk");
    }

    public static Uri uriOf(String name) {
        return Uri.parse("content://" + AUTH + "/" + name);
    }

    /** 只认自己下出来的那种名字（同时挡住路径穿越）。 */
    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9._-]{1,80}\\.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!validName(name)) throw new FileNotFoundException("非法文件名：" + name);
        File f = new File(dir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException("还没下完：" + f.getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    /**
     * 安装器会先问一句"这东西多大、叫什么"（{@link OpenableColumns}），
     * 给了它就不必把整个 APK 读一遍。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        String name = uri.getLastPathSegment();
        if (!validName(name)) return null;
        File f = new File(dir(getContext()), name);
        MatrixCursor c = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{name, f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException("只读");
    }
}
