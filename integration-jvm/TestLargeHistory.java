import org.vault.exporter.*;
import android.content.Context;
import java.io.*;
import java.nio.file.*;

/** 100k synthetic IDs; actual ExportEngine, encryption and verifier; NO network. */
public final class TestLargeHistory {
  public static void main(String[] args)throws Exception {
    final int count=100000; final long chat=4321;
    long[] ids=new long[count];
    for(int i=0;i<count;i++)ids[i]=20000000L-i*31L;
    File root=Files.createTempDirectory("vault-100k-").toFile();
    char[] password="synthetic-stress-password".toCharArray();
    TdGateway first=new TdGateway(ids,root);first.setPageSize(100);first.failOnHistoryCall(200);
    boolean dropped=false;
    try {new ExportEngine(new Context(root),first,x->{}).export(chat,new ByteArrayOutputStream(),password);}
    catch(IOException error){dropped=error.getMessage().contains("SIMULATED_NETWORK_DROP");}
    if(!dropped)throw new AssertionError("Expected synthetic history outage");
    TdGateway resumed=new TdGateway(ids,root);resumed.setPageSize(100);
    File result=new File(root,"result.tva");
    ExportEngine.Summary summary;
    try(OutputStream output=new BufferedOutputStream(new FileOutputStream(result))){
      summary=new ExportEngine(new Context(root),resumed,x->{}).export(chat,output,password);
    }
    if(summary.messages!=count || summary.media!=8 || !summary.historyFinished)
      throw new AssertionError("Unexpected export counts: "+summary.messages+"/"+summary.media);
    try(InputStream input=new BufferedInputStream(new FileInputStream(result))){
      if(ArchiveVerifier.verify(input,password)<2)throw new AssertionError("Invalid archive");
    }
    System.out.println("PASS: 100000 synthetic messages, outage/resume, authenticated archive and strict ID order/count; no Telegram connection");
  }
}
