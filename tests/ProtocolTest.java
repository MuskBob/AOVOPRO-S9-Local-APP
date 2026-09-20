package com.s9local.app;
import java.util.*;
import java.nio.charset.StandardCharsets;
public final class ProtocolTest {
    static int checks;
    interface Task{void run()throws Exception;}
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void reject(Task t,String name)throws Exception{boolean rejected=false;try{t.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,name);}
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static TuyaBleProtocol.Frame receive(TuyaBleProtocol p,List<byte[]> packets)throws Exception{TuyaBleProtocol.Frame f=null;for(byte[] b:packets){TuyaBleProtocol.Frame got=p.receive(b);if(got!=null)f=got;}return f;}
    public static void main(String[] args)throws Exception{
        TuyaBleProtocol p=new TuyaBleProtocol("abcdef0123456789");
        check(TuyaBleProtocol.crc16("123456789".getBytes(StandardCharsets.US_ASCII),9)==0x4b37,"standard Modbus CRC vector");
        byte[] golden=hex("0400000000000000000000000000000000dbf89cd4defcdb706e09a3b977803910");
        List<byte[]> expected=TuyaBleProtocol.fragment(golden,4),actual=p.encodeWithIv(1,0,0,new byte[]{0,20},new byte[16]);
        check(actual.size()==2&&Arrays.equals(actual.get(0),hex("0021400400000000000000000000000000000000"))&&Arrays.equals(actual.get(1),hex("01dbf89cd4defcdb706e09a3b977803910")),"V4 handshake matches independent Node AES golden bytes");
        TuyaBleProtocol.Frame f=receive(p,expected);check(f.code==0&&f.sequence==1&&Arrays.equals(f.data,new byte[]{0,20}),"decode golden frame across BLE fragments");
        // Independently encrypted Node/OpenSSL fixtures with the same CRC-valid
        // header/payload and different bytes after CRC; these are synthetic keys.
        String prefix="0400000000000000000000000000000000995718e7aed7522f01ecc93c75a182b6";
        for(String suffix:new String[]{"87767d5c0c0e7515e9c15e44d48d241e","43e219431b6fb80779eda5bed40d3bdd","291b141ca04975a1ba0c1551f2f258d8"}){
            f=receive(p,TuyaBleProtocol.fragment(hex(prefix+suffix),4));
            check(f.sequence==7&&f.responseTo==1&&f.code==0&&Arrays.equals(f.data,new byte[]{1,2,3,4}),"CRC-delimited payload independent of AES tail: "+suffix.substring(0,8));
        }
        reject(()->receive(p,TuyaBleProtocol.fragment(hex(prefix+"c206ef0b25426280e3188bc5aaf1c81d"),4)),"nonzero AES tail still requires valid CRC");
        reject(()->receive(p,TuyaBleProtocol.fragment(hex(prefix+"291b141ca04975a1ba0c1551f2f258d88cc4733e45f1b92654aaa2f49e41c82e"),4)),"extra ciphertext block rejected despite valid CRC");
        reject(()->p.receive(new byte[]{1,2,3}),"orphan fragment rejected");
        p.receive(expected.get(0));reject(()->p.receive(new byte[]{2,0}),"missing fragment rejected");
        List<byte[]> corrupt=new ArrayList<>();for(byte[] b:expected)corrupt.add(b.clone());corrupt.get(1)[5]^=1;
        reject(()->receive(p,corrupt),"corrupted encrypted frame rejected");
        f=receive(p,expected);check(f!=null,"parser recovers after malformed input");
        List<byte[]> filled=new ArrayList<>();for(byte[] b:expected)filled.add(b.clone());
        byte[] tail=filled.get(filled.size()-1);byte[] fixedTail=Arrays.copyOf(tail,20);Arrays.fill(fixedTail,tail.length,20,(byte)0xA5);filled.set(filled.size()-1,fixedTail);
        f=receive(p,filled);check(f.code==0&&Arrays.equals(f.data,new byte[]{0,20}),"fixed ATT final packet fill ignored outside declared encrypted envelope");
        List<byte[]> badFill=new ArrayList<>(filled);byte[] badFixed=fixedTail.clone();badFixed[5]^=1;badFill.set(badFill.size()-1,badFixed);
        reject(()->receive(p,badFill),"transport fill compatibility does not bypass encrypted frame validation");
        List<byte[]> oversized=new ArrayList<>(expected);oversized.set(1,Arrays.copyOf(tail,21));
        reject(()->receive(p,oversized),"overflow outside negotiated fixed ATT size rejected");
        p.receive(expected.get(0));check(p.receive(expected.get(0))==null,"exact repeated first fragment ignored while incomplete");
        f=p.receive(expected.get(1));check(f!=null,"repeated fragment does not duplicate encrypted data");
        try{p.receive(new byte[]{1,2,3});throw new AssertionError("expected structured error");}
        catch(TuyaBleProtocol.ProtocolException e){check(e.getMessage().equals("Out of order BLE fragment")&&p.receiveDiagnostic().contains("packet=1"),"diagnostic identifies parser boundary without payload");}
        reject(()->p.receive(new byte[]{0,(byte)0xff,(byte)0xff,0x7f,0x40}),"oversized frame rejected before allocation");
        byte[] info=new byte[96];info[2]=4;info[5]=1;System.arraycopy("123456".getBytes(StandardCharsets.US_ASCII),0,info,6,6);String id="0123456789abcdefghij12";System.arraycopy(id.getBytes(StandardCharsets.US_ASCII),0,info,55,22);
        byte[] unbound=info.clone();unbound[5]=0;reject(()->p.acceptDeviceInfo(unbound,id),"unbound device never paired");
        reject(()->p.acceptDeviceInfo(info,"wrong"),"different vehicle identity rejected");
        byte[] advanced=info.clone();advanced[4]=10;reject(()->p.acceptDeviceInfo(advanced,id),"unsupported certificate authentication rejected");
        p.acceptDeviceInfo(info,id);byte[] pair=p.pairingData("0123456789abcdef",id);
        check(pair.length==44&&new String(pair,16,6,StandardCharsets.US_ASCII).equals("abcdef"),"pair payload uses 16-byte UUID and 6-byte local key");
        f=receive(p,p.encode(2,0,1,pair));check(f.security==5&&Arrays.equals(pair,f.data),"session key packet encryption and reassembly");
        byte[] bool=TuyaBleProtocol.writeDp(4,7,8,1,1);
        check(Arrays.equals(bool,hex("00000000070801000101")),"V4 boolean command wire format");
        byte[] number=TuyaBleProtocol.writeDp(4,8,101,2,12);
        check(Arrays.equals(number,hex("0000000008650200040000000c")),"V4 speed command uses 4-byte integer");
        List<TuyaBleProtocol.Dp> d=TuyaBleProtocol.parseDps(hex("0302000400000052080100010110040001011402000400000faa"),0,4);
        check(d.size()==4&&d.get(0).value.equals(82)&&d.get(1).value.equals(true)&&d.get(2).value.equals(1)&&d.get(3).value.equals(4010),"mixed V4 battery/light/start/voltage notification");
        check(TuyaBleProtocol.parseDps(hex("080101"+"01"),0,3).get(0).value.equals(true),"legacy DP parser uses one-byte lengths");
        reject(()->TuyaBleProtocol.parseDps(hex("0801000102"),0,4),"invalid boolean does not change state");
        reject(()->TuyaBleProtocol.parseDps(hex("140200040001"),0,4),"truncated telemetry rejected");
        reject(()->TuyaBleProtocol.parseDps(hex("100400020001"),0,4),"invalid enum width rejected");
        byte[] large=new byte[2500];new Random(12).nextBytes(large);f=receive(p,p.encode(10,0,0x8006,large));check(Arrays.equals(f.data,large),"multi-byte fragment index and length across large notification");
        p.clear();System.out.println("Protocol checks: "+checks);
    }
}
