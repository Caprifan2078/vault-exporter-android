package org.vault.exporter;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Phase 1: checkpoint every completed page into Keystore-derived encrypted private staging.
 * Phase 2: finalize verified encrypted .tva from staging; interrupted finalization can restart.
 * All cloud files are downloaded on demand using downloadFile(synchronous=true, limit=0).
 */
public final class ExportEngine {
  public interface Progress { void show(String message); }
  public static final class Summary {
    public final int messages, media, missing;
    public final boolean historyFinished;
    public Summary(int m,int f,int e,boolean done){messages=m;media=f;missing=e;historyFinished=done;}
  }
  private static final int FILE_RETRIES=3;
  private final Context app;
  private final TdGateway td;
  private final Progress progress;
  private final String permittedRoot;
  public ExportEngine(Context ctx,TdGateway td,Progress progress) throws Exception {
    app=ctx.getApplicationContext();this.td=td;this.progress=progress;
    permittedRoot=new File(app.getFilesDir(),"tdlib/files").getCanonicalPath()+File.separator;
  }
  private static JSONObject req(String name,String key,Object value) throws JSONException {return TdGateway.cmd(name).put(key,value);}
  private static JSONObject object(JSONObject root,String key){return root==null?null:root.optJSONObject(key);}
  private static JSONObject deepest(JSONObject root,String... keys){
    JSONObject obj=root; for(String k:keys){obj=object(obj,k);if(obj==null)return null;}return obj;
  }
  private static JSONObject largestPhoto(JSONObject message){
    JSONObject photo=deepest(message,"content","photo"); if(photo==null)return null;
    JSONArray sizes=photo.optJSONArray("sizes");if(sizes==null)return null;
    JSONObject best=null;long area=-1;
    for(int i=0;i<sizes.length();i++){
      JSONObject item=sizes.optJSONObject(i);if(item==null)continue;
      JSONObject f=item.optJSONObject("photo");if(f==null || f.optInt("id")<=0)continue;
      long a=(long)item.optInt("width")*item.optInt("height");
      if(a>=area){best=f;area=a;}
    }
    return best;
  }
  /** Select the main media only. Thumbnail files do not replace full-resolution files. */
  private static JSONObject mainFile(JSONObject message){
    JSONObject content=message.optJSONObject("content");if(content==null)return null;
    switch(content.optString("@type")) {
      case "messagePhoto":return largestPhoto(message);
      case "messageVideo":return deepest(message,"content","video","video");
      case "messageVideoNote":return deepest(message,"content","video_note","video");
      case "messageVoiceNote":return deepest(message,"content","voice_note","voice");
      case "messageAudio":return deepest(message,"content","audio","audio");
      case "messageDocument":return deepest(message,"content","document","document");
      case "messageAnimation":return deepest(message,"content","animation","animation");
      case "messageSticker":return deepest(message,"content","sticker","sticker");
      default:return null;
    }
  }
  /** Content variants audited as having no standalone TDLib File to download.
   * Other variants must be explicitly audited before the exporter may call an
   * archive complete. This conservative list intentionally excludes messageText
   * with a rich link preview: the preview can carry an embedded photo/video. */
  private static boolean isVerifiedNonMediaContent(String contentType){
    switch(contentType){
      case "messageContact":
      case "messageLocation":
      case "messageVenue":
      case "messagePoll":
      case "messageDice":
      case "messageCall":
      case "messageChatChangeTitle":
      case "messageChatDeletePhoto":
      case "messageChatAddMembers":
      case "messageChatDeleteMember":
      case "messageChatJoinByLink":
      case "messageChatJoinByRequest":
      case "messageChatSetMessageAutoDeleteTime":
      case "messagePinMessage":
      case "messageScreenshotTaken":
        return true;
      default: return false;
    }
  }

  private static String type(JSONObject msg){
    JSONObject content=msg.optJSONObject("content");String name=content==null?"":content.optString("@type");
    switch(name){
      case "messagePhoto":return "photo";
      case "messageVideo":return "video";
      case "messageVideoNote":return "round_video";
      case "messageVoiceNote":return "voice";
      case "messageAudio":return "audio";
      case "messageDocument":return "document";
      case "messageAnimation":return "animation";
      case "messageSticker":return "sticker";
      default:return "other";
    }
  }
  private static String extension(String category,JSONObject message){
    JSONObject content=message.optJSONObject("content");
    JSONObject doc=object(content,"document");
    String original=doc==null?"":doc.optString("file_name");
    int dot=original.lastIndexOf('.');
    if(dot>0){String candidate=original.substring(dot).toLowerCase(Locale.ROOT);
      if(candidate.matches("\\.[a-z0-9]{1,8}"))return candidate;}
    switch(category){
      case "photo":return ".jpg";
      case "round_video":case "video":case "animation":return ".mp4";
      case "voice":return ".ogg";
      case "audio":return ".mp3";
      case "sticker":return ".webp";
      default:return ".bin";
    }
  }
  private static String mime(JSONObject message,String category){
    JSONObject content=message.optJSONObject("content"); JSONObject metadata=object(content, category.equals("document")?"document":category.equals("voice")?"voice_note":category.equals("round_video")?"video_note":category);
    String val=metadata==null?"":metadata.optString("mime_type","");
    if(!val.isEmpty())return val;
    switch(category){case "photo":return "image/jpeg";case "round_video":case "video":return "video/mp4";case "voice":return "audio/ogg";default:return "application/octet-stream";}
  }
  private static String fileName(JSONObject message){
    JSONObject doc=deepest(message,"content","document");return doc==null?"":doc.optString("file_name","");
  }
  /** Only read/download calls may be repeated; never retries login, logout or modifications. */
  private JSONObject safeRead(JSONObject request, long timeoutSeconds) throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      try { return td.await(request, timeoutSeconds); }
      catch (TelegramRateLimit.Delay limit) {
        // Wait exactly as Telegram requests, plus a small margin; never bypass or rotate identities.
        if (limit.waitSeconds > 120 || attempt != 0)
          throw new IOException("Telegram просит подождать " + limit.waitSeconds + " сек. Экспорт приостановлен, прогресс сохранён.", limit);
        progress.show("Telegram ограничил скорость. Ожидаем " + limit.waitSeconds + " сек. без новых запросов.");
        Thread.sleep((limit.waitSeconds + 2L) * 1000L);
      }
    }
    throw new IOException("Не удалось получить ответ после ограничения Telegram");
  }
  private File cloudDownload(int id) throws Exception {
    Exception last=null;
    for(int attempt=1;attempt<=FILE_RETRIES;attempt++){
      try {
        // synchronous=true waits for a full download. offset=0, limit=0 means ALL bytes.
        JSONObject result=safeRead(req("downloadFile","file_id",id).put("priority",16)
          .put("offset",0).put("limit",0).put("synchronous",true),3600);
        JSONObject local=result.optJSONObject("local");
        if(local==null || !local.optBoolean("is_downloading_completed",false))throw new IOException("TDLib не завершила загрузку файла "+id);
        File path=new File(local.optString("path","")).getCanonicalFile();
        if(!path.isFile() || !path.getPath().startsWith(permittedRoot)) throw new SecurityException("Медиа вне закрытого каталога приложения");
        return path;
      } catch(TelegramRateLimit.Delay limit) { throw limit;
      } catch(IOException limitOrIO) {
        if (limitOrIO.getCause() instanceof TelegramRateLimit.Delay) throw limitOrIO;
        last=limitOrIO;
        if(attempt<FILE_RETRIES){progress.show("Повтор загрузки файла "+id+" ("+(attempt+1)+"/"+FILE_RETRIES+")");Thread.sleep(1000L*attempt);}
      } catch(Exception e) {
        last=e;
        if(attempt<FILE_RETRIES) Thread.sleep(1000L*attempt);
      }
    }
    throw new IOException("Не удалось скачать медиа "+id+" после "+FILE_RETRIES+" попыток; можно продолжить позже",last);
  }
  private static byte[] bytes(StringBuilder builder){return builder.toString().getBytes(StandardCharsets.UTF_8);}
  public Summary export(long chatId,OutputStream output,char[] password) throws Exception {
    JSONObject chat=safeRead(req("getChat","chat_id",chatId),30);
    if(chat.optBoolean("has_protected_content",false))throw new SecurityException("Сохранение содержимого этого чата ограничено");
    if("chatTypeSecret".equals(object(chat,"type")==null?"":object(chat,"type").optString("@type")))
      throw new SecurityException("Облачный API не экспортирует секретные чаты");
    byte[] sessionSecret=new SessionSecrets(app).getOrCreate();
    try(ResumeStore stage=new ResumeStore(new File(app.getFilesDir(),"export-progress"),chatId,sessionSecret)){
      Arrays.fill(sessionSecret,(byte)0);
      ResumeStore.State s=stage.state();
      if(s.pages>0)progress.show("Продолжаем: уже сохранено сообщений: "+s.messages+", файлов: "+s.media);
      while(!s.historyFinished) {
        // TDLib requires the last received ID, not id-1 (message IDs can be sparse).
        long nextFrom=s.cursor;
        JSONObject history=safeRead(req("getChatHistory","chat_id",chatId)
          .put("from_message_id",nextFrom).put("offset",0).put("limit",100).put("only_local",false),120);
        JSONArray messages=history.optJSONArray("messages");
        if(messages==null)throw new IOException("TDLib вернула историю в неизвестном формате");
        ArrayList<JSONObject> page=new ArrayList<>();long oldest=Long.MAX_VALUE;
        long[] rawIds=new long[messages.length()];
        for(int i=0;i<messages.length();i++){
          JSONObject msg=messages.optJSONObject(i);
          if(msg==null)throw new IOException("TDLib вернула неполное сообщение");
          rawIds[i]=msg.optLong("id");
        }
        HistoryCursor.Selection selection=HistoryCursor.select(s.cursor,rawIds);
        for(int i:selection.indices)page.add(messages.getJSONObject(i));
        if(!page.isEmpty())oldest=selection.nextCursor;
        if(messages.length()==0){
          // A second network-enabled query is required before declaring history complete.
          // Never equate a page of already-seen IDs with the end of Telegram history.
          JSONObject confirmation=safeRead(req("getChatHistory","chat_id",chatId)
            .put("from_message_id",nextFrom).put("offset",0).put("limit",100)
            .put("only_local",false),120);
          JSONArray check=confirmation.optJSONArray("messages");
          if(check==null || check.length()!=0)
            throw new IOException("История не подтверждена: Telegram дал разные ответы. Продолжи экспорт позднее.");
          s=new ResumeStore.State(chatId,s.cursor,s.pages,s.messages,s.media,true);
          stage.checkpoint(s);
          break;
        }
        if(selection.stalled) {
          // Retry a strict lower-bound query instead of incorrectly declaring EOF.
          if(s.cursor<=1)throw new IOException("Невозможно подтвердить конец истории: некорректный курсор");
          long probe=s.cursor-1;
          JSONObject lower=safeRead(req("getChatHistory","chat_id",chatId)
              .put("from_message_id",probe).put("offset",0).put("limit",100)
              .put("only_local",false),120);
          JSONArray candidates=lower.optJSONArray("messages");
          if(candidates==null)throw new IOException("Неверный ответ TDLib при проверке нижней границы");
          if(candidates.length()==0) {
            JSONObject check=safeRead(req("getChatHistory","chat_id",chatId)
                .put("from_message_id",probe).put("offset",0).put("limit",100)
                .put("only_local",false),120);
            JSONArray second=check.optJSONArray("messages");
            if(second==null||second.length()!=0)
              throw new IOException("Конец истории не подтверждён повторным запросом");
            s=new ResumeStore.State(chatId,s.cursor,s.pages,s.messages,s.media,true);
            stage.checkpoint(s);
            break;
          }
          long[] candidateIds=new long[candidates.length()];
          for(int i=0;i<candidates.length();i++){
            JSONObject candidate=candidates.optJSONObject(i);
            if(candidate==null)throw new IOException("Недопустимый элемент истории");
            candidateIds[i]=candidate.optLong("id");
          }
          HistoryCursor.Selection lowerSelection=HistoryCursor.select(s.cursor,candidateIds);
          if(lowerSelection.indices.length==0)
            throw new IOException("История не продвигается: TDLib возвращает только сохранённые сообщения");
          for(int i:lowerSelection.indices)page.add(candidates.getJSONObject(i));
          oldest=lowerSelection.nextCursor;
        }
        StringBuilder line=new StringBuilder(),index=new StringBuilder();
        int downloadedOnPage=0;
        for(JSONObject msg:page) {
          long msgId=msg.getLong("id");line.append(msg.toString()).append('\n');
          JSONObject f=mainFile(msg);
          String category=type(msg);
          if(f==null || f.optInt("id")<=0) {
            // A message with known media must never quietly become "success: 0 missing".
            if(!"other".equals(category)) throw new IOException("Медиа из сообщения "+msgId+" недоступно; экспорт можно повторить позже");
            JSONObject content=msg.optJSONObject("content");
            String contentType=content==null?"":content.optString("@type");
            // Fail closed for unknown Telegram message kinds. Many valid TDLib content
            // variants carry embedded or multiple files (paid media, chat photos,
            // gifts, link previews, etc.), so assuming "other" == "no media"
            // silently produces a falsely complete archive.
            if (!("messageText".equals(contentType) &&
                  (content==null || content.optJSONObject("link_preview")==null)) &&
                !isVerifiedNonMediaContent(contentType))
              throw new IOException("Тип сообщения "+contentType+" пока не проверен на полноту вложений; прогресс сохранён");
            continue;
          }
          int fileId=f.getInt("id");
          ResumeStore.Media saved;
          if(stage.hasMedia(msgId,fileId)) {
            saved=stage.mediaFingerprint(msgId,fileId);
          } else {
            File source=cloudDownload(fileId);
            saved=stage.saveMedia(msgId,fileId,source);
            // Remove plaintext TDLib cache copy after it has been durably staged encrypted.
            try{td.await(req("deleteFile","file_id",fileId),30);}catch(Exception ignored){}
          }
          String cat=category,path="media/"+msgId+"/"+fileId+extension(cat,msg);
          index.append(new JSONObject().put("message_id",msgId).put("file_id",fileId)
            .put("category",cat).put("mime_type",mime(msg,cat))
            .put("original_name",fileName(msg)).put("path",path)
            .put("sha256",saved.sha256).put("bytes",saved.bytes).toString()).append('\n');
          downloadedOnPage++;
        }
        int next=s.pages+1;
        stage.putPage(next,bytes(line),bytes(index));
        s=new ResumeStore.State(chatId,oldest,next,s.messages+page.size(),s.media+downloadedOnPage,false);
        // Checkpoint only after EVERY message and attached full-resolution file on page is stored.
        stage.checkpoint(s);
        progress.show("Сохранено: "+s.messages+" сообщений · "+s.media+" медиа. Можно продолжить после перерыва.");
      }
      // No downloads required during finalization; partial final .tva may be recreated from staging.
      progress.show("Все доступные данные получены. Собираем защищённый архив…");
      int files=0;
      LinkedHashSet<Long> users=new LinkedHashSet<>(), senderChats=new LinkedHashSet<>();
      try(ArchiveWriter archive=new ArchiveWriter(output,password)){
        for(int page=1;page<=s.pages;page++) {
          if(!stage.hasPage(page))throw new IOException("Контрольная точка повреждена: страница "+page);
          String pageName=String.format(Locale.ROOT,"messages/page-%06d.jsonl",page);
          // Extract sender identities as metadata without changing the raw TDLib messages.
          try(BufferedReader messageReader=new BufferedReader(new InputStreamReader(stage.openPage(page),StandardCharsets.UTF_8))) {
            String msgLine;
            while((msgLine=messageReader.readLine())!=null) {
              if(msgLine.isEmpty())continue;
              JSONObject sender=new JSONObject(msgLine).optJSONObject("sender_id");
              if(sender==null)continue;
              String ty=sender.optString("@type");
              if("messageSenderUser".equals(ty))users.add(sender.optLong("user_id"));
              if("messageSenderChat".equals(ty))senderChats.add(sender.optLong("chat_id"));
            }
          }
          archive.writeStream(pageName,stage.openPage(page));
          String indexName=String.format(Locale.ROOT,"media-index/page-%06d.jsonl",page);
          // Read one page of encrypted media references and emit authenticated staging files.
          ByteArrayOutputStream buffer=new ByteArrayOutputStream();
          try(InputStream data=stage.openIndex(page)){
            byte[] scratch=new byte[65536];int read;
            while((read=data.read(scratch))!=-1){buffer.write(scratch,0,read);
              if(buffer.size()>16*1024*1024)throw new IOException("Индекс медиа слишком велик");}
          }
          byte[] mediaIndex=buffer.toByteArray();
          archive.writeBytes(indexName,mediaIndex);
          try(BufferedReader reader=new BufferedReader(new StringReader(new String(mediaIndex,StandardCharsets.UTF_8)))){
            String row;
            while((row=reader.readLine())!=null){if(row.isEmpty())continue;
              JSONObject media=new JSONObject(row);
              String name=media.getString("path");
              String hash=archive.writeStream(name,stage.openMedia(media.getLong("message_id"),media.getInt("file_id")));
              if(!hash.equals(media.getString("sha256")))throw new SecurityException("Медиа изменилось: "+name);
              files++;
            }
          }
          progress.show("Упаковка защищённого архива: "+page+"/"+s.pages+" страниц");
        }
        JSONArray participants=new JSONArray();
        for(long userId:users) {
          try {
            JSONObject user=safeRead(req("getUser","user_id",userId),30);
            participants.put(new JSONObject().put("kind","user").put("id",userId)
              .put("first_name",user.optString("first_name"))
              .put("last_name",user.optString("last_name"))
              .put("username",user.optString("username")));
          } catch(Exception e) {participants.put(new JSONObject().put("kind","user").put("id",userId));}
        }
        for(long senderChat:senderChats) {
          try {
            JSONObject identity=safeRead(req("getChat","chat_id",senderChat),30);
            participants.put(new JSONObject().put("kind","chat").put("id",senderChat)
              .put("title",identity.optString("title")));
          } catch(Exception e) {participants.put(new JSONObject().put("kind","chat").put("id",senderChat));}
        }
        archive.writeBytes("participants.json",participants.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject manifest=new JSONObject()
          .put("schema","tg-vault-v1").put("source","TDLib Android")
          .put("chat_id",chatId).put("chat_title",chat.optString("title","Чат"))
          .put("exported_at_utc",Instant.now().toString())
          .put("messages",s.messages).put("media_downloaded",files)
          .put("media_missing",0).put("history_end_reached",s.historyFinished)
          .put("resume_supported",true).put("participants",participants.length())
          .put("verification","Available cloud history; deleted or restricted content cannot be recovered");
        archive.writeBytes("manifest.json",manifest.toString(2).getBytes(StandardCharsets.UTF_8));
      }
      return new Summary(s.messages,files,0,s.historyFinished);
    } finally {Arrays.fill(sessionSecret,(byte)0);}
  }
}
