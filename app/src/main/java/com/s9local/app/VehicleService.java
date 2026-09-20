package com.s9local.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;
import java.util.Locale;

/** Keeps the BLE session alive and displays the latest reported vehicle readings. */
public final class VehicleService extends Service {
    private static final String CHANNEL="vehicle_connection", STOP="com.s9local.app.STOP_CONNECTION";
    private static final int ID=9;
    private S9Application app;
    private NotificationManager manager;
    private boolean promoted, stopping;
    private String lastNotification="";
    private final BleController.Listener listener=this::update;
    @Override public void onCreate(){
        super.onCreate();app=(S9Application)getApplication();manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"车辆连接与电量",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持车辆蓝牙连接，显示车辆回报的电量、电压与单次里程");channel.setShowBadge(false);manager.createNotificationChannel(channel);
        app.add(listener);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){app.ble.disconnect(true);stopConnectionService();return START_NOT_STICKY;}
        if(stopping){stopping=false;promoted=false;}app.serviceRequested=true;
        JSONObject state=app.ble.snapshot();
        // Fulfil startForegroundService even if the connection ended before the
        // service was scheduled. Immediately remove the notice in that case.
        show(state);if(!S9Application.busy(state))stopConnectionService();
        return START_NOT_STICKY;
    }
    private void update(JSONObject state){if(stopping)return;if(!S9Application.busy(state)){stopConnectionService();return;}show(state);}
    private void show(JSONObject state){
        boolean ready=state.optBoolean("controlReady");int battery=state.optInt("battery",-1);
        String title=ready?"S9 电量 · "+(battery>=0?battery+"%":"待读取"):"S9 · 正在连接";
        JSONObject dp=state.optJSONObject("datapoints");
        // Use only live-session datapoints, never the persisted lastKnown values.
        String voltage=dp!=null&&dp.opt("20") instanceof Number?String.format(Locale.US,"%.2f V",dp.optDouble("20")/100.0):"— V";
        String trip=dp!=null&&dp.opt("5") instanceof Number?String.format(Locale.US,"%.1f km",dp.optDouble("5")/10.0):"— km";
        String detail=ready?voltage+" · "+trip:state.optString("message","正在连接车辆");
        String signature=title+detail;
        if(promoted&&signature.equals(lastNotification))return;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,VehicleService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_scooter)
            .setContentTitle(title).setContentText(detail).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setCategory(Notification.CATEGORY_STATUS).setLocalOnly(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(new Notification.Action.Builder(null,"断开连接",stop).build());
        try{Notification notification=b.build();
            if(!promoted){startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);promoted=true;}
            else manager.notify(ID,notification);
            lastNotification=signature;
        }catch(RuntimeException e){app.serviceError=e.getClass().getSimpleName();stopConnectionService();}
    }
    private void stopConnectionService(){if(stopping)return;stopping=true;app.serviceRequested=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onTaskRemoved(Intent rootIntent){app.ble.disconnect(true);stopConnectionService();}
    @Override public void onDestroy(){app.remove(listener);app.serviceRequested=false;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}

