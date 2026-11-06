import org.vault.exporter.*;
import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** Many unique synthetic senders; no Telegram or personal data. */
public final class TestManySenders {
  public static void main(String[] args)throws Exception {
    final int count=25000; final long chat=4321;
    long[] ids=new long[count];for(int i=0;i<count;i++)ids[i]=20000000L-i*31L;
    File root=Files.createTempDirectory("vault-many-senders-").toFile();
    File file=new File(root,"archive.tva");
    char[] pass="synthetic-many-senders-password".toCharArray();
    TdGateway fake=new TdGateway(ids,root);fake.setPageSize(100);fake.useDistinctSenders();
    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){
      ExportEngine.Summary summary=new ExportEngine(new Context(root),fake,x->{}).export(chat,out,pass);
      if(summary.messages!=count || summary.media!=8 || !summary.historyFinished)
        throw new AssertionError("Unexpected export counts");
    }
    try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
      ArchiveVerifier.verify(in,pass);
    }
    boolean found=false;
    try(ZipInputStream zip=new ZipInputStream(new ChunkedVault.Decrypt(new BufferedInputStream(new FileInputStream(file)),pass))){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        if(entry.getName().equals("manifest.json")){
          String manifest=new String(zip.readAllBytes(),StandardCharsets.UTF_8);
          org.json.JSONObject json=new org.json.JSONObject(manifest);
          if(json.getInt("participants")!=count)throw new AssertionError("Missing participants");
          found=true;
        }
        zip.closeEntry();
      }
    }
    if(!found)throw new AssertionError("Manifest missing");
    System.out.println("PASS: 25000 synthetic messages with 25000 distinct senders under 128 MiB heap, no network");
  }
}
