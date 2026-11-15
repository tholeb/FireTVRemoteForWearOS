package dev.firetvonwatch.injector;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * Key injector that runs on the Fire TV, started by the watch through ADB:
 * {@code CLASSPATH=<dex> app_process /system/bin dev.firetvonwatch.injector.Injector}.
 *
 * It runs as the ADB shell user, which is allowed to inject input. Staying alive saves the
 * ~0.5 s JVM start that each {@code input keyevent} costs on Android 11 and older.
 *
 * Line protocol on stdin/stdout:
 *   start-up        -> "READY 1"   (or "ERR ..." and exit)
 *   "K 19 19 23"    -> "OK"        (key codes injected in order, down + up each)
 *   "I com.foo"     -> "I <base64 label> <base64 png | ->"   (see AppInfoLoader)
 *   "PING"          -> "PONG"
 *   anything else   -> "ERR ..."
 * Exits when stdin closes (i.e. when the ADB connection or stream goes away).
 */
public final class Injector {
    static final int PROTOCOL_VERSION = 2;
    private static final int INJECT_MODE_ASYNC = 0;

    private final Object inputManager;
    private final Method injectMethod;

    private Injector(Object inputManager, Method injectMethod) {
        this.inputManager = inputManager;
        this.injectMethod = injectMethod;
    }

    /** Android 14 moved injection to InputManagerGlobal; older releases use InputManager. */
    private static Injector create() throws Exception {
        String[] classes = {"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"};
        Exception last = null;
        for (String name : classes) {
            try {
                Class<?> cls = Class.forName(name);
                Object instance = cls.getDeclaredMethod("getInstance").invoke(null);
                Method inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
                return new Injector(instance, inject);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private void key(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        injectMethod.invoke(inputManager, event(now, KeyEvent.ACTION_DOWN, code), INJECT_MODE_ASYNC);
        injectMethod.invoke(inputManager, event(now, KeyEvent.ACTION_UP, code), INJECT_MODE_ASYNC);
    }

    private static KeyEvent event(long time, int action, int code) {
        return new KeyEvent(time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    /** Parses "K 19 20" into key codes; null if the line is malformed. */
    static int[] parseKeys(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !parts[0].equals("K")) return null;
        int[] codes = new int[parts.length - 1];
        for (int i = 1; i < parts.length; i++) {
            try {
                codes[i - 1] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (codes[i - 1] <= 0 || codes[i - 1] > 400) return null;
        }
        return codes;
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        Injector injector;
        try {
            injector = create();
        } catch (Throwable t) {
            out.println("ERR init " + t);
            out.flush();
            return;
        }
        AppInfoLoader appInfo = null;
        try {
            appInfo = new AppInfoLoader();
        } catch (Throwable ignored) {
            // Keys still work; "I" requests will answer ERR.
        }
        out.println("READY " + PROTOCOL_VERSION);
        out.flush();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equals("PING")) {
                    out.println("PONG");
                } else if (trimmed.startsWith("I ")) {
                    try {
                        if (appInfo == null) throw new IllegalStateException("app info unavailable");
                        out.println(appInfo.describe(trimmed.substring(2).trim()));
                    } catch (Throwable t) {
                        out.println("ERR info " + t.getClass().getSimpleName());
                    }
                } else {
                    int[] codes = parseKeys(line);
                    if (codes == null) {
                        out.println("ERR bad command");
                    } else {
                        try {
                            for (int code : codes) injector.key(code);
                            out.println("OK");
                        } catch (Throwable t) {
                            out.println("ERR inject " + t);
                        }
                    }
                }
                out.flush();
            }
        } catch (Throwable ignored) {
            // stdin closed: the watch disconnected.
        }
    }
}
