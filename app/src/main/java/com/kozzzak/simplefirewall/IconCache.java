package com.kozzzak.simplefirewall;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide icon memory cache with background decoding; rows never block on PackageManager. */
final class IconCache {
    private static IconCache instance;
    private final Context context;
    private final int size;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap fallback;

    static synchronized IconCache get(Context c) {
        if (instance == null) instance = new IconCache(c.getApplicationContext());
        return instance;
    }
    private IconCache(Context c) {
        context = c; size = Ui.dp(c, 40);
        int kb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 10, 24 * 1024);
        cache = new LruCache<String, Bitmap>(kb) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; } };
    }

    /** Binds an icon for a package (or a fallback for null); safe for recycled rows. */
    void load(ImageView view, String packageName) {
        String key = packageName == null ? "" : packageName;
        view.setTag(R.id.icon_tag, key);
        Bitmap hit = cache.get(key);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageBitmap(fallback());
        if (packageName == null) return;
        workers.execute(() -> {
            Bitmap bitmap;
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                bitmap = render(info.loadIcon(pm));
            } catch (Exception e) { bitmap = fallback(); }
            Bitmap result = bitmap; cache.put(key, result);
            main.post(() -> { if (key.equals(view.getTag(R.id.icon_tag))) view.setImageBitmap(result); });
        });
    }
    private synchronized Bitmap fallback() {
        if (fallback == null) fallback = render(context.getPackageManager().getDefaultActivityIcon());
        return fallback;
    }
    private Bitmap render(Drawable d) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b); d.setBounds(0, 0, size, size); d.draw(canvas); return b;
    }
}
