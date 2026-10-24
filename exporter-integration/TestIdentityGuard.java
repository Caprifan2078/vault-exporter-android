import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class TestIdentityGuard {
 static final long CHAT=4321L;
 static final char[] PASS="offline-integration-test-password".toCharArray();
 static void ok(boolean v,String m){if(!v)throw new AssertionError(m);}
 static void resumeAndCheck(File root,long[] ids)throws Exception{
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   ExportEngine.Summary result=new ExportEngine(new Context(root),new TdGateway(ids,root),s->{}).export(CHAT,out,PASS);
   ok(result.historyFinished && result.messages==ids.length,"Recovery failed");
   Set<Long> actual=new HashSet<>();
   try(ZipInputStream zip=new ZipInputStream(new ChunkedVault.Decrypt(new ByteArrayInputStream(out.toByteArray()),PASS))){
     ZipEntry e;byte[] buf=new byte[8192];
     while((e=zip.getNextEntry())!=null) {
       ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;
       while((n=zip.read(buf))!=-1)bytes.write(buf,0,n);
       if(e.getName().startsWith("messages/")){
         for(String line:new String(bytes.toByteArray(),StandardCharsets.UTF_8).split("\\n")){
           if(line.isEmpty())continue;
           ok(actual.add(new JSONObject(line).getLong("id")),"Duplicate message");
         }
       }
     }
   }
   ok(actual.size()==ids.length,"Missing messages");
   for(long id:ids)ok(actual.contains(id),"Missing ID "+id);
   ArchiveVerifier.verify(new ByteArrayInputStream(out.toByteArray()),PASS);
 }
 interface Configure { void set(TdGateway fake); }
 static void rejectedAndResumed(String name,Configure setup,long[] ids)throws Exception{
   File root=Files.createTempDirectory("identity-"+name).toFile();
   TdGateway fake=new TdGateway(ids,root);setup.set(fake);
   boolean rejected=false;
   try {new ExportEngine(new Context(root),fake,s->{}).export(CHAT,new ByteArrayOutputStream(),PASS);}
   catch(IOException e){rejected=e.getMessage().contains("другого чата") || e.getMessage().contains("другой чат");}
   ok(rejected,name+" accepted cross-chat response");
   resumeAndCheck(root,ids);
   System.out.println("PASS "+name+": rejected cross-chat response and safely resumed");
 }
 public static void main(String[] args)throws Exception{
   long[] ids=new long[250];for(int i=0;i<ids.length;i++)ids[i]=10000-i*17L;
   rejectedAndResumed("metadata",TdGateway::rejectMetadataFixture,ids);
   rejectedAndResumed("history",TdGateway::rejectHistoryFixture,ids);
   rejectedAndResumed("stalled-probe",TdGateway::rejectProbeFixture,ids);
   System.out.println("PASS 3 real ExportEngine chat identity fail-closed integration scenarios");
 }
}
