import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real ExportEngine, ResumeStore, ArchiveWriter, and ChunkedVault with only TDLib/Android replaced. */
public final class TestRealExportEngine {
  static void ok(boolean value,String message){if(!value)throw new AssertionError(message);}
  static final char[] PASS="offline-integration-test-password".toCharArray();
  static Map<String,byte[]> unpack(byte[] tva)throws Exception{
    Map<String,byte[]> files=new HashMap<>();
    ChunkedVault.Decrypt dec=new ChunkedVault.Decrypt(new ByteArrayInputStream(tva),PASS);
    try(ZipInputStream zip=new ZipInputStream(dec)){
      ZipEntry e;
      while((e=zip.getNextEntry())!=null){
        ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
        while((n=zip.read(buf))!=-1)data.write(buf,0,n);
        if(files.put(e.getName(),data.toByteArray())!=null)throw new IOException("Duplicate entry");
        zip.closeEntry();
      }
      while(dec.read()!=-1){}
    }
    return files;
  }
  public static void main(String[] args)throws Exception{
    File root=Files.createTempDirectory("real-exporter-test-").toFile();
    long chatId=4321;
    long[] ids=new long[250];
    for(int i=0;i<ids.length;i++)ids[i]=10_000-i*17L;
    Context ctx=new Context(root);
    TdGateway broken=new TdGateway(ids,root);
    broken.failOnHistoryCall(9);
    boolean interrupted=false;
    try{
      new ExportEngine(ctx,broken,x->{}).export(chatId,new ByteArrayOutputStream(),PASS);
    }catch(IOException x){
      interrupted=x.getMessage().contains("SIMULATED_NETWORK_DROP");
    }
    ok(interrupted,"Fake transport failure did not interrupt exporter");
    TdGateway resumed=new TdGateway(ids,root);
    ByteArrayOutputStream output=new ByteArrayOutputStream();
    ExportEngine.Summary summary=new ExportEngine(ctx,resumed,x->{}).export(chatId,output,PASS);
    ok(summary.historyFinished,"History was not marked complete");
    ok(summary.messages==250,"Incomplete history count "+summary.messages);
    ok(summary.media==1,"Media missing "+summary.media);
    int entries=ArchiveVerifier.verify(new ByteArrayInputStream(output.toByteArray()),PASS);
    Map<String,byte[]> unpacked=unpack(output.toByteArray());
    Set<Long> actual=new HashSet<>();int pages=0,mediaItems=0;
    String index="";
    for(Map.Entry<String,byte[]> item:unpacked.entrySet()){
      if(item.getKey().startsWith("messages/")){
        pages++;
        for(String line:new String(item.getValue(),StandardCharsets.UTF_8).split("\n")){
          if(line.isEmpty())continue;
          long id=new JSONObject(line).getLong("id");
          ok(actual.add(id),"Duplicate exported message "+id);
        }
      }
      if(item.getKey().startsWith("media/"))mediaItems++;
      if(item.getKey().startsWith("media-index/"))index+=new String(item.getValue(),StandardCharsets.UTF_8);
    }
    ok(actual.size()==ids.length,"Missing messages "+(ids.length-actual.size()));
    for(long id:ids)ok(actual.contains(id),"Missing "+id);
    ok(mediaItems==1,"Wrong media entries "+mediaItems);
    ok(index.contains("sha256"),"No media hash index");
    ok(entries==unpacked.size(),"Unexpected ZIP entries");
    JSONObject manifest=new JSONObject(new String(unpacked.get("manifest.json"),StandardCharsets.UTF_8));
    ok(manifest.getInt("messages")==250 && manifest.getInt("media_downloaded")==1,"Manifest count mismatch");
    System.out.println("PASS: actual ExportEngine with fake TDLib: 250 sparse messages, staged media, crash/restart, encrypted TVA, authenticated ZIP, exact IDs and manifest; pages="+pages);
  }
}
