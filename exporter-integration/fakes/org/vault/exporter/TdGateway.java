package org.vault.exporter;
import org.json.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Test-only fake TDLib transport. Never included in an Android release. */
public final class TdGateway {
  private final long[] ids;
  private final File root;
  private int historyCalls=0;
  private int failAt=0;
  private int failFileId=0;
  public TdGateway(long[] ids,File root) {this.ids=ids;this.root=root;}
  public void failOnHistoryCall(int n){failAt=n;}
  public void failDownloadFor(int id){failFileId=id;}
  public int historyCalls(){return historyCalls;}
  public static JSONObject cmd(String type){return new JSONObject().put("@type",type);}
  public static byte[] contentFor(int id){
    switch(id){
      case 501:return "OggS synthetic voice data".getBytes(StandardCharsets.UTF_8);
      case 502:return new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe0,1,2,3,4};
      case 503:case 504:case 507:return new byte[]{0,0,0,16,102,116,121,112,105,115,111,109,1,2,3,4};
      case 505:return "ID3 synthetic audio data".getBytes(StandardCharsets.UTF_8);
      case 506:return "%PDF-1.4\nsynthetic document".getBytes(StandardCharsets.UTF_8);
      case 508:return "RIFF1234WEBPsynthetic".getBytes(StandardCharsets.UTF_8);
      default:throw new IllegalArgumentException("Unknown synthetic file "+id);
    }
  }
  private JSONObject file(int id){return new JSONObject().put("id",id);}
  private JSONObject contentForPosition(int position, long id){
    JSONObject c;
    switch(position){
      case 3: // Voice note, not previously cached.
        return new JSONObject().put("@type","messageVoiceNote").put("voice_note",
          new JSONObject().put("voice",file(501)).put("mime_type","audio/ogg"));
      case 9: // Lower-res thumbnail must not replace main photo.
        JSONArray sizes=new JSONArray().put(new JSONObject()
          .put("width",64).put("height",64).put("photo",file(599)))
          .put(new JSONObject().put("width",1920).put("height",1080).put("photo",file(502)));
        return new JSONObject().put("@type","messagePhoto")
          .put("photo",new JSONObject().put("sizes",sizes));
      case 17:
        return new JSONObject().put("@type","messageVideo")
          .put("video",new JSONObject().put("video",file(503)).put("mime_type","video/mp4"));
      case 25:
        return new JSONObject().put("@type","messageVideoNote")
          .put("video_note",new JSONObject().put("video",file(504)).put("mime_type","video/mp4"));
      case 33:
        return new JSONObject().put("@type","messageAudio")
          .put("audio",new JSONObject().put("audio",file(505)).put("mime_type","audio/mpeg"));
      case 41:
        return new JSONObject().put("@type","messageDocument")
          .put("document",new JSONObject().put("document",file(506))
            .put("file_name","notes.pdf").put("mime_type","application/pdf"));
      case 49:
        return new JSONObject().put("@type","messageAnimation")
          .put("animation",new JSONObject().put("animation",file(507)).put("mime_type","video/mp4"));
      case 57:
        return new JSONObject().put("@type","messageSticker")
          .put("sticker",new JSONObject().put("sticker",file(508)).put("mime_type","image/webp"));
      default:
        return new JSONObject().put("@type","messageText")
          .put("text",new JSONObject().put("text","Synthetic #"+id));
    }
  }
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
      // Always short pages; overlap cursor deliberately, as a real server may.
      int count=Math.min(limit,7);
      for(int j=start;j<ids.length&&j<start+count;j++)
        arr.put(new JSONObject().put("id",ids[j]).put("content",contentForPosition(j,ids[j])));
      return new JSONObject().put("messages",arr);
    }
    if(type.equals("downloadFile")){
      int fileId=req.getInt("file_id");
      if(fileId==failFileId)throw new IOException("SIMULATED_MEDIA_DOWNLOAD_ERROR");
      File f=new File(root,"tdlib/files/fake-"+fileId+".bin");
      f.getParentFile().mkdirs();
      try(FileOutputStream out=new FileOutputStream(f)){out.write(contentFor(fileId));}
      return new JSONObject().put("local",new JSONObject()
         .put("is_downloading_completed",true).put("path",f.getCanonicalPath()));
    }
    if(type.equals("deleteFile"))return new JSONObject().put("@type","ok");
    throw new IOException("Unexpected fake TDLib command "+type);
  }
}
