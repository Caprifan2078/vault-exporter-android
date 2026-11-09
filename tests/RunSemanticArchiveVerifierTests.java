import org.vault.exporter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Adversarial, independent audit of internally inconsistent encrypted TVA fixtures. */
public final class RunSemanticArchiveVerifierTests {
  static final char[] PASS="offline-semantic-audit-2026".toCharArray();
  static final byte[] PHOTO={1,2,3,4,5,6,7};
  static final byte[] CIRCLE={0,0,0,16,102,116,121,112,1,2,3,4};
  static int cases=0;
  static String sha(byte[] input)throws Exception {
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(input);
    StringBuilder result=new StringBuilder();
    for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));
    return result.toString();
  }
  static String index(long message,int file,String ext,byte[] bytes)throws Exception{
    return "{\"message_id\":"+message+",\"file_id\":"+file+",\"category\":\"video\",\"path\":\"media/"+message+"/"+file+ext+"\",\"sha256\":\""+sha(bytes)+"\",\"bytes\":"+bytes.length+"}\n";
  }
  static String manifest(int messages,int media){return "{\"schema\":\"tg-vault-v1\",\"messages\":"+messages+",\"media_downloaded\":"+media+",\"media_missing\":0,\"history_end_reached\":true}";}
  static final class Entry {String name;byte[] data;Entry(String n,byte[] d){name=n;data=d;}}
  static Entry e(String name,String body){return new Entry(name,body.getBytes(StandardCharsets.UTF_8));}
  static Entry e(String name,byte[] body){return new Entry(name,body);}
  static ArrayList<Entry> fixture()throws Exception {
    ArrayList<Entry> data=new ArrayList<>();
    data.add(e("messages/page-000001.jsonl","{\"id\":100,\"text\":\"escapes: \\\" quote and nested\",\"content\":{\"text\":{\"text\":\"Hi\"}}}\n{\"id\":90}\n"));
    data.add(e("media-index/page-000001.jsonl",index(100,501,".jpg",PHOTO)));
    data.add(e("media/100/501.jpg",PHOTO));
    data.add(e("messages/page-000002.jsonl","{\"id\":70}\n"));
    data.add(e("media-index/page-000002.jsonl",index(70,502,".mp4",CIRCLE)));
    data.add(e("media/70/502.mp4",CIRCLE));
    data.add(e("participants.json","[]"));
    data.add(e("manifest.json",manifest(3,2)));
    return data;
  }
  static byte[] build(List<Entry> entries)throws Exception{
    ByteArrayOutputStream output=new ByteArrayOutputStream();
    try(ArchiveWriter archive=new ArchiveWriter(output,PASS)){
      for(Entry entry:entries)archive.writeBytes(entry.name,entry.data);
    }
    return output.toByteArray();
  }
  static void pass(List<Entry> fixture,int expected)throws Exception{
    int count=ArchiveVerifier.verify(new ByteArrayInputStream(build(fixture)),PASS);
    if(count!=expected)throw new AssertionError("Entry count mismatch "+count);
    cases++;
  }
  static void reject(List<Entry> entries,String why)throws Exception {
    try {ArchiveVerifier.verify(new ByteArrayInputStream(build(entries)),PASS);}
    catch(IOException expected){cases++;return;}
    throw new AssertionError("SEMANTIC CORRUPTION ACCEPTED: "+why);
  }
  static void change(List<Entry> fixture,int pos,String body){fixture.set(pos,e(fixture.get(pos).name,body));}
  static void testCorruption()throws Exception {
    ArrayList<Entry> good=fixture();pass(good,8);
    // Real TVA1 messages and manifest are scoped to exactly one chat.
    // Legacy minimal fixtures without any chat_id remain readable.

    ArrayList<Entry> emptyChat=new ArrayList<>();
    emptyChat.add(e("participants.json","[]"));
    emptyChat.add(e("manifest.json",manifest(0,0).replace("\"messages\":0","\"chat_id\":-1001,\"messages\":0")));
    pass(emptyChat,2);
    ArrayList<Entry> emptyInvalidChat=new ArrayList<>(emptyChat);
    emptyInvalidChat.set(1,e("manifest.json",manifest(0,0).replace("\"messages\":0","\"chat_id\":0,\"messages\":0")));
    reject(emptyInvalidChat,"zero chat id in empty archive");
    ArrayList<Entry> unscopedMessages=fixture();
    change(unscopedMessages,7,manifest(3,2).replace("\"messages\":3","\"chat_id\":-1001,\"messages\":3"));
    reject(unscopedMessages,"manifest identifies a chat but messages omit chat_id");
    ArrayList<Entry> scoped=fixture();
    change(scoped,0,"{\"id\":100,\"chat_id\":-1001}\n{\"id\":90,\"chat_id\":-1001}\n");
    change(scoped,3,"{\"id\":70,\"chat_id\":-1001}\n");
    change(scoped,7,manifest(3,2).replace("\"messages\":3","\"chat_id\":-1001,\"messages\":3"));
    pass(scoped,8);
    ArrayList<Entry> mismatchedChat=new ArrayList<>(scoped);
    change(mismatchedChat,0,"{\"id\":100,\"chat_id\":-1001}\n{\"id\":90,\"chat_id\":-1002}\n");
    reject(mismatchedChat,"mixed chat messages");
    ArrayList<Entry> changedManifestChat=new ArrayList<>(scoped);
    change(changedManifestChat,7,manifest(3,2).replace("\"messages\":3","\"chat_id\":-1002,\"messages\":3"));
    reject(changedManifestChat,"manifest points at another chat");
    ArrayList<Entry> omittedChat=new ArrayList<>(scoped);
    change(omittedChat,3,"{\"id\":70}\n");
    reject(omittedChat,"one message lacks chat_id");
    ArrayList<Entry> invalidChat=new ArrayList<>(scoped);
    change(invalidChat,3,"{\"id\":70,\"chat_id\":\"-1001\"}\n");
    reject(invalidChat,"chat_id as string");
    ArrayList<Entry> missingManifestScope=new ArrayList<>(scoped);
    change(missingManifestScope,7,manifest(3,2));
    pass(missingManifestScope,8); // pre-manifest archives: consistent IDs still checked
    // Paid media, chat photo animations and link previews may carry more than
    // one different TDLib file in the SAME message. Their TVA must verify.
    ArrayList<Entry> multi=fixture();
    multi.set(1,e("media-index/page-000001.jsonl",
      index(100,501,".jpg",PHOTO)+index(100,503,".mp4",CIRCLE)));
    multi.add(3,e("media/100/503.mp4",CIRCLE));
    multi.set(8,e("manifest.json",manifest(3,3)));
    pass(multi,9);
    // A reused (message_id, file_id) must still be rejected even if the
    // attacker changes the filename/extension to evade duplicate-path checks.
    ArrayList<Entry> duplicateFileId=fixture();
    duplicateFileId.set(1,e("media-index/page-000001.jsonl",
      index(100,501,".jpg",PHOTO)+index(100,501,".mp4",CIRCLE)));
    duplicateFileId.add(3,e("media/100/501.mp4",CIRCLE));
    duplicateFileId.set(8,e("manifest.json",manifest(3,3)));
    reject(duplicateFileId,"same message and TDLib file id under distinct paths");
    ArrayList<Entry> a;
    a=fixture();change(a,7,manifest(300,2));reject(a,"inflated message count");
    a=fixture();change(a,7,manifest(3,200));reject(a,"inflated media count");
    a=fixture();change(a,7,manifest(2,2));reject(a,"missing message count");
    a=fixture();change(a,7,manifest(3,1));reject(a,"missing media count");
    a=fixture();change(a,7,manifest(3,2).replace("\"media_missing\":0","\"media_missing\":1"));reject(a,"missing media");
    a=fixture();change(a,7,manifest(3,2).replace("true","false"));reject(a,"unfinished history");
    a=fixture();change(a,7,manifest(3,2).replace("\"messages\":3","\"messages\":\"3\""));reject(a,"numeric string counts");
    a=fixture();change(a,7,manifest(3,2).replace("true","\"true\""));reject(a,"boolean string");
    a=fixture();change(a,7,manifest(3,2).replace("tg-vault-v1","another-schema"));reject(a,"unknown schema");
    a=fixture();change(a,7,manifest(3,2).replace("\"messages\":3","\"messages\":3,\"messages\":3"));reject(a,"duplicate JSON manifest key");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace(sha(PHOTO),"0".repeat(64)));reject(a,"false media hash");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"bytes\":7","\"bytes\":999999"));reject(a,"false size");
    a=fixture();a.set(2,e("media/100/501.jpg",new byte[]{9,9,9,9,9,9,9}));reject(a,"modified media bytes");
    a=fixture();a.remove(2);reject(a,"missing media payload");
    a=fixture();a.add(3,e("media/100/999.mp4",PHOTO));reject(a,"unreferenced media payload");
    a=fixture();change(a,1,index(123456,501,".jpg",PHOTO));reject(a,"orphan message ref");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO)+index(100,501,".jpg",PHOTO));reject(a,"duplicate media index row");
    a=fixture();change(a,0,"{\"id\":100}\n{\"id\":100}\n");reject(a,"duplicate message ID on page");
    a=fixture();change(a,3,"{\"id\":90}\n");reject(a,"duplicate message ID across pages");
    a=fixture();change(a,3,"{\"id\":110}\n");reject(a,"ascending message ID across pages");
    a=fixture();change(a,0,"{\"id\":\"100\"}\n{\"id\":90}\n");reject(a,"string message IDs");
    a=fixture();change(a,0,"{\"id\":100,\"id\":100}\n{\"id\":90}\n");reject(a,"duplicate JSON message id key");
    a=fixture();change(a,0,"{\"id\":100,\"content\":{\"caption\":{\"text\":\"first\",\"text\":\"second\"}}}\n{\"id\":90}\n");reject(a,"duplicate nested message field");
    a=fixture();change(a,6,"[{\"name\":{\"first_name\":\"A\",\"first_name\":\"B\"}}]");reject(a,"duplicate nested participants field");
    a=fixture();change(a,7,manifest(3,2).replace("\"schema\"", "\"meta\":{\"value\":1,\"value\":2},\"schema\""));reject(a,"duplicate nested manifest field");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"category\"", "\"metadata\":{\"x\":1,\"x\":2},\"category\""));reject(a,"duplicate nested media-index field");
    a=fixture();change(a,0,"{\"id\":100,}\n{\"id\":90}\n");reject(a,"bad JSON");
    a=fixture();change(a,0,"{\"id\":100}\n".repeat(2001));reject(a,"unbounded page count");
    a=fixture();a.set(3,e("messages/page-000003.jsonl","{\"id\":70}\n"));reject(a,"missing page numbering");
    a=fixture();a.remove(6);reject(a,"missing participants");
    a=fixture();a.add(e("extra.txt","hello"));reject(a,"unexpected file after manifest");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("media/100/501.jpg","media/../../evil.jpg"));reject(a,"path traversal");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"sha256\"","\"digest\""));reject(a,"missing SHA256");
    a=fixture();change(a,1,index(100,501,".jpg",PHOTO).replace("\"bytes\":7","\"bytes\":\"7\""));reject(a,"string length field");
    a=fixture();change(a,0,"{\"id\":100}\n{\"id\":90,\"text\":\""+"x".repeat(2*1024*1024)+"\"}\n");reject(a,"oversized JSONL row");
    a=fixture();change(a,6,"not-json");reject(a,"malformed participants JSON");
    a=fixture();change(a,6,"[{\"id\":1},]");reject(a,"participants trailing comma");
    a=fixture();change(a,6,"[42]");reject(a,"nonobject participants");
    a=fixture();change(a,7,manifest(3,2).replace("\"schema\"", "\"participants\":2,\"schema\""));reject(a,"participant count mismatch");
  }
  public static void main(String[] args)throws Exception {
    testCorruption();
    System.out.println("PASS "+cases+" verifier fixtures: complete archive + adversarial counts, ids, malformed JSON, tamper, path and missing file rejection");
  }
}
