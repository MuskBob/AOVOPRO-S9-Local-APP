package com.s9local.app;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.*;

/** Process-owned local Tuya BLE session, restricted to the imported vehicle. */
public final class BleController {
    public interface Listener { void changed(JSONObject state); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> found = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt active;
    private boolean scanning, foreground, preview, autoSelecting;
    private String phase = "idle", message = "点击连接，查找附近的滑板车", selected = "", name = "";
    private Integer battery;
    private JSONArray services = new JSONArray();
    private JSONObject advertisement = new JSONObject();
    private Runnable timeout;
    private final Runnable endScan = () -> stopScan(true);
    private boolean updatePending;
    private VehicleConfig config;
    private TuyaBleProtocol protocol;
    private BluetoothGattCharacteristic writer;
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private boolean writing, authenticated;
    private int infoSequence, pairSequence, pendingSequence, pendingId, pendingValue, notifications, sentFragments;
    private long speedAt;
    private int controlResultId;
    private String controlResult="";
    private JSONObject datapoints = new JSONObject();
    private JSONObject lastDatapoints = new JSONObject();
    private JSONObject lastKnown = new JSONObject();
    private final Map<Integer,JSONObject> definitions = new HashMap<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final ArrayDeque<String> controlEvents = new ArrayDeque<>();
    private Runnable writeTimeout, commandTimeout, pollTask;
    private static final UUID SERVICE=UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb"), WRITE=UUID.fromString("00000001-0000-1001-8001-00805f9b07d0"), NOTIFY=UUID.fromString("00000002-0000-1001-8001-00805f9b07d0"), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public BleController(Context context, Listener listener) {
        this.context=context;this.listener=listener;config=VehicleConfig.load(context);loadLastKnown();
        try(java.io.InputStream in=context.getAssets().open("vehicle-model.json")) {
            JSONArray list=new JSONObject(new String(VehicleConfig.read(in,65536),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("services").getJSONObject(0).getJSONArray("properties");
            for(int i=0;i<list.length();i++){JSONObject d=list.getJSONObject(i);definitions.put(d.getInt("abilityId"),d);}
        }catch(Exception e){message="车辆功能配置读取失败";}
    }
    public void configImported() {disconnect(false);config=VehicleConfig.load(context);loadLastKnown();message=config==null?"连接配置读取失败":"连接配置已导入，可以连接车辆";emit();autoConnect();}
    private void loadLastKnown(){
        lastKnown=new JSONObject();if(config==null)return;
        try{lastKnown=new JSONObject(context.getSharedPreferences("vehicle_last_values",Context.MODE_PRIVATE).getString(config.deviceId,"{}"));}catch(JSONException ignored){}
    }
    private void saveLastKnown(int id,Object value){
        if(config==null||(id!=3&&id!=12))return;
        String key=Integer.toString(id);if(value.equals(lastKnown.opt(key)))return;
        put(lastKnown,key,value);
        context.getSharedPreferences("vehicle_last_values",Context.MODE_PRIVATE).edit().putString(config.deviceId,lastKnown.toString()).apply();
    }
    private int lastLightCommand(){
        return config==null?-1:context.getSharedPreferences("vehicle_light_commands",Context.MODE_PRIVATE).getInt(config.deviceId,-1);
    }
    public void foreground() {foreground=true;autoConnect();}
    public void preview(boolean enabled) {preview=enabled;if(enabled)disconnect(true);else autoConnect();}
    public void autoConnect() {
        if(!foreground||preview||config==null||active!=null||scanning||!hasPermissions()||!enabled())return;
        scan(true);
    }
    private void controlLog(String event){if(controlEvents.size()>=40)controlEvents.removeFirst();controlEvents.addLast(android.os.SystemClock.elapsedRealtime()+" "+event);log(event);}
    private void log(String event){if(events.size()>=80)events.removeFirst();events.addLast(android.os.SystemClock.elapsedRealtime()+" "+event);}
    private void abort(String text){log(text);disconnect(false);fail(text);}
    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }
    public boolean hasPermissions() {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    public boolean supported() { return adapter() != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE); }
    public boolean enabled() { return hasPermissions() && adapter() != null && adapter().isEnabled(); }
    private void emit() { listener.changed(snapshot()); }
    private void fail(String text) { phase = "error"; message = text; emit(); }
    private void clearTimeout() { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    private void later(Runnable action, long delay) { clearTimeout(); timeout = action; main.postDelayed(action, delay); }

    public void scan() {scan(false);}
    private void scan(boolean automatic) {
        if (!hasPermissions()) { fail("需要附近设备权限才能扫描"); return; }
        if (!supported()) { fail("这台设备不支持低功耗蓝牙"); return; }
        if (!enabled()) { fail("请先打开手机蓝牙"); return; }
        disconnect(false);
        stopScan(false);
        autoSelecting=automatic;
        found.clear();
        services = new JSONArray(); battery = null; selected = ""; name = ""; advertisement = new JSONObject();
        scanner = adapter().getBluetoothLeScanner();
        if (scanner == null) { fail("蓝牙尚未准备好，请稍后重试"); return; }
        scanning = true; phase = "scanning"; message = automatic?"正在自动查找你的车辆…":"正在查找附近设备…";
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                final ScanCallback thisScan = this;
                main.post(() -> {
                    if (!scanning || scanCallback != thisScan || !hasPermissions()) return;
                    String address = result.getDevice().getAddress();
                    if (found.size() < 64 || found.containsKey(address)) found.put(address, result);
                    if(autoSelecting&&config!=null&&config.address.equalsIgnoreCase(address)&&result.isConnectable()) {connect(address);return;}
                    if (!updatePending) {
                        updatePending = true;
                        main.postDelayed(() -> { updatePending = false; emit(); }, 350);
                    }
                });
            }
            @Override public void onScanFailed(int errorCode) {
                final ScanCallback thisScan = this;
                main.post(() -> {
                    if (scanCallback != thisScan) return;
                    stopScan(false);
                    fail(errorCode == 6 ? "扫描过于频繁，请稍等半分钟再试" : "扫描失败（" + errorCode + "），请重新打开蓝牙后重试");
                });
            }
        };
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.postDelayed(endScan, 12000); emit();
        } catch (RuntimeException e) { stopScan(false); fail("无法开始扫描，请检查蓝牙及附近设备权限"); }
    }

    public void stopScan(boolean notify) {
        main.removeCallbacks(endScan);
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
        }
        boolean wasAuto=autoSelecting;
        scanner = null; scanCallback = null; scanning = false;autoSelecting=false;
        if (phase.equals("scanning")) { phase = "idle"; message = found.isEmpty() ? "没有找到设备，请确认车辆已开机，并断开原 App 的连接" : "请选择你的滑板车"; }
        if(wasAuto&&notify)message="未找到你的车辆，请开机后点击连接重试";
        if (notify) emit();
    }

    private String deviceName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String value = record == null ? null : record.getDeviceName();
        if (value == null && hasPermissions()) value = result.getDevice().getName();
        return value == null || value.trim().isEmpty() ? "未命名蓝牙设备" : value;
    }
    public void connect(String address) {
        if(config==null){fail("请先在连接页面导入车辆连接配置");return;}
        if(!config.address.equalsIgnoreCase(address)){fail("这不是连接配置对应的车辆，请选择你的 S9");return;}
        if (!hasPermissions() || !enabled()) { fail("请打开蓝牙并允许附近设备权限"); return; }
        ScanResult result = found.get(address);
        if (result == null) { fail("请重新扫描后选择设备"); return; }
        if (!result.isConnectable()) { fail("该设备的广播不支持连接"); return; }
        stopScan(false); disconnect(false);
        selected = address; name = deviceName(result); battery = null; services = new JSONArray();
        events.clear();controlEvents.clear();notifications=0;sentFragments=0;lastDatapoints=new JSONObject();log("begin connection");
        advertisement = describeAdvertisement(result);
        phase = "connecting"; message = "正在连接 " + name; emit();
        try {
            active = result.getDevice().connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (active == null) { fail("无法创建蓝牙连接"); return; }
            later(() -> { disconnect(false); fail("连接超时，请先断开涂鸦 App，再靠近车辆重试"); }, 15000);
        } catch (RuntimeException e) { disconnect(false); fail("无法连接，请检查权限并重新扫描"); }
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            main.post(() -> {
                if (gatt != active) { gatt.close(); return; }
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect(false);
                    phase = status == BluetoothGatt.GATT_SUCCESS ? "idle" : "error";
                    message = status == BluetoothGatt.GATT_SUCCESS ? "蓝牙已断开" : "连接已中断（" + status + "），请断开原 App 后重试";
                    emit(); return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase = "discovering"; message = "底层链路已建立，正在识别服务；车辆尚未认证"; emit();
                    try {
                        if (!gatt.discoverServices()) { disconnect(false); fail("无法开始服务识别，请重试"); return; }
                        later(() -> { disconnect(false); fail("服务识别超时，请重试"); }, 10000);
                    } catch (RuntimeException e) { disconnect(false); fail("无法读取服务，请检查蓝牙权限"); }
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            main.post(() -> {
                if (gatt != active) return;
                clearTimeout();
                if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(false); fail("服务识别失败（" + status + "）"); return; }
                services = describeServices(gatt);
                beginAuthentication(gatt);
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (android.os.Build.VERSION.SDK_INT < 33) acceptBattery(gatt, characteristic, characteristic.getValue(), status);
        }
        @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            acceptBattery(gatt, characteristic, value, status);
        }
        @Override public void onDescriptorWrite(BluetoothGatt gatt,BluetoothGattDescriptor descriptor,int status){main.post(()->{
            if(gatt!=active||!phase.equals("subscribing"))return;
            if(status!=BluetoothGatt.GATT_SUCCESS){abort("开启车辆通知失败（"+status+"）");return;}
            clearTimeout();phase="authenticating";message="正在进行车辆加密认证";log("notify enabled; request V4 device info");emit();
            infoSequence=sendFrame(0,new byte[]{0,20},0);
            if(active!=null)later(()->abort("车辆认证无响应：请保存设备诊断，检查协议握手"),12000);
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic,int status){main.post(()->{
            if(gatt!=active||!writing||!WRITE.equals(characteristic.getUuid()))return;
            if(writeTimeout!=null)main.removeCallbacks(writeTimeout);writeTimeout=null;
            writing=false;if(status!=BluetoothGatt.GATT_SUCCESS){abort("蓝牙写入失败（"+status+"）");return;}
            writes.pollFirst();sentFragments++;main.postDelayed(()->{if(gatt==active)drainWrites();},20);
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic c){if(android.os.Build.VERSION.SDK_INT<33)acceptNotification(gatt,c,c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic c,byte[] value){acceptNotification(gatt,c,value);}
    };
    private void beginAuthentication(BluetoothGatt gatt){
        try{
            BluetoothGattService service=gatt.getService(SERVICE);writer=service==null?null:service.getCharacteristic(WRITE);
            BluetoothGattCharacteristic notify=service==null?null:service.getCharacteristic(NOTIFY);
            BluetoothGattDescriptor cccd=notify==null?null:notify.getDescriptor(CCCD);
            if(writer==null||cccd==null||config==null){abort("缺少涂鸦蓝牙服务或车辆连接配置");return;}
            protocol=new TuyaBleProtocol(config.localKey);phase="subscribing";message="正在开启车辆状态通知";log("FD50 service found");emit();
            if(!gatt.setCharacteristicNotification(notify,true)){abort("无法开启车辆通知");return;}
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if(!gatt.writeDescriptor(cccd)){abort("无法订阅车辆状态");return;}
            later(()->abort("订阅车辆状态超时"),8000);
        }catch(Exception e){abort("车辆认证初始化失败");}
    }
    private int sendFrame(int code,byte[] data,int responseTo){
        if(active==null||protocol==null)return 0;
        try{int seq=protocol.nextSequence();List<byte[]> packets=protocol.encode(seq,responseTo,code,data);if(writes.size()+packets.size()>128)throw new IllegalStateException();writes.addAll(packets);log(String.format(Locale.US,"tx code=%04x seq=%d bytes=%d",code,seq,data.length));drainWrites();return seq;}catch(Exception e){abort("蓝牙数据编码失败");return 0;}
    }
    private void drainWrites(){
        if(active==null||writer==null||writing||writes.isEmpty())return;
        try{writing=true;writer.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);writer.setValue(writes.peekFirst());
            if(!active.writeCharacteristic(writer)){writing=false;abort("蓝牙发送队列失败");return;}
            BluetoothGatt current=active;writeTimeout=()->{if(active==current&&writing)abort("蓝牙发送超时");};main.postDelayed(writeTimeout,5000);
        }catch(RuntimeException e){abort("无法向车辆发送蓝牙数据");}
    }
    private void acceptNotification(BluetoothGatt gatt,BluetoothGattCharacteristic c,byte[] value){
        if(value==null||!NOTIFY.equals(c.getUuid()))return;byte[] copy=value.clone();main.post(()->{
            if(gatt!=active||protocol==null)return;notifications++;
            String stage="decode";
            try{TuyaBleProtocol.Frame frame=protocol.receive(copy);if(frame!=null){stage="handle";handleFrame(frame);}}
            catch(Exception e){
                // Only our fixed protocol messages are safe to export. Never log
                // arbitrary exception messages, plaintext, IVs or authentication data.
                String reason=e instanceof TuyaBleProtocol.ProtocolException?e.getMessage():e.getClass().getSimpleName();
                log("receive failure stage="+stage+" reason="+reason);
                if(protocol!=null)log(protocol.receiveDiagnostic());
                abort("车辆认证或数据解析失败（"+reason+"），请保存设备诊断");
            }
        });
    }
    private void handleFrame(TuyaBleProtocol.Frame f)throws Exception {
        if(f.code!=0x8006&&f.code!=0x8007)log(String.format(Locale.US,"rx code=%04x seq=%d response=%d bytes=%d",f.code,f.sequence,f.responseTo,f.data.length));
        if(f.code==0 && f.responseTo==infoSequence && phase.equals("authenticating")){
            if(f.security!=4)throw new IllegalArgumentException();
            protocol.acceptDeviceInfo(f.data,config.deviceId);log("protocol="+protocol.version()+" bound=true");
            clearTimeout();pairSequence=sendFrame(1,protocol.pairingData(config.uuid,config.deviceId),0);
            if(active!=null)later(()->abort("车辆未确认认证，请保存设备诊断"),12000);return;
        }
        if(f.code==1 && f.responseTo==pairSequence && phase.equals("authenticating")){
            if(f.security!=5 || f.data.length!=1 || (f.data[0]!=0&&f.data[0]!=2)){abort("车辆拒绝认证，可能需要更新连接配置");return;}
            clearTimeout();authenticated=true;phase="ready";message="车辆已认证，正在读取状态";log("authenticated");emit();sendFrame(3,new byte[0],0);schedulePoll();return;
        }
        if(!authenticated||f.security!=5)return;
        if(f.code==0x8011 && f.data.length==0){
            byte[] time=Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);java.nio.ByteBuffer out=java.nio.ByteBuffer.allocate(time.length+2);out.put(time).putShort((short)(TimeZone.getDefault().getOffset(System.currentTimeMillis())/36000));sendFrame(f.code,out.array(),f.sequence);return;
        }
        if(f.code==0x8012 && f.data.length==0){Calendar c=Calendar.getInstance();java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(9);b.put((byte)(c.get(Calendar.YEAR)%100)).put((byte)(c.get(Calendar.MONTH)+1)).put((byte)c.get(Calendar.DAY_OF_MONTH)).put((byte)c.get(Calendar.HOUR_OF_DAY)).put((byte)c.get(Calendar.MINUTE)).put((byte)c.get(Calendar.SECOND)).put((byte)((c.get(Calendar.DAY_OF_WEEK)+5)%7)).putShort((short)(c.getTimeZone().getOffset(c.getTimeInMillis())/36000));sendFrame(f.code,b.array(),f.sequence);return;}
        if((f.code==0x27||f.code==2) && f.responseTo==pendingSequence && pendingSequence!=0){
            int status=f.code==0x27?(f.data.length==6?f.data[5]&255:-1):(f.data.length==1?f.data[0]&255:-1);
            controlLog("command ack dp="+pendingId+" status="+status);
            if(pendingId==8&&status==0){
                // Remember only accepted commands, never infer a physical lamp state.
                if(config!=null)context.getSharedPreferences("vehicle_light_commands",Context.MODE_PRIVATE).edit().putInt(config.deviceId,pendingValue).apply();
                finishCommand("车辆已接收"+(pendingValue==1?"开灯":"关灯")+"指令，请查看实际灯光");message=controlResult;emit();return;
            }
            if(status!=0){finishCommand("车辆未接受设置（"+status+"）");message=controlResult;emit();}else{message="指令已接收，等待车辆回报确认";sendFrame(3,new byte[0],0);emit();}return;
        }
        int offset=0,v=3;byte[] ack=new byte[0];boolean acknowledge=true;
        if(f.code==0x8006||f.code==0x8007){
            if(f.data.length<7||f.data[0]!=0)throw new IllegalArgumentException();v=4;offset=7;ack=Arrays.copyOf(f.data,8);ack[7]=0;acknowledge=(f.data[5]&128)==0;
            // Panel destinations are mode 0 (cloud + panel) and 2 (panel only).
            int mode=f.data[6]&255;
            boolean fresh=true;
            if(f.code==0x8007){
                if(f.data.length<8)throw new IllegalArgumentException();int t=f.data[7]&255;offset=t==0?21:t==1?12:-1;
                if(offset<0||offset>f.data.length)throw new IllegalArgumentException();
                long at=t==0?Long.parseLong(new String(f.data,8,13,java.nio.charset.StandardCharsets.US_ASCII)):(java.nio.ByteBuffer.wrap(f.data,8,4).getInt()&0xffffffffL)*1000;
                fresh=Math.abs(System.currentTimeMillis()-at)<=12000;
            }
            if((mode!=0&&mode!=2)||!fresh){if(acknowledge)sendFrame(f.code,ack,f.sequence);return;}
        }else if(f.code==0x8001){offset=0;}
        else if(f.code==0x8004){if(f.data.length<3)throw new IllegalArgumentException();offset=3;ack=new byte[]{f.data[0],f.data[1],f.data[2],0};}
        else if(f.code==0x8003||f.code==0x8005){ // Acknowledge historical reports without treating them as live state.
            ack=f.code==0x8005&&f.data.length>=3?new byte[]{f.data[0],f.data[1],f.data[2],0}:new byte[0];sendFrame(f.code,ack,f.sequence);return;
        }else return;
        List<TuyaBleProtocol.Dp> values=TuyaBleProtocol.parseDps(f.data,offset,v);
        for(TuyaBleProtocol.Dp dp:values)acceptDp(dp);
        if(acknowledge)sendFrame(f.code,ack,f.sequence);
        if(pendingSequence==0)message="车辆已连接，状态来自本地蓝牙";emit();
    }
    private void acceptDp(TuyaBleProtocol.Dp dp)throws JSONException{
        JSONObject definition=definitions.get(dp.id);if(definition==null)return;
        JSONObject spec=definition.getJSONObject("typeSpec");String t=spec.getString("type");
        if((dp.type==1&&!t.equals("bool"))||(dp.type==2&&!t.equals("value"))||(dp.type==4&&!t.equals("enum")))return;
        if(dp.type!=1&&dp.type!=2&&dp.type!=4)return;
        if(dp.type==2){int n=(Integer)dp.value;if(n<spec.optInt("min",Integer.MIN_VALUE)||n>spec.optInt("max",Integer.MAX_VALUE))return;}
        if(dp.type==4 && (Integer)dp.value>=spec.getJSONArray("range").length())return;
        put(datapoints,Integer.toString(dp.id),dp.value);put(lastDatapoints,Integer.toString(dp.id),dp.value);
        saveLastKnown(dp.id,dp.value);
        if(dp.id==3)battery=(Integer)dp.value;if(dp.id==2)speedAt=android.os.SystemClock.elapsedRealtime();
        if(dp.id==pendingId&&pendingId!=8){int value=dp.value instanceof Boolean?((Boolean)dp.value?1:0):(Integer)dp.value;if(value==pendingValue){controlLog("confirmed dp="+pendingId);finishCommand("车辆已回报，设置成功");}}
    }
    private void schedulePoll(){BluetoothGatt current=active;pollTask=()->{if(current!=active||!authenticated)return;sendFrame(3,new byte[0],0);schedulePoll();};main.postDelayed(pollTask,5000);}
    public String control(int id,int value){
        if(!authenticated||active==null)return "车辆尚未认证";
        if(pendingSequence!=0)return "请等待上一条设置确认";
        if(!Arrays.asList(1,8,13,15,16,101,102,103,104).contains(id))return "不支持此设置";
        // The owner confirmed explicit true/false commands work in v0.2.3.
        // DP 8 is not reported, so accept either bool without requiring its state.
        JSONObject def=definitions.get(id);if(def==null||!"rw".equals(def.optString("accessMode")))return "设置不可写";
        if(id!=8&&!datapoints.has(Integer.toString(id)))return "还未读取到这项设置，请稍后重试";
        // Values remain valid for this authenticated connection until replaced.
        // This scooter does not continuously re-send an unchanged zero speed.
        if(id!=8&&!datapoints.has("2")){controlLog("blocked dp="+id+" speed=unknown");return "尚未收到本次连接的车速，请稍候";}
        if(id!=8&&datapoints.optInt("2",-1)!=0){controlLog("blocked dp="+id+" speed="+datapoints.optInt("2",-1));return "车辆上报速度不为零，请停车后设置";}
        try{JSONObject spec=def.getJSONObject("typeSpec");String type=spec.getString("type");int wire;
            if(type.equals("bool")){wire=1;if(value<0||value>1)return "无效开关值";}
            else if(type.equals("enum")){wire=4;if(value<0||value>=spec.getJSONArray("range").length())return "无效选项";}
            else{wire=2;int min=spec.getInt("min"),max=spec.getInt("max"),step=spec.optInt("step",1);if(value<min||value>max||(value-min)%step!=0)return "设置超出车辆支持范围";}
            pendingId=id;pendingValue=value;int sn=protocol.nextSequence();pendingSequence=sn;
            List<byte[]> encoded=protocol.encode(sn,0,protocol.version()==4?0x27:2,TuyaBleProtocol.writeDp(protocol.version(),sn,id,wire,value));
            writes.addAll(encoded);controlLog("user command dp="+id+" value="+value+" seq="+sn);message="正在设置，等待车辆确认";drainWrites();
            if(active==null)return "蓝牙发送失败";
            commandTimeout=()->{controlLog("command report timeout dp="+pendingId);finishCommand("未收到设置回报，请确认车辆实际状态后重试");message=controlResult;emit();};main.postDelayed(commandTimeout,8000);emit();return null;
        }catch(Exception e){clearCommand();return "设置编码失败";}
    }
    private void clearCommand(){if(commandTimeout!=null)main.removeCallbacks(commandTimeout);commandTimeout=null;pendingSequence=0;pendingId=0;}
    private void finishCommand(String result){clearCommand();controlResult=result;controlResultId++;}
    private void acceptBattery(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        final byte[] copy = value == null ? null : value.clone();
        main.post(() -> {
            if (gatt != active || !phase.equals("authentication_unavailable") || status != BluetoothGatt.GATT_SUCCESS || copy == null || copy.length != 1) return;
            if (!characteristic.getUuid().toString().equals("00002a19-0000-1000-8000-00805f9b34fb")) return;
            int percent = copy[0] & 255;
            if (percent <= 100) { battery = percent; emit(); }
        });
    }

    public void disconnect(boolean notify) {
        stopScan(false); clearTimeout();
        authenticated=false;clearCommand();if(pollTask!=null)main.removeCallbacks(pollTask);pollTask=null;
        if(writeTimeout!=null)main.removeCallbacks(writeTimeout);writeTimeout=null;writing=false;writes.clear();writer=null;
        if(protocol!=null)protocol.clear();protocol=null;datapoints=new JSONObject();speedAt=0;
        BluetoothGatt previous = active; active = null; battery = null;
        if (previous != null) {
            try { previous.disconnect(); } catch (RuntimeException ignored) { }
            previous.close();
        }
        phase = "idle"; message = "蓝牙未连接";
        if (notify) emit();
    }
    public void background() {
        foreground=false;
        // The connectedDevice service owns the live GATT and notification.
        // Stop discovery if the UI leaves, but keep an existing connection.
        if(scanning)stopScan(false);
        emit();
    }
    public void bluetoothOff() { disconnect(false); message = "手机蓝牙已关闭"; emit(); }
    public void close() { disconnect(false); main.removeCallbacksAndMessages(null); }

    public JSONObject snapshot() {
        JSONObject result = new JSONObject();
        put(result, "phase", phase); put(result, "message", message); put(result, "scanning", scanning);
        put(result, "name", name); put(result, "address", selected); put(result, "battery", battery == null ? JSONObject.NULL : battery);
        put(result, "transportConnected", transportConnected());
        put(result, "authenticationState", authenticated?"authenticated":phase.equals("authenticating")?"in_progress":"not_authenticated");
        put(result, "controlReady", authenticated); put(result, "services", services);
        put(result, "configured",config!=null);put(result,"datapoints",datapoints);put(result,"commandPending",pendingSequence!=0);
        put(result,"controlResultId",controlResultId);put(result,"controlResult",controlResult);
        put(result,"lastKnown",lastKnown);
        put(result,"lightCommandValue",lastLightCommand());
        JSONArray devices = new JSONArray();
        if (hasPermissions()) {
            ArrayList<ScanResult> sorted = new ArrayList<>(found.values());
            sorted.sort((a,b) -> Integer.compare(b.getRssi(), a.getRssi()));
            for (ScanResult scan : sorted) {
                JSONObject device = new JSONObject();
                put(device, "name", deviceName(scan)); put(device, "address", scan.getDevice().getAddress());
                put(device, "rssi", scan.getRssi()); put(device, "connectable", scan.isConnectable()); devices.put(device);
            }
        }
        put(result, "devices", devices); return result;
    }
    public JSONObject report() {
        JSONObject report = new JSONObject();
        put(report, "format", "s9-local-inspection-v2"); put(report, "appVersion", "0.2.10");
        put(report, "capturedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        put(report, "deviceName", name); put(report, "address", selected); put(report, "advertisement", advertisement);
        put(report, "services", services); put(report, "battery", battery == null ? JSONObject.NULL : battery);
        put(report, "connectionState", phase); put(report, "controlReady", authenticated);
        put(report, "transportConnected", transportConnected());
        put(report, "authenticationState", authenticated?"authenticated":"not_authenticated");
        put(report,"events",new JSONArray(events));put(report,"notifications",notifications);put(report,"sentFragments",sentFragments);put(report,"lastDatapoints",lastDatapoints);
        put(report,"controlEvents",new JSONArray(controlEvents));put(report,"message",message);put(report,"commandPending",pendingSequence!=0);
        put(report,"speedAgeMs",speedAt==0?JSONObject.NULL:android.os.SystemClock.elapsedRealtime()-speedAt);
        put(report, "note", "包含蓝牙握手阶段、功能值及错误记录；不包含 local_key、会话密钥或认证数据包。");
        return report;
    }
    private boolean transportConnected() {
        return active != null && (phase.equals("discovering") || phase.equals("subscribing") || phase.equals("authenticating") || phase.equals("ready"));
    }
    private JSONObject describeAdvertisement(ScanResult scan) {
        JSONObject ad = new JSONObject(); put(ad, "rssi", scan.getRssi());
        ScanRecord record = scan.getScanRecord();
        if (record != null) {
            JSONArray uuids = new JSONArray();
            if (record.getServiceUuids() != null) for (android.os.ParcelUuid uuid : record.getServiceUuids()) uuids.put(uuid.toString());
            put(ad, "serviceUuids", uuids);
            // Only the selected device's advertisement, never the whole nearby-device list.
            put(ad, "rawHex", hex(record.getBytes()));
        }
        return ad;
    }
    private JSONArray describeServices(BluetoothGatt gatt) {
        JSONArray list = new JSONArray();
        for (BluetoothGattService service : gatt.getServices()) {
            JSONObject entry = new JSONObject(); put(entry, "uuid", service.getUuid().toString());
            JSONArray chars = new JSONArray();
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                JSONObject c = new JSONObject(); put(c, "uuid", characteristic.getUuid().toString());
                put(c, "properties", characteristic.getProperties());
                JSONArray descriptors = new JSONArray();
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) descriptors.put(descriptor.getUuid().toString());
                put(c, "descriptors", descriptors); chars.put(c);
            }
            put(entry, "characteristics", chars); list.put(entry);
        }
        return list;
    }
    private static String hex(byte[] data) { if (data == null) return ""; StringBuilder s = new StringBuilder(); for (byte b : data) s.append(String.format(Locale.US, "%02x", b & 255)); return s.toString(); }
    static void put(JSONObject o, String key, Object value) { try { o.put(key, value); } catch (JSONException ignored) { } }
}





