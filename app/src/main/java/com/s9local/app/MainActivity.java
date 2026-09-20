package com.s9local.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class MainActivity extends Activity {
    private WebView web;
    private BleController ble;
    private S9Application app;
    private final BleController.Listener bleListener=state->send("ble",state);
    private boolean pageReady, destroyed;
    private String exportPending;
    private static final int REQUEST_PERMISSIONS = 10, REQUEST_BLUETOOTH = 11, REQUEST_EXPORT = 12, REQUEST_IMPORT = 13, REQUEST_NOTIFICATION=14;
    private static final String ORIGIN = "https://app.s9.local/";
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) ble.bluetoothOff();
            if (state == BluetoothAdapter.STATE_ON) ble.autoConnect();
        }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.rgb(245,245,239));
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(245,245,239));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        web = new WebView(this); web.setBackgroundColor(Color.rgb(245,245,239));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        web.getSettings().setDomStorageEnabled(false); web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                if ("https".equals(uri.getScheme()) && "app.s9.local".equals(uri.getHost()) && ("/index.html".equals(path) || "/app.css".equals(path) || "/app.js".equals(path))) {
                    try {
                        String type = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
                        return new WebResourceResponse(type, "UTF-8", getAssets().open(path.substring(1)));
                    } catch (IOException ignored) { }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (!(ORIGIN + "index.html").equals(url)) return;
                pageReady = true;
                JSONObject init = new JSONObject();
                BleController.put(init, "native", true);
                BleController.put(init, "unit", getPreferences(MODE_PRIVATE).getString("unit", "km"));
                send("init", init); send("ble", ble.snapshot());
                requestNotifications();
            }
        });
        app=(S9Application)getApplication();ble=app.ble;app.add(bleListener);
        ble.preview(false);
        web.addJavascriptInterface(new Bridge(), "S9Native");
        frame.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1)); setContentView(frame);
        // PhoneWindow's decor is not created before setContentView. Query the attached
        // view instead of dereferencing Window.getInsetsController during early onCreate.
        frame.post(() -> {
            if (destroyed) return;
            WindowInsetsController controller = frame.getWindowInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
        });
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothEvents, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothEvents, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        web.loadUrl(ORIGIN + "index.html");
    }
    private void handleBack() {
        web.evaluateJavascript("window.S9App ? window.S9App.back() : false", value -> { if (!"true".equals(value)) finish(); });
    }
    @Override public void onBackPressed() { handleBack(); }
    private void send(String event, JSONObject payload) {
        if (!pageReady || destroyed || web == null) return;
        web.evaluateJavascript("window.S9App&&window.S9App.receive(" + JSONObject.quote(event) + "," + payload.toString() + ")", null);
    }
    private void notice(String text) { JSONObject msg = new JSONObject(); BleController.put(msg, "message", text); send("notice", msg); }
    private void requestNotifications(){
        if(Build.VERSION.SDK_INT>=33&&ble.hasPermissions()&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATION);
        }
    }
    private void requestScan() {
        if (!ble.supported()) { notice("这台设备不支持低功耗蓝牙"); return; }
        if (!ble.hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS); return;
        }
        if (!ble.enabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BLUETOOTH); }
            catch (RuntimeException e) { notice("请在系统设置中打开蓝牙"); }
            return;
        }
        ble.scan();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (ble.hasPermissions()) {requestScan();requestNotifications();}
            else notice("未获得附近设备权限。可在系统设置 → 应用 → S9 本地 → 权限中开启，然后重试。");
        }
    }
    private void exportReport() {
        JSONObject report = ble.report();
        BleController.put(report,"backgroundServiceRequested",app.serviceRequested);BleController.put(report,"backgroundServiceError",app.serviceError);
        BleController.put(report,"notificationsEnabled",getSystemService(NotificationManager.class).areNotificationsEnabled());
        BleController.put(report,"phoneModel",Build.MODEL);BleController.put(report,"androidApi",Build.VERSION.SDK_INT);
        if (report.optString("address").isEmpty()) { notice("请先扫描并连接你选择的设备"); return; }
        new AlertDialog.Builder(this).setTitle("保存设备诊断")
            .setMessage("文件包含所选设备的名称、蓝牙地址、广播和服务列表。仅保存到你选择的位置，不会自动上传。")
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                try { exportPending = report.toString(2); } catch (JSONException e) { exportPending = report.toString(); }
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "s9-device-info.json");
                try { startActivityForResult(intent, REQUEST_EXPORT); } catch (RuntimeException e) { exportPending = null; notice("未找到文件保存程序"); }
            }).show();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==REQUEST_IMPORT && resultCode==RESULT_OK && data!=null && data.getData()!=null){
            try(InputStream in=getContentResolver().openInputStream(data.getData())){
                if(in==null)throw new IOException();
                VehicleConfig.save(this,new String(VehicleConfig.read(in,8192),StandardCharsets.UTF_8));ble.configImported();notice("连接配置已导入，日常使用无需登录或联网");
            }catch(Exception e){notice("连接配置导入失败，请选择提供的 s9-connection.json");}
        }
        if (requestCode == REQUEST_BLUETOOTH) {
            if (resultCode == RESULT_OK) requestScan(); else notice("蓝牙未开启，可以稍后重试");
        }
        if (requestCode == REQUEST_EXPORT) {
            String captured = exportPending; exportPending = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || captured == null) return;
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                if (stream == null) throw new IOException("No stream");
                stream.write(captured.getBytes(StandardCharsets.UTF_8)); notice("设备诊断已保存");
            } catch (IOException e) { notice("保存失败，请换一个位置重试"); }
        }
    }
    @Override protected void onStart() {super.onStart();if(app!=null)app.enter();}
    @Override protected void onStop() { super.onStop(); if (app != null) app.leave(); }
    @Override protected void onDestroy() {
        destroyed = true; pageReady = false;
        unregisterReceiver(bluetoothEvents); app.remove(bleListener);
        web.removeJavascriptInterface("S9Native"); web.destroy(); super.onDestroy();
    }
    public final class Bridge {
        @JavascriptInterface public void scan() { runOnUiThread(() -> requestScan()); }
        @JavascriptInterface public void stopScan() { runOnUiThread(() -> ble.stopScan(true)); }
        @JavascriptInterface public void connect(String address) { runOnUiThread(() -> ble.connect(address)); }
        @JavascriptInterface public void disconnect() { runOnUiThread(() -> ble.disconnect(true)); }
        @JavascriptInterface public void preview(boolean enabled) {runOnUiThread(()->ble.preview(enabled));}
        @JavascriptInterface public void notificationSettings(){runOnUiThread(()->{
            try{startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));}
            catch(RuntimeException e){notice("请到系统设置中开启 S9 本地的通知权限");}
        });}
        @JavascriptInterface public void exportReport() { runOnUiThread(() -> MainActivity.this.exportReport()); }
        @JavascriptInterface public void importConfig(){runOnUiThread(()->{
            ble.disconnect(true);
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try{startActivityForResult(intent,REQUEST_IMPORT);}catch(RuntimeException e){notice("无法打开文件选择器");}
        });}
        @JavascriptInterface public void control(int id,int value){runOnUiThread(()->{String error=ble.control(id,value);notice(error!=null?error:"指令已发送，等待车辆确认");});}
        @JavascriptInterface public void saveUnit(String unit) {
            if ("km".equals(unit) || "mi".equals(unit)) getPreferences(MODE_PRIVATE).edit().putString("unit", unit).apply();
        }
    }
}
