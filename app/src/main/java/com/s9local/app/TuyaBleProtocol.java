package com.s9local.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Tuya BLE frame codec. See docs/ble-protocol.md for wire-format sources. */
public final class TuyaBleProtocol {
    private final byte[] local, login;
    private byte[] session;
    private int version = 4, sequence = 1, expectedPacket, expectedLength;
    private ByteArrayOutputStream input;
    private byte[] lastFragment;
    private String receiveDiagnostic = "no notifications";
    public static final class ProtocolException extends IllegalArgumentException {
        ProtocolException(String message) { super(message); }
    }
    public String receiveDiagnostic() { return receiveDiagnostic; }
    private final SecureRandom random = new SecureRandom();
    public static final class Frame {
        public final int sequence, responseTo, code, security;
        public final byte[] data;
        Frame(int sequence, int responseTo, int code, int security, byte[] data) {
            this.sequence=sequence; this.responseTo=responseTo; this.code=code; this.security=security; this.data=data;
        }
    }
    public static final class Dp {
        public final int id, type;
        public final Object value;
        Dp(int id, int type, Object value) { this.id=id; this.type=type; this.value=value; }
    }
    public TuyaBleProtocol(String localKey) throws GeneralSecurityException {
        if (localKey == null || localKey.length()<6) throw new ProtocolException("Missing local key");
        local=localKey.substring(0,6).getBytes(StandardCharsets.UTF_8);
        if(local.length!=6) throw new ProtocolException("Invalid key encoding");
        login=md5(local);
    }
    public int nextSequence() { if(sequence==Integer.MAX_VALUE) throw new IllegalStateException("Reconnect required"); return sequence++; }
    public int version() { return version; }
    public void acceptDeviceInfo(byte[] data, String deviceId) throws GeneralSecurityException {
        if(data.length<46) throw new ProtocolException("Device info too short");
        int v=data[2]&255;
        if(v!=3 && v!=4) throw new ProtocolException("Unsupported BLE protocol "+v);
        if(data[5]!=1) throw new ProtocolException("Device is not bound; stopped without pairing");
        if((data[4]&0x0A)==0x0A) throw new ProtocolException("Device requires advanced certificate authentication");
        if(v==4) {
            if(data.length<77) throw new ProtocolException("V4 device info too short");
            String returned=new String(Arrays.copyOfRange(data,55,77),StandardCharsets.US_ASCII).replace("\u0000", "");
            if(!deviceId.equals(returned)) throw new ProtocolException("Device identity mismatch");
        }
        version=v;
        byte[] seed=Arrays.copyOf(local,12);
        System.arraycopy(data,6,seed,6,6); session=md5(seed); Arrays.fill(seed,(byte)0);
    }
    public byte[] pairingData(String uuid, String deviceId) {
        byte[] u=uuid.getBytes(StandardCharsets.US_ASCII), id=deviceId.getBytes(StandardCharsets.US_ASCII);
        if(u.length!=16 || id.length>22 || session==null) throw new ProtocolException("Invalid pairing identity");
        byte[] p=new byte[44];System.arraycopy(u,0,p,0,16);System.arraycopy(local,0,p,16,6);System.arraycopy(id,0,p,22,id.length);return p;
    }
    public List<byte[]> encode(int seq,int responseTo,int code,byte[] data) throws GeneralSecurityException {
        byte[] iv=new byte[16];random.nextBytes(iv);return encodeWithIv(seq,responseTo,code,data,iv);
    }
    List<byte[]> encodeWithIv(int seq,int responseTo,int code,byte[] data,byte[] iv) throws GeneralSecurityException {
        if(data.length>4096) throw new ProtocolException("Payload too long");
        int security=code==0?4:5; byte[] key=security==4?login:session;
        if(key==null) throw new IllegalStateException("No session key");
        byte[] raw=new byte[((14+data.length+15)/16)*16];
        ByteBuffer b=ByteBuffer.wrap(raw);b.putInt(seq).putInt(responseTo).putShort((short)code).putShort((short)data.length).put(data);
        b.putShort((short)crc16(raw,12+data.length));
        byte[] crypt=crypt(Cipher.ENCRYPT_MODE,key,iv,raw), envelope=new byte[17+crypt.length];
        envelope[0]=(byte)security;System.arraycopy(iv,0,envelope,1,16);System.arraycopy(crypt,0,envelope,17,crypt.length);
        return fragment(envelope,version);
    }
    static List<byte[]> fragment(byte[] envelope,int version) {
        List<byte[]> result=new ArrayList<>();int pos=0,packet=0;
        while(pos<envelope.length) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();varint(out,packet);
            if(packet==0) {varint(out,envelope.length);out.write(version<<4);}
            int n=Math.min(20-out.size(),envelope.length-pos);out.write(envelope,pos,n);pos+=n;packet++;result.add(out.toByteArray());
        } return result;
    }
    public Frame receive(byte[] fragment) throws GeneralSecurityException {
        try {
            receiveDiagnostic="stage=fragment bytes="+fragment.length;
            int[] cursor={0};int packet=readVarint(fragment,cursor);
            receiveDiagnostic+=" packet="+packet;
            // Some transports repeat a notification. Only ignore an exact repeat
            // of the preceding fragment while that same frame is incomplete.
            if(input!=null && packet==expectedPacket-1 && Arrays.equals(fragment,lastFragment)) {
                receiveDiagnostic+=" duplicate=true";return null;
            }
            if(packet==0) {
                expectedLength=readVarint(fragment,cursor);
                if(expectedLength<33 || expectedLength>8192 || cursor[0]>=fragment.length) throw new ProtocolException("Invalid frame length");
                int peer= (fragment[cursor[0]++]&255)>>4;
                if(peer!=3 && peer!=4) throw new ProtocolException("Unsupported fragment protocol");
                input=new ByteArrayOutputStream();expectedPacket=0;
            }
            receiveDiagnostic+=" expectedPacket="+expectedPacket+" total="+expectedLength+" assembled="+(input==null?0:input.size());
            if(input==null || packet!=expectedPacket) throw new ProtocolException("Out of order BLE fragment");
            int available=fragment.length-cursor[0], remaining=expectedLength-input.size();
            // SDK trsmitr_recv_pkg_decode uses the declared total length. Accept
            // a fixed 20-byte final ATT packet, but never append its trailing fill.
            if(available>remaining && fragment.length!=20)throw new ProtocolException("Frame overflow");
            int count=Math.min(available,remaining);
            input.write(fragment,cursor[0],count);expectedPacket++;lastFragment=fragment.clone();
            receiveDiagnostic+=" received="+input.size()+" transportFill="+(available-count);
            if(input.size()<expectedLength)return null;
            byte[] envelope=input.toByteArray();input=null;expectedPacket=0;lastFragment=null;
            int security=envelope[0]&255;byte[] key=security==4?login:security==5?session:null;
            receiveDiagnostic+=" stage=envelope security="+security;
            if(key==null || (envelope.length-17)%16!=0)throw new ProtocolException("Invalid encryption envelope");
            byte[] raw=crypt(Cipher.DECRYPT_MODE,key,Arrays.copyOfRange(envelope,1,17),Arrays.copyOfRange(envelope,17,envelope.length));
            receiveDiagnostic+=" stage=decrypted-length";
            ByteBuffer b=ByteBuffer.wrap(raw);int seq=b.getInt(),resp=b.getInt(),code=b.getShort()&65535,len=b.getShort()&65535;
            if(len+14>raw.length || raw.length-(len+14)>=16)throw new ProtocolException("Invalid decrypted length");
            receiveDiagnostic+=" stage=crc";
            if(crc16(raw,len+12)!=(ByteBuffer.wrap(raw,len+12,2).getShort()&65535))throw new ProtocolException("CRC mismatch");
            // The declared payload length and following CRC delimit the message.
            // Device firmware can leave nonzero bytes in the final AES block.
            // Match SDK ble_cmd_data_crc_check: do not interpret those bytes as
            // payload or require zero fill. The bounds above still limit the
            // trailing area to 0..15 bytes (no extra ciphertext blocks).
            receiveDiagnostic+=" cipherTailBytes="+(raw.length-len-14);
            receiveDiagnostic+=" stage=validated";
            return new Frame(seq,resp,code,security,Arrays.copyOfRange(raw,12,12+len));
        } catch(GeneralSecurityException|RuntimeException e){input=null;expectedPacket=0;lastFragment=null;throw e;}
    }
    public static byte[] writeDp(int version,int sn,int id,int type,int value) {
        if(id<1||id>255||!(type==1||type==2||type==4))throw new ProtocolException("Unsupported DP");
        int n=type==2?4:1;ByteBuffer b=ByteBuffer.allocate((version==4?9:3)+n);
        if(version==4)b.put((byte)0).putInt(sn);
        b.put((byte)id).put((byte)type);if(version==4)b.putShort((short)n);else b.put((byte)n);
        if(n==4)b.putInt(value);else b.put((byte)value);return b.array();
    }
    public static List<Dp> parseDps(byte[] data,int offset,int version) {
        List<Dp> result=new ArrayList<>();ByteBuffer b=ByteBuffer.wrap(data);b.position(offset);
        while(b.hasRemaining()) {
            if(b.remaining()<(version==4?4:3))throw new ProtocolException("Truncated DP header");
            int id=b.get()&255,type=b.get()&255,n=version==4?b.getShort()&65535:b.get()&255;
            if(n>b.remaining()||type>5)throw new ProtocolException("Invalid DP length/type");
            byte[] raw=new byte[n];b.get(raw);Object value;
            if(type==1) {if(n!=1 || (raw[0]!=0 && raw[0]!=1))throw new ProtocolException("Invalid boolean");value=raw[0]!=0;}
            else if(type==2){if(n!=4)throw new ProtocolException("Invalid integer");value=ByteBuffer.wrap(raw).getInt();}
            else if(type==4){if(n!=1)throw new ProtocolException("Invalid enum");value=raw[0]&255;}
            else if(type==3)value=new String(raw,StandardCharsets.UTF_8);
            else value=raw;
            result.add(new Dp(id,type,value));
        }return result;
    }
    public void clear(){Arrays.fill(local,(byte)0);Arrays.fill(login,(byte)0);if(session!=null)Arrays.fill(session,(byte)0);session=null;input=null;}
    static int crc16(byte[] data,int len){int c=65535;for(int i=0;i<len;i++){c^=data[i]&255;for(int j=0;j<8;j++)c=(c>>>1)^((c&1)!=0?0xA001:0);}return c;}
    private static byte[] md5(byte[] b)throws GeneralSecurityException{return MessageDigest.getInstance("MD5").digest(b);}
    private static byte[] crypt(int mode,byte[] key,byte[] iv,byte[] data)throws GeneralSecurityException{Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(data);}
    private static void varint(ByteArrayOutputStream b,int n){do{int v=n&127;n>>>=7;b.write(v|(n==0?0:128));}while(n!=0);}
    private static int readVarint(byte[] b,int[] p){int v=0;for(int i=0;i<4;i++){if(p[0]>=b.length)throw new ProtocolException("Truncated varint");int x=b[p[0]++]&255;v|=(x&127)<<(7*i);if((x&128)==0)return v;}throw new ProtocolException("Oversized varint");}
}

