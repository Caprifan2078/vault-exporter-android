import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline contract tests for actual encrypted synthetic export, no Telegram connection. */
public final class RunDemoArchiveAuditTests {
  private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
  private static String hash(byte[] x)throws Exception{
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(x);
    StringBuilder text=new StringBuilder();
    for(byte b:digest)text.append(String.format(Locale.ROOT,"%02x",b&255));
    return text.toString();
  }
  private static byte[] png(){return new byte[]{(byte)137,80,78,71,13,10,26,10,2,4,6,8};}
  private static byte[] wav(){return new byte[]{82,73,70,70,4,0,0,0,87,65,86,69,0,1,2,3};}
  private static byte[] mp4(){return new byte[]{0,0,0,16,102,116,121,112,105,115,111,109,0,0,0,1};}
  static byte[] archive(int n,boolean skipPhoto,boolean badHash,boolean badId,boolean duplicateId)throws Exception{
    char[] password="offline-audit-test-password".toCharArray();
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(ArchiveWriter writer=new ArchiveWriter(out,password)){
      StringBuilder messages=new StringBuilder();
      for(int i=1;i<=n;i++)messages.append("{\"id\":").append(i).append(",\"text\":\"test\"}\n");
      if(duplicateId)messages.append("{\"id\":1,\"text\":\"duplicate\"}\n");
      writer.writeBytes("messages/page-000001.jsonl",messages.toString().getBytes(StandardCharsets.UTF_8));
      byte[] photo=png(),voice=wav(),video=mp4();
      if(!skipPhoto)writer.writeBytes("media/3/photo.png",photo);
      writer.writeBytes("media/5/voice.wav",voice);
      writer.writeBytes("media/7/round.mp4",video);
      String index="{\"message_id\":"+(badId?999:3)+",\"path\":\"media/3/photo.png\",\"sha256\":\""+(badHash?hash(video):hash(photo))+"\"}\n"+
        "{\"message_id\":5,\"path\":\"media/5/voice.wav\",\"sha256\":\""+hash(voice)+"\"}\n"+
        "{\"message_id\":7,\"path\":\"media/7/round.mp4\",\"sha256\":\""+hash(video)+"\"}\n";
      writer.writeBytes("media-index/page-000001.jsonl",index.getBytes(StandardCharsets.UTF_8));
      writer.writeBytes("manifest.json",("{\"schema\":\"tg-vault-v1\",\"synthetic\":true,\"messages\":"+n+",\"media\":3}").getBytes(StandardCharsets.UTF_8));
    }finally{Arrays.fill(password,'\0');}
    return out.toByteArray();
  }
  static void verify(byte[] archive)throws Exception{
    char[] password="offline-audit-test-password".toCharArray();
    try{
      DemoArchiveAudit.verify(new ByteArrayInputStream(archive),password);
    }finally{Arrays.fill(password,'\0');}
  }
  private static void rejects(String reason,byte[] x)throws Exception{
    try{verify(x);throw new AssertionError("Accepted bad archive: "+reason);}
    catch(IOException expected){ /* invalid archive correctly rejected */ }
  }
  public static void main(String[] args)throws Exception{
    byte[] good=archive(120,false,false,false,false);
    verify(good);
    verify(archive(10_000,false,false,false,false));
    rejects("missing media",archive(120,true,false,false,false));
    rejects("wrong sha",archive(120,false,true,false,false));
    rejects("orphan media link",archive(120,false,false,true,false));
    rejects("duplicate message id",archive(120,false,false,false,true));
    // Wrong password must fail closed and reveal no preview.
    char[] wrong="this-is-the-wrong-password".toCharArray();
    boolean denied=false;
    try { DemoArchiveAudit.inspect(new ByteArrayInputStream(good),wrong); }
    catch(Exception expected){denied=true;}
    finally { Arrays.fill(wrong,'\0'); }
    check(denied,"Wrong password unexpectedly accepted");
    byte[] corrupt=good.clone();corrupt[corrupt.length/2]^=0x55;
    rejects("encrypted tampering",corrupt);
    rejects("encrypted truncation",Arrays.copyOf(good,good.length-6));
    System.out.println("PASS demo audit: 120 and 10k messages; 3 media types; rejects missing, wrong hash, orphan, duplicate, tampering, truncation");
  }
}