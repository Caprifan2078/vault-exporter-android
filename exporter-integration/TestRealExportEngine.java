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
    ok(summary.media==8,"Media missing "+summary.media);
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
    ok(mediaItems==8,"Wrong media entries "+mediaItems);
    ok(index.contains("sha256"),"No media hash index");
    String[] mediaRows=index.split("\\n");
    int checked=0;
    for(String row:mediaRows){
      if(row.trim().isEmpty())continue;
      JSONObject ref=new JSONObject(row);
      String path=ref.getString("path");
      byte[] bytes=unpacked.get(path);
      ok(bytes!=null,"Indexed media missing: "+path);
      ok(ref.getLong("bytes")==bytes.length,"Wrong indexed media length");
      java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
      byte[] sha=digest.digest(bytes);
      StringBuilder hex=new StringBuilder();
      for(byte b:sha)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
      ok(hex.toString().equals(ref.getString("sha256")),"Media hash mismatch");
      ok(Arrays.equals(bytes,TdGateway.contentFor(ref.getInt("file_id"))),
        "Wrong media bytes for file "+ref.getInt("file_id"));
      checked++;
    }
    ok(checked==8,"Unexpected indexed media count");
    boolean denied=false;
    try{ArchiveVerifier.verify(new ByteArrayInputStream(output.toByteArray()),
       "definitely-wrong-password".toCharArray());}
    catch(Exception expected){denied=true;}
    ok(denied,"Encrypted export readable with incorrect password");
    ok(entries==unpacked.size(),"Unexpected ZIP entries");
    JSONObject manifest=new JSONObject(new String(unpacked.get("manifest.json"),StandardCharsets.UTF_8));
    ok(manifest.getInt("messages")==250 && manifest.getInt("media_downloaded")==8,"Manifest count mismatch");
    System.out.println("PASS: real ExportEngine with fake TDLib: 250 sparse messages, 8 media formats, interrupted/resumed history, encrypted TVA, exact media bytes/sha256 and manifest; pages="+pages);
  }
}
