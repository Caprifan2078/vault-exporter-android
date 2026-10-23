package org.vault.exporter;
import org.json.*;
import java.util.*;
import java.io.*;

/** Test-only fake TDLib transport. Never linked into Android build. */
public final class TdGateway {
  private final long[] ids;
  private final File root;
  private int historyCalls=0;
  private int failAt=0;
  public TdGateway(long[] ids,File root) {this.ids=ids;this.root=root;}
  public void failOnHistoryCall(int n){failAt=n;}
  public static JSONObject cmd(String type){return new JSONObject().put("@type",type);}
  public JSONObject await(JSONObject req,long timeout)throws Exception{
    String type=req.optString("@type");
    if(type.equals("getChat"))return new JSONObject().put("title","Synthetic")
      .put("has_protected_content",false).put("type",new JSONObject().put("@type","chatTypePrivate"));
    if(type.equals("getUser"))return new JSONObject().put("first_name","Test");
    if(type.equals("getChatHistory")){
      historyCalls++;
      if(failAt>0&&historyCalls==failAt)throw new IOException("SIMULATED_NETWORK_DROP");
      long from=req.optLong("from_message_id",0);
      int limit=req.optInt("limit",100);
      JSONArray arr=new JSONArray();
      int start=0;
      if(from>0)while(start<ids.length&&ids[start]>from)start++;
      if(start>=ids.length)return new JSONObject().put("messages",arr);
      // Fixed small pages, intentionally include cursor to test overlap filtering.
      for(int j=start;j<ids.length&&j<start+Math.min(limit,7);j++){
        long id=ids[j];
        JSONObject message=new JSONObject().put("id",id)
          .put("content",new JSONObject().put("@type","messageText")
            .put("text",new JSONObject().put("text","Synthetic #"+id)));
        if(id==ids[3]){
          message.put("content",new JSONObject().put("@type","messageVoiceNote")
            .put("voice_note",new JSONObject().put("voice",new JSONObject().put("id",501))
              .put("mime_type","audio/ogg")));
        }
        arr.put(message);
      }
      return new JSONObject().put("messages",arr);
    }
    if(type.equals("downloadFile")){
      File f=new File(root,"tdlib/files/fake-501.ogg");
      f.getParentFile().mkdirs();
      try(FileOutputStream out=new FileOutputStream(f)){out.write("OggS fake voice data".getBytes("UTF-8"));}
      return new JSONObject().put("local",new JSONObject()
         .put("is_downloading_completed",true).put("path",f.getCanonicalPath()));
    }
    if(type.equals("deleteFile"))return new JSONObject().put("@type","ok");
    throw new IOException("Unexpected fake TDLib command "+type);
  }
}