package com.s9local.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against a real Android framework and WebView, without granting BLE permissions. */
public class StartupInstrumentation extends Instrumentation {
    private Activity activity;
    private WebView web;
    private int passed;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void launch() throws Exception {
        Intent intent = new Intent().setComponent(new ComponentName("com.s9local.app", "com.s9local.app.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(intent);
        java.lang.reflect.Field field = activity.getClass().getDeclaredField("web"); field.setAccessible(true);
        web = (WebView) field.get(activity);
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!"true".equals(js("!!window.S9App"))) {
            if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Local WebView UI did not load");
            SystemClock.sleep(150);
        }
    }
    private String js(String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value); latch.countDown(); }));
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("evaluateJavascript timed out");
        return result.get();
    }
    private void check(String name, String expression) throws Exception {
        if (!"true".equals(js(expression))) throw new AssertionError(name);
        passed++; Bundle progress = new Bundle(); progress.putString("stream", "PASS " + name + "\n"); sendStatus(0, progress);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            launch();
            check("Cold launch with no Bluetooth permission", "document.getElementById('control-light').disabled && document.getElementById('battery-value').textContent === '—'");
            check("Native bridge is available", "typeof S9Native.scan === 'function' && typeof S9Native.saveUnit === 'function'");
            js("document.getElementById('preview-toggle').click()");
            check("Preview can open on Android WebView", "!document.getElementById('preview-banner').hidden && document.getElementById('battery-value').textContent === '82'");
            js("document.getElementById('control-light').click()");
            check("Preview control interaction", "document.getElementById('control-light').getAttribute('aria-pressed') === 'true'");
            js("document.getElementById('exit-preview').click()");
            check("Exit preview restores unknown data", "document.getElementById('battery-value').textContent === '—' && document.getElementById('control-lock').disabled");
            js("document.getElementById('nav-settings').click(); document.getElementById('about-setting').click()");
            check("Android dialog and back flow", "S9App.back() && document.getElementById('modal').hidden && S9App.back()");
            js("S9Native.saveUnit('mi')"); waitForIdleSync();
            // Sync with the main thread after the bridge has saved preferences.
            SystemClock.sleep(250);
            runOnMainSync(() -> activity.finish()); waitForIdleSync();
            launch();
            check("Activity restart and native preference persistence", "document.getElementById('unit-value').textContent === '英里'");
            js("S9Native.saveUnit('km')");
            runOnMainSync(() -> activity.finish());
            result.putString("stream", "\nPASS " + passed + " Android runtime checks\n"); result.putInt("passed", passed);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", "\nFAILED: " + trace.toString()); result.putInt("passed",passed); finish(Activity.RESULT_CANCELED,result);
        }
    }
}
