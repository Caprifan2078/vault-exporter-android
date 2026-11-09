import org.vault.exporter.*;
import org.json.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.*;
/** Real patched ExportEngine+ResumeStore(TVS2)+strict ArchiveVerifier vs fake TDLib. */
public final class TestHardenedExportEngine {
  static final char[] PASS="reproducible-test-passphrase".toCharArray();
  static void yes(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  static void send(File root,TdGateway td,long chat,OutputStream output)throws Exception{
    new ExportEngine(new Context(root),td,message->{}).export(chat,output,PASS);
  }
  static long[] ids(int n) {long[] a=new long[n];for(int i=0;i<n;i++)a[i]=200_000-i*31L;return a;}
  static Map<String,byte[]> unpack(byte[] blob)throws Exception{
    Map<String,byte[]> ret=new LinkedHashMap<>();
    try(ZipInputStream zip=new ZipInputStream(new ChunkedVault.Decrypt(new ByteArrayInputStream(blob),PASS))){
      ZipEntry entry;byte[] buf=new byte[65536];
      while((entry=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int read;
        while((read=zip.read(buf))!=-1)out.write(buf,0,read);
        yes(ret.put(entry.getName(),out.toByteArray())==null,"duplicate archive entry");zip.closeEntry();
      }
    }
    return ret;
  }
  static String hash(byte[] data)throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
  }
  static void checkArchive(byte[] blob,long[] expected,int media)throws Exception{
    int n=ArchiveVerifier.verify(new ByteArrayInputStream(blob),PASS);
    Map<String,byte[]> files=unpack(blob);yes(n==files.size(),"archive entry count");
    HashSet<Long> actual=new HashSet<>();int countMedia=0;
    for(var file:files.entrySet()){
      if(file.getKey().startsWith("messages/")){
        for(String line:new String(file.getValue(),StandardCharsets.UTF_8).split("\\n")){
          if(!line.isEmpty())yes(actual.add(new JSONObject(line).getLong("id")),"duplicate ID");
        }
      }
      if(file.getKey().startsWith("media-index/")){
        for(String line:new String(file.getValue(),StandardCharsets.UTF_8).split("\\n")){
          if(line.isEmpty())continue;JSONObject row=new JSONObject(line);
          byte[] raw=files.get(row.getString("path"));yes(raw!=null,"missing indexed media");
          yes(raw.length==row.getLong("bytes"),"media size mismatch");
          yes(hash(raw).equals(row.getString("sha256")),"media SHA mismatch");
          yes(Arrays.equals(raw,TdGateway.contentFor(row.getInt("file_id"))),"wrong media bytes");
          countMedia++;
        }
      }
    }
    yes(actual.size()==expected.length,"missing messages "+(expected.length-actual.size()));
    for(long id:expected)yes(actual.contains(id),"missing ID "+id);
    yes(countMedia==media,"wrong media count "+countMedia);
    JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));
    yes(manifest.getInt("messages")==expected.length&&manifest.getInt("media_downloaded")==media,"manifest count mismatch");
  }
  public static void main(String[] args)throws Exception{
    long chat=4321;long[] shortIds=ids(250);File root=Files.createTempDirectory("hardened-engine-short-").toFile();
    for(String field:new String[]{"self_destruct_type","self_destruct_in","auto_delete_in"}) {
      File expiryRoot=Files.createTempDirectory("expiring-message-").toFile();
      TdGateway expiry=new TdGateway(shortIds,expiryRoot);
      expiry.setExpiringMessage(field,field.equals("self_destruct_type")
          ?new JSONObject().put("@type","messageSelfDestructTypeImmediately"):0.5);
      ByteArrayOutputStream output=new ByteArrayOutputStream();boolean refused=false;
      try{send(expiryRoot,expiry,chat,output);}catch(SecurityException expected){refused=true;}
      yes(refused && output.size()==0,"Expiring message exported: "+field);
    }
    // Simulate an older app version having checkpointed an expiring message.
    File legacyRoot=Files.createTempDirectory("legacy-expiring-stage-").toFile();
    byte[] legacySecret=new byte[32];Arrays.fill(legacySecret,(byte)0x5c);
    try(ResumeStore legacy=new ResumeStore(new File(legacyRoot,"export-progress"),chat,legacySecret)) {
      JSONObject message=new JSONObject().put("id",123).put("chat_id",chat).put("can_be_saved",true)
        .put("self_destruct_in",0).put("auto_delete_in",30).put("content",new JSONObject().put("@type","messageText"));
      legacy.putPage(1,(message.toString()+"\n").getBytes(StandardCharsets.UTF_8),new byte[0]);
      legacy.checkpoint(new ResumeStore.State(chat,123,1,1,0,true));
    }
    ByteArrayOutputStream legacyOutput=new ByteArrayOutputStream();boolean legacyRefused=false;
    try{send(legacyRoot,new TdGateway(shortIds,legacyRoot),chat,legacyOutput);}
    catch(SecurityException expected){legacyRefused=true;}
    yes(legacyRefused && legacyOutput.size()==0,"Legacy expiring checkpoint was exported");
    for(int mode=0;mode<9;mode++) {
      File restrictionRoot=Files.createTempDirectory("invalid-restriction-").toFile();
      TdGateway gateway=new TdGateway(shortIds,restrictionRoot);
      if(mode==0)gateway.omitMessageCanBeSaved();
      if(mode==1)gateway.setExpiringMessage("can_be_saved",false);
      if(mode==2)gateway.setExpiringMessage("can_be_saved","true");
      if(mode==3)gateway.omitChatProtection();
      if(mode==4)gateway.setExpiringMessage("auto_delete_in","bad");
      if(mode==5)gateway.setExpiringMessage("self_destruct_type","bad");
      if(mode==6)gateway.setExpiringMessage("auto_delete_in",null);
      if(mode==7)gateway.omitMessageTimer("auto_delete_in");
      if(mode==8)gateway.omitMessageTimer("self_destruct_in");
      ByteArrayOutputStream output=new ByteArrayOutputStream();boolean denied=false;
      try{send(restrictionRoot,gateway,chat,output);}catch(SecurityException expected){denied=true;}
      yes(denied && output.size()==0,"Invalid restriction accepted, case "+mode);
    }
    for(String badType:new String[]{"chatTypeSecret","unknownFutureChatType",""}) {
      File badChatRoot=Files.createTempDirectory("unsupported-chat-type-").toFile();
      TdGateway badChat=new TdGateway(shortIds,badChatRoot);badChat.setChatType(badType);
      ByteArrayOutputStream out=new ByteArrayOutputStream();boolean refused=false;
      try{send(badChatRoot,badChat,chat,out);}catch(SecurityException expected){refused=true;}
      yes(refused && out.size()==0,"Unknown or secret chat accepted: "+badType);
    }
    for(int mode:new int[]{1,2,3,5,6}) {
      File limitRoot=Files.createTempDirectory("participant-stop-").toFile();
      TdGateway gateway=new TdGateway(ids(3),limitRoot);gateway.failParticipant(mode);
      boolean stopped=false;
      try{send(limitRoot,gateway,chat,new ByteArrayOutputStream());}
      catch(IOException expected){stopped=(mode==1 && expected.getCause() instanceof TelegramRateLimit.Delay) || mode==5;}
      catch(InterruptedException expected){stopped=mode==2 && Thread.currentThread().isInterrupted();Thread.interrupted();}
      catch(TdGateway.TelegramError expected){stopped=mode==3 && expected.code==429;}
      catch(java.util.concurrent.TimeoutException expected){stopped=mode==6;}
      yes(stopped,"Participant lookup swallowed rate limit or cancellation: "+mode);
    }
    File missingUserRoot=Files.createTempDirectory("missing-user-").toFile();
    TdGateway missingUser=new TdGateway(ids(3),missingUserRoot);missingUser.failParticipant(4);
    ByteArrayOutputStream anonymous=new ByteArrayOutputStream();send(missingUserRoot,missingUser,chat,anonymous);
    checkArchive(anonymous.toByteArray(),ids(3),0);
    // Actual ExportEngine must export multiple paid-media files attached to ONE
    // message and pass its final independent TVA verifier. Previously the
    // verifier rejected the second media index row as a duplicate message ID.
    File paidRoot=Files.createTempDirectory("paid-multi-media-").toFile();
    TdGateway paidTd=new TdGateway(ids(5),paidRoot);
    JSONObject paidPhotoFile=new JSONObject().put("id",502)
      .put("remote",new JSONObject().put("unique_id","fake-502"));
    JSONObject paidVideoFile=new JSONObject().put("id",503)
      .put("remote",new JSONObject().put("unique_id","fake-503"));
    JSONObject paidPhoto=new JSONObject().put("sizes",new JSONArray()
      .put(new JSONObject().put("width",640).put("height",480).put("photo",paidPhotoFile)));
    JSONObject paidContent=new JSONObject().put("@type","messagePaidMedia")
      .put("media",new JSONArray()
        .put(new JSONObject().put("@type","paidMediaPhoto").put("photo",paidPhoto))
        .put(new JSONObject().put("@type","paidMediaVideo")
          .put("video",new JSONObject().put("video",paidVideoFile).put("mime_type","video/mp4"))));
    paidTd.contentOverride(0,paidContent);
    ByteArrayOutputStream paidArchive=new ByteArrayOutputStream();
    send(paidRoot,paidTd,chat,paidArchive);
    checkArchive(paidArchive.toByteArray(),ids(5),3);
    // A chat can become protected while downloading or immediately before packing.
    for(int protectAt:new int[]{2,3}) {
      File protectRoot=Files.createTempDirectory("chat-protection-changed-").toFile();
      TdGateway changing=new TdGateway(ids(3),protectRoot);changing.protectOnChatCall(protectAt);
      ByteArrayOutputStream out=new ByteArrayOutputStream();boolean stopped=false;
      try{send(protectRoot,changing,chat,out);}catch(SecurityException expected){stopped=true;}
      yes(stopped && out.size()==0,"Changed chat protection was ignored at check "+protectAt);
      ByteArrayOutputStream resumed=new ByteArrayOutputStream();
      send(protectRoot,new TdGateway(ids(3),protectRoot),chat,resumed);
      checkArchive(resumed.toByteArray(),ids(3),0);
    }
    for(int mode=1;mode<=11;mode++) {
      File localRoot=Files.createTempDirectory("cached-message-change-").toFile();
      long[] sample=ids(mode==9 || mode==11?4:3);
      TdGateway changed=new TdGateway(sample,localRoot);changed.setLocalFault(mode);
      ByteArrayOutputStream out=new ByteArrayOutputStream();boolean stopped=false;
      try{send(localRoot,changed,chat,out);}
      catch(SecurityException | IOException expected){stopped=true;}
      yes(stopped && out.size()==0,"Unsafe cached-message state accepted: "+mode);
      ByteArrayOutputStream recoveredLocal=new ByteArrayOutputStream();
      send(localRoot,new TdGateway(sample,localRoot),chat,recoveredLocal);
      checkArchive(recoveredLocal.toByteArray(),sample,(mode==9 || mode==11)?1:0);
    }
    File harmlessRoot=Files.createTempDirectory("cache-state-changed-").toFile();
    TdGateway harmless=new TdGateway(ids(4),harmlessRoot);harmless.setLocalFault(12);
    ByteArrayOutputStream harmlessArchive=new ByteArrayOutputStream();
    send(harmlessRoot,harmless,chat,harmlessArchive);
    checkArchive(harmlessArchive.toByteArray(),ids(4),1);
    TdGateway fails=new TdGateway(shortIds,root);fails.failOnHistoryCall(9);boolean failed=false;
    try{send(root,fails,chat,new ByteArrayOutputStream());}
    catch(IOException ex){failed=ex.getMessage().contains("SIMULATED_NETWORK_DROP");}
    yes(failed,"expected history outage");
    ByteArrayOutputStream done=new ByteArrayOutputStream();send(root,new TdGateway(shortIds,root),chat,done);
    checkArchive(done.toByteArray(),shortIds,8);
    boolean wrongPassword=false;try{ArchiveVerifier.verify(new ByteArrayInputStream(done.toByteArray()),"bad password".toCharArray());}catch(Exception expected){wrongPassword=true;}
    yes(wrongPassword,"wrong password allowed");
    // Restart when one full-resolution file is not retrievable. Page may not be committed.
    File mediaRoot=Files.createTempDirectory("hardened-engine-media-").toFile();
    TdGateway broken=new TdGateway(shortIds,mediaRoot);broken.failDownloadFor(503);boolean mediaFail=false;
    try{send(mediaRoot,broken,chat,new ByteArrayOutputStream());}
    catch(IOException ex){mediaFail=ex.getMessage().contains("503");}
    yes(mediaFail,"missing download accepted");
    ByteArrayOutputStream recovered=new ByteArrayOutputStream();send(mediaRoot,new TdGateway(shortIds,mediaRoot),chat,recovered);checkArchive(recovered.toByteArray(),shortIds,8);
    // Same local file id can refer to a different remote file after a restart.
    // Interrupt within one page after voice was staged and before it commits.
    for(int scenario=0;scenario<3;scenario++) {
      long[] sample=ids(20);
      File retryRoot=Files.createTempDirectory("orphan-media-identity-").toFile();
      TdGateway first=new TdGateway(sample,retryRoot);first.setPageSize(100);first.failDownloadFor(503);
      if(scenario==2)first.omitVoiceIdentity();
      boolean interrupted=false;
      try{send(retryRoot,first,chat,new ByteArrayOutputStream());}catch(IOException expected){interrupted=true;}
      yes(interrupted,"synthetic interruption missing");
      TdGateway next=new TdGateway(sample,retryRoot);next.setPageSize(100);
      if(scenario==0)next.changeVoice();
      if(scenario==1)next.failDownloadFor(501); // unchanged identity must reuse cache
      if(scenario==2){next.omitVoiceIdentity();next.changeVoice();}
      ByteArrayOutputStream archive=new ByteArrayOutputStream();send(retryRoot,next,chat,archive);
      ArchiveVerifier.verify(new ByteArrayInputStream(archive.toByteArray()),PASS);
      Map<String,byte[]> entries=unpack(archive.toByteArray());
      byte[] voice=null;
      for(var entry:entries.entrySet())if(entry.getKey().startsWith("media/"+sample[3]+"/501."))voice=entry.getValue();
      byte[] expected=scenario==1?TdGateway.contentFor(501):"OggS changed voice data".getBytes(StandardCharsets.UTF_8);
      yes(Arrays.equals(voice,expected),"orphan media reused without matching persistent identity, scenario="+scenario);
    }
    System.out.println("PASS interrupted media: changed, unchanged and missing persistent identities");
    for(int fault:new int[]{1,2}) {
      File mismatchRoot=Files.createTempDirectory("download-identity-fault-").toFile();
      TdGateway mismatch=new TdGateway(ids(4),mismatchRoot);mismatch.setDownloadIdentityFault(fault);
      ByteArrayOutputStream output=new ByteArrayOutputStream();boolean refused=false;
      try{send(mismatchRoot,mismatch,chat,output);}
      catch(SecurityException expected){refused=true;}
      yes(refused && output.size()==0,"download with changed or missing identity accepted");
      yes(!new File(mismatchRoot,"tdlib/files/file-501.bin").exists(),"failed download left plaintext behind");
      ByteArrayOutputStream resumed=new ByteArrayOutputStream();
      send(mismatchRoot,new TdGateway(ids(4),mismatchRoot),chat,resumed);
      checkArchive(resumed.toByteArray(),ids(4),1);
    }
    System.out.println("PASS changed or missing downloaded media identity rejected, plaintext cleaned, retry succeeds");
    // Crash in final archive output must not invalidate committed encrypted staging.
    File finalRoot=Files.createTempDirectory("hardened-engine-finalize-").toFile();
    final long[] created={0};boolean failedFinalize=false;
    try{send(finalRoot,new TdGateway(shortIds,finalRoot),chat,new OutputStream(){
      @Override public void write(int value)throws IOException {if(++created[0]>1300)throw new IOException("SIMULATED_DISK_FULL");}
      @Override public void write(byte[] b,int off,int len)throws IOException {
        for(int i=0;i<len;i++)write(b[off+i]);
      }
    });}catch(IOException expected){failedFinalize=expected.getMessage().contains("SIMULATED_DISK_FULL");}
    yes(failedFinalize,"finalization interruption not triggered");
    ByteArrayOutputStream rebuilt=new ByteArrayOutputStream();
    TdGateway finalGateway=new TdGateway(shortIds,finalRoot);
    send(finalRoot,finalGateway,chat,rebuilt);
    yes(finalGateway.historyCalls()==0,"finalization retry fetched history again");
    checkArchive(rebuilt.toByteArray(),shortIds,8);
    // Bit flip in authenticated TVS2 staging must never be silently accepted.
    Path staged=Files.walk(new File(root,"export-progress/chat-"+chat+"/media").toPath())
      .filter(Files::isRegularFile).findFirst().orElseThrow();
    byte[] ciphertext=Files.readAllBytes(staged);
    yes(ciphertext.length>40,"short staging cipher fixture");
    ciphertext[35]^=1;
    Files.write(staged,ciphertext);
    boolean tamperFailed=false;
    try{send(root,new TdGateway(shortIds,root),chat,new ByteArrayOutputStream());}
    catch(IOException ex){tamperFailed=true;}
    yes(tamperFailed,"tampered staging passed finalization");
    // Three failures across the SAME resume store; latest exporter must recover exact 2k message archive.
    long[] longIds=ids(2000);File longRoot=Files.createTempDirectory("hardened-engine-many-").toFile();
    for(int hit:new int[]{6,15,31}){
      TdGateway flaky=new TdGateway(longIds,longRoot);flaky.failOnHistoryCall(hit);boolean stopped=false;
      try{send(longRoot,flaky,chat,new ByteArrayOutputStream());}catch(IOException ex){stopped=ex.getMessage().contains("SIMULATED_NETWORK_DROP");}
      yes(stopped,"expected outage at history call "+hit);
    }
    ByteArrayOutputStream large=new ByteArrayOutputStream();send(longRoot,new TdGateway(longIds,longRoot),chat,large);
    checkArchive(large.toByteArray(),longIds,8);
    // An unfamiliar Telegram media-bearing content type cannot silently pass as
    // an ordinary message. A failed page must remain resumable after fixing it.
    for(String unsupported:new String[]{"messagePaidMedia", "messageChatChangePhoto", "messageImaginaryAttachment"}) {
      File typeRoot=Files.createTempDirectory("hardened-unknown-type-").toFile();
      TdGateway unsupportedGateway=new TdGateway(shortIds,typeRoot);
      unsupportedGateway.contentOverride(10,new JSONObject().put("@type",unsupported)
          .put("media",new JSONArray().put(new JSONObject().put("file",new JSONObject().put("id",4242)))));
      boolean stopped=false;
      try{send(typeRoot,unsupportedGateway,chat,new ByteArrayOutputStream());}
      catch(IOException expected){stopped=expected.getMessage().contains(unsupported);}
      yes(stopped,"Unsupported content was silently ignored: "+unsupported);
      // A subsequent run of the same exporter with newly supported content is
      // allowed to resume the already committed pages and finish accurately.
      ByteArrayOutputStream afterUpdate=new ByteArrayOutputStream();
      send(typeRoot,new TdGateway(shortIds,typeRoot),chat,afterUpdate);
      checkArchive(afterUpdate.toByteArray(),shortIds,8);
    }
    File previewRoot=Files.createTempDirectory("hardened-link-preview-").toFile();
    TdGateway preview=new TdGateway(shortIds,previewRoot);
    preview.contentOverride(1,new JSONObject().put("@type","messageText")
        .put("text",new JSONObject().put("text","Example link"))
        .put("link_preview",new JSONObject().put("@type","linkPreviewPhoto")));
    boolean previewRejected=false;
    try{send(previewRoot,preview,chat,new ByteArrayOutputStream());}
    catch(IOException expected){previewRejected=expected.getMessage().contains("messageText");}
    yes(previewRejected,"Rich preview silently skipped");
    // TDLib can encode link-preview variants as a nested type object; missing
    // types must also fail closed instead of silently losing original media.
    for(int mode=0;mode<2;mode++) {
      File pRoot=Files.createTempDirectory("hardened-preview-format-").toFile();
      TdGateway pFake=new TdGateway(shortIds,pRoot);
      JSONObject pObj= mode==0
        ? new JSONObject().put("type",new JSONObject().put("@type","linkPreviewTypePhoto"))
        : new JSONObject();
      pFake.contentOverride(1,new JSONObject().put("@type","messageText")
        .put("text",new JSONObject().put("text","Preview test"))
        .put("link_preview",pObj));
      boolean rejected=false;
      try{send(pRoot,pFake,chat,new ByteArrayOutputStream());}
      catch(IOException error){rejected=error.getMessage().contains("messageText");}
      yes(rejected,"Unrecognized or missing preview type was silently accepted");
    }
    // A known non-media article preview is permitted without an attachment.
    File articleRoot=Files.createTempDirectory("hardened-article-preview-").toFile();
    TdGateway articleFake=new TdGateway(shortIds,articleRoot);
    articleFake.contentOverride(1,new JSONObject().put("@type","messageText")
      .put("text",new JSONObject().put("text","Article test"))
      .put("link_preview",new JSONObject().put("type",
        new JSONObject().put("@type","linkPreviewTypeArticle"))));
    ByteArrayOutputStream articleArchive=new ByteArrayOutputStream();
    send(articleRoot,articleFake,chat,articleArchive);
    checkArchive(articleArchive.toByteArray(),shortIds,8);
    File ordinaryRoot=Files.createTempDirectory("hardened-non-media-").toFile();
    TdGateway ordinary=new TdGateway(shortIds,ordinaryRoot);
    ordinary.contentOverride(1,new JSONObject().put("@type","messageContact")
        .put("contact",new JSONObject().put("first_name","Test")));
    ByteArrayOutputStream ordinaryArchive=new ByteArrayOutputStream();
    send(ordinaryRoot,ordinary,chat,ordinaryArchive);
    checkArchive(ordinaryArchive.toByteArray(),shortIds,8);
    // All three TDLib history paths must refuse data belonging to another chat.
    File metadataRoot=Files.createTempDirectory("hardened-wrong-chat-meta-").toFile();
    TdGateway wrongMetadata=new TdGateway(shortIds,metadataRoot);
    wrongMetadata.misrouteChatMetadata();
    boolean rejectedMetadata=false;
    try{send(metadataRoot,wrongMetadata,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedMetadata=expected.getMessage().contains("другой чат");}
    yes(rejectedMetadata,"Wrong getChat metadata accepted");
    File misrouteRoot=Files.createTempDirectory("hardened-wrong-chat-history-").toFile();
    TdGateway wrongMessage=new TdGateway(shortIds,misrouteRoot);
    wrongMessage.misrouteHistoryMessageAt(11);
    boolean rejectedMessage=false;
    try{send(misrouteRoot,wrongMessage,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedMessage=expected.getMessage().contains("другого чата");}
    yes(rejectedMessage,"Cross-chat history accepted");
    ByteArrayOutputStream afterMisroute=new ByteArrayOutputStream();
    send(misrouteRoot,new TdGateway(shortIds,misrouteRoot),chat,afterMisroute);
    checkArchive(afterMisroute.toByteArray(),shortIds,8);
    File probeRoot=Files.createTempDirectory("hardened-wrong-chat-probe-").toFile();
    TdGateway wrongProbe=new TdGateway(shortIds,probeRoot);
    wrongProbe.misrouteStalledProbe();
    boolean rejectedProbe=false;
    try{send(probeRoot,wrongProbe,chat,new ByteArrayOutputStream());}
    catch(IOException expected){rejectedProbe=expected.getMessage().contains("другого чата");}
    yes(rejectedProbe,"Cross-chat stalled probe accepted");
    ByteArrayOutputStream afterProbe=new ByteArrayOutputStream();
    send(probeRoot,new TdGateway(shortIds,probeRoot),chat,afterProbe);
    checkArchive(afterProbe.toByteArray(),shortIds,8);
    System.out.println("PASS: getChat, history, stalled probe wrong-chat rejection and safe resume");
    System.out.println("PASS strict content coverage: three unknown media variants and rich previews fail closed; progress resumes; known contact succeeds");
    System.out.printf(Locale.ROOT,"PASS Hardened ExportEngine+TVS2+strict verifier: 250 and 2000 messages, eight media kinds, three outages, media retry, interrupted finalization, SHA-256, wrong password; bytes=%d%n",large.size());
  }
}

