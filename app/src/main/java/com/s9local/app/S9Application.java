package com.s9local.app;

import android.app.Application;
import android.content.Intent;
import org.json.JSONObject;
import java.util.ArrayList;

/** Process-owned BLE session: an Activity disappearing must not close the GATT. */
public final class S9Application extends Application {
    BleController ble;
    boolean visible, serviceRequested;
    String serviceError="";
    private final ArrayList<BleController.Listener> listeners=new ArrayList<>();
    @Override public void onCreate(){super.onCreate();ble=new BleController(this,this::changed);}
    static boolean busy(JSONObject state){String p=state.optString("phase");return state.optBoolean("scanning")||p.equals("connecting")||p.equals("discovering")||p.equals("subscribing")||p.equals("authenticating")||p.equals("ready");}
    void add(BleController.Listener listener){if(!listeners.contains(listener))listeners.add(listener);}
    void remove(BleController.Listener listener){listeners.remove(listener);}
    private void changed(JSONObject state){
        if(busy(state)&&visible&&!serviceRequested&&ble.hasPermissions()){
            serviceRequested=true;
            try{startForegroundService(new Intent(this,VehicleService.class));serviceError="";}
            catch(RuntimeException e){serviceRequested=false;serviceError=e.getClass().getSimpleName();}
        }
        for(BleController.Listener listener:new ArrayList<>(listeners))listener.changed(state);
    }
    void enter(){visible=true;ble.foreground();changed(ble.snapshot());}
    void leave(){visible=false;ble.background();}
}
