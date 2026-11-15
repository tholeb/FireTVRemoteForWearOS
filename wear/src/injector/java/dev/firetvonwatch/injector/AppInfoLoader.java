package dev.firetvonwatch.injector;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

/**
 * Loads an installed app's label and icon on the TV without a Context: application info
 * from the package service, then the app's own resources through an AssetManager on its APK.
 * Labels come out in the TV's language. Icons are rendered to a small PNG for the watch.
 */
final class AppInfoLoader {
    private static final int ICON_PX = 96;
    private static final int DENSITY = DisplayMetrics.DENSITY_XHIGH;

    private final Object packageManager;
    private final Method getApplicationInfo;

    AppInfoLoader() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "package");
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        packageManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method found = null;
        // (String, int flags, int userId) before Android 13; (String, long flags, int userId) after.
        for (Method m : packageManager.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("getApplicationInfo") && p.length == 3 && p[0] == String.class) {
                found = m;
                break;
            }
        }
        if (found == null) throw new NoSuchMethodException("getApplicationInfo");
        getApplicationInfo = found;
    }

    /** Reply line: "I <base64 label> <base64 png or ->". */
    String describe(String packageName) throws Exception {
        Object flags = getApplicationInfo.getParameterTypes()[1] == long.class ? (Object) 0L : (Object) 0;
        ApplicationInfo info = (ApplicationInfo) getApplicationInfo.invoke(packageManager, packageName, flags, 0);
        if (info == null) throw new IllegalArgumentException("not installed: " + packageName);

        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, info.sourceDir);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        metrics.densityDpi = DENSITY;
        Configuration config = new Configuration(Resources.getSystem().getConfiguration());
        config.densityDpi = DENSITY;
        Resources res = new Resources(assets, metrics, config);

        String label = packageName;
        if (info.nonLocalizedLabel != null) {
            label = info.nonLocalizedLabel.toString();
        } else if (info.labelRes != 0) {
            try {
                label = res.getString(info.labelRes);
            } catch (Exception ignored) {
                // keep package name
            }
        }

        String png = "-";
        int[] candidates = {info.icon, info.banner, info.logo};
        for (int id : candidates) {
            if (id == 0) continue;
            try {
                png = render(res.getDrawableForDensity(id, DENSITY, null));
                break;
            } catch (Throwable ignored) {
                // try the next drawable (e.g. themed attributes we can't resolve)
            }
        }
        assets.close();
        return "I " + b64(label.getBytes("UTF-8")) + " " + png;
    }

    private static String render(Drawable drawable) {
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        // Keep the aspect ratio (TV banners are wide) inside ICON_PX.
        float scale = (w > 0 && h > 0) ? Math.min((float) ICON_PX / w, (float) ICON_PX / h) : 1f;
        int bw = (w > 0 && h > 0) ? Math.max(1, Math.round(w * scale)) : ICON_PX;
        int bh = (w > 0 && h > 0) ? Math.max(1, Math.round(h * scale)) : ICON_PX;
        Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, bw, bh);
        drawable.draw(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return b64(out.toByteArray());
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
