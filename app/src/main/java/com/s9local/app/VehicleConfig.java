package com.s9local.app;

import android.content.Context;
import android.security.keystore.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Device credentials stay in private app storage, encrypted with Android Keystore. */
final class VehicleConfig {
    final String uuid, deviceId, localKey, address;
    private static final String FILE="vehicle.bin", ALIAS="s9-vehicle-config";
    VehicleConfig(JSONObject o) throws JSONException {
        uuid=o.getString("uuid");deviceId=o.getString("device_id");localKey=o.getString("local_key");address=o.getString("address").toUpperCase(Locale.US);
        if(!uuid.matches("[a-zA-Z0-9]{16}")||!deviceId.matches("[a-zA-Z0-9]{1,22}")||localKey.length()<6||localKey.length()>64||!address.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"))throw new JSONException("Invalid device configuration");
        if(!"p0emi0c7".equals(o.optString("product_id")))throw new JSONException("Wrong S9 product");
    }
    static VehicleConfig load(Context c) {
        try(InputStream in=c.openFileInput(FILE)) {
            byte[] bytes=read(in,8192);if(bytes.length<29)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            return new VehicleConfig(new JSONObject(new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8)));
        }catch(Exception e){return null;}
    }
    static VehicleConfig save(Context c,String text)throws Exception {
        JSONObject o=new JSONObject(text);VehicleConfig value=new VehicleConfig(o);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
        android.util.AtomicFile target=new android.util.AtomicFile(new File(c.getFilesDir(),FILE));FileOutputStream out=null;
        try{out=target.startWrite();out.write(cipher.getIV());out.write(encrypted);target.finishWrite(out);}catch(Exception e){if(out!=null)target.failWrite(out);throw e;}return value;
    }
    private static javax.crypto.SecretKey key()throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
    }
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(b.size()+n>limit)throw new IOException("File too large");b.write(buffer,0,n);}return b.toByteArray();}
}
