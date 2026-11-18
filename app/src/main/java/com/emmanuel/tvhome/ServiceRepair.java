package com.emmanuel.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped or reinstalled,
 * causing remote Home, Input, Settings, and custom app buttons to stop working until helpers are re-enabled.
 * With restore_key_services enabled and WRITE_SECURE_SETTINGS granted, Home ensures both HomeKeyService and
 * InputKeyService (plus the GuardRevival notification listener) are recorded, enabled, and bound on launch/resume.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";
    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";
    private static final long REPAIR_COOLDOWN_MS = 10_000L;
    private static volatile long lastRepairMs = Long.MIN_VALUE;

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = context.getContentResolver();
            restoreNotificationListener(cr, context);

            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<ComponentName> enabled = parseComponents(list);
            List<ComponentName> recorded = collectServices(context);

            // The list without our recorded helpers, then with all of them.
            StringBuilder others = new StringBuilder();
            for (ComponentName name : enabled) {
                if (recorded.contains(name)) continue;
                if (others.length() > 0) others.append(':');
                others.append(name.flattenToShortString());
            }
            StringBuilder updated = new StringBuilder(others);
            boolean missing = false;
            for (ComponentName name : recorded) {
                if (!enabled.contains(name)) missing = true;
                if (updated.length() > 0) updated.append(':');
                updated.append(name.flattenToShortString());
            }

            boolean a11yOff = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1;
            boolean unbound = HomeKeyService.bound() == null || InputKeyService.bound() == null;
            if (!missing && !a11yOff && !unbound) return;

            long nowMs = SystemClock.elapsedRealtime();
            if (!missing && !a11yOff && lastRepairMs != Long.MIN_VALUE && nowMs - lastRepairMs < REPAIR_COOLDOWN_MS) {
                return;
            }
            lastRepairMs = nowMs;

            // A killed helper also lands on Android's "Crashed services" list (dumpsys accessibility), and a
            // crashed service is never bound again, so putting it back on the list alone binds nothing and the
            // switch falls back to 0. Android drops the crashed mark when it sees the helper leave the enabled
            // list, so write -> remove -> write off the main thread.
            final String without = others.toString();
            final String with = updated.toString();
            new Thread(() -> {
                try {
                    if (!replaceList(cr, list, with)) return;
                    Thread.sleep(1000);
                    if (!replaceList(cr, with, without)) return;
                    Thread.sleep(1000);
                    if (!replaceList(cr, without, with)) return;
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (RuntimeException | InterruptedException ignored) {
                    // The setting is the system's; a refusal leaves the helper off, as before.
                }
            }, "ServiceRepair").start();
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the helper off, as before.
        }
    }

    private static List<ComponentName> collectServices(Context context) {
        SharedPreferences p = prefs(context);
        Class<?>[] required = {HomeKeyService.class, InputKeyService.class};
        SharedPreferences.Editor editor = null;
        for (Class<?> cls : required) {
            if (!p.getBoolean(cls.getName(), false)) {
                if (editor == null) editor = p.edit();
                editor.putBoolean(cls.getName(), true);
            }
        }
        if (editor != null) editor.apply();

        List<ComponentName> recorded = new ArrayList<>();
        for (Class<?> cls : required) {
            recorded.add(new ComponentName(context, cls));
        }
        for (String service : p.getAll().keySet()) {
            ComponentName cn = new ComponentName(context.getPackageName(), service);
            if (!recorded.contains(cn)) recorded.add(cn);
        }
        return recorded;
    }

    private static void restoreNotificationListener(ContentResolver cr, Context context) {
        try {
            ComponentName guard = new ComponentName(context, GuardRevival.class);
            String raw = Settings.Secure.getString(cr, ENABLED_NOTIFICATION_LISTENERS);
            List<ComponentName> current = parseComponents(raw);
            if (current.contains(guard)) return;
            String entry = guard.flattenToString();
            String next = (raw == null || raw.trim().isEmpty()) ? entry : raw.trim() + ":" + entry;
            Settings.Secure.putString(cr, ENABLED_NOTIFICATION_LISTENERS, next);
        } catch (RuntimeException ignored) {
            // Keep accessibility repair resilient even on firmware restricting listener writes.
        }
    }

    private static List<ComponentName> parseComponents(String raw) {
        List<ComponentName> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        for (String entry : raw.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (name != null) list.add(name);
        }
        return list;
    }

    /** Writes the enabled list only while it still reads {@code expected}; an absent list counts as empty. */
    private static boolean replaceList(ContentResolver cr, String expected, String updated) {
        String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Objects.equals(now == null ? "" : now, expected == null ? "" : expected)
                && Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
