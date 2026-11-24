package pl.micekwidget;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CoverImages {
    private final ExecutorService executor;
    private final LruCache<String, Bitmap> memory;
    private final List<ImageView> visible = new ArrayList<>();
    private final int pixels;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile int generation;

    CoverImages(boolean efficient) {
        executor = Executors.newFixedThreadPool(efficient ? 1 : 2);
        pixels = efficient ? 64 : 128;
        memory = new LruCache<String, Bitmap>(efficient ? 512 * 1024 : 2 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        };
    }

    void show(String link, ImageView target) {
        if (link.isEmpty()) return;
        visible.add(target);
        Bitmap cached = memory.get(link);
        if (cached != null) { target.setImageBitmap(cached); return; }
        int token = generation;
        executor.execute(() -> {
            if (token != generation) return;
            Bitmap image = download(link);
            main.post(() -> {
                if (token != generation || image == null) return;
                memory.put(link, image);
                target.setImageBitmap(image);
            });
        });
    }

    private Bitmap download(String link) {
        java.net.HttpURLConnection connection = null;
        try {
            URL address = new URL(link);
            if (!"https".equals(address.getProtocol()) || !"i.scdn.co".equalsIgnoreCase(address.getHost())
                    || (address.getPort() != -1 && address.getPort() != 443)) return null;
            connection = (java.net.HttpURLConnection) address.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setReadTimeout(4500);
            connection.setConnectTimeout(4500);
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int length;
                while ((length = in.read(chunk)) != -1) {
                    if (buffer.size() + length > 512 * 1024) return null;
                    buffer.write(chunk, 0, length);
                }
                bytes = buffer.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > pixels * 2) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (java.io.IOException ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void clear() {
        generation++;
        for (ImageView image : visible) image.setImageDrawable(null);
        visible.clear();
        memory.evictAll();
    }

    void close() {
        clear();
        executor.shutdownNow();
    }
}
