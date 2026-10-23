#!/usr/bin/env python3
"""Integrate tested HistoryCursor with the pinned real v0.9 ExportEngine.
The patch refuses unknown source revisions. It never connects to Telegram.
"""
from pathlib import Path
import sys

src=Path(sys.argv[1])
code=src.read_text(encoding="utf-8")
old="""        // Cursor-1 prevents re-fetching the last saved message on small TDLib pages.
        long nextFrom=s.cursor<=1?0:s.cursor-1;"""
new="""        // TDLib: use the last received message as from_message_id.
        // Message IDs are sparse; subtracting one is not a correct page cursor.
        long nextFrom=s.cursor;"""
assert code.count(old)==1, "Unsafe/changed cursor logic: abort patch"
code=code.replace(old,new)
old="""        ArrayList<JSONObject> page=new ArrayList<>();long oldest=Long.MAX_VALUE;
        for(int i=0;i<messages.length();i++){
          JSONObject msg=messages.optJSONObject(i);if(msg==null)continue;
          long id=msg.optLong("id");
          if(id<=0 || (s.cursor!=0 && id>=s.cursor))continue;
          page.add(msg);oldest=Math.min(oldest,id);
        }"""
new="""        ArrayList<JSONObject> page=new ArrayList<>();long oldest=Long.MAX_VALUE;
        long[] rawIds=new long[messages.length()];
        for(int i=0;i<messages.length();i++){
          JSONObject msg=messages.optJSONObject(i);
          if(msg==null)throw new IOException("TDLib вернула неполное сообщение");
          rawIds[i]=msg.optLong("id");
        }
        HistoryCursor.Selection selection=HistoryCursor.select(s.cursor,rawIds);
        for(int i:selection.indices){
          page.add(messages.getJSONObject(i));
        }
        if(!page.isEmpty())oldest=selection.nextCursor;"""
assert code.count(old)==1, "Changed raw-page parser: abort patch"
code=code.replace(old,new)
old="""        if(page.isEmpty())
          throw new IOException("История не продвигается: получены только уже сохранённые сообщения. Архив не объявлен полным.");"""
new="""        if(selection.stalled) {
          // Some TDLib versions include the cursor but no older message.
          // Query strictly below it. Treat uncertain or inconsistent replies
          // as a resumable error, never as a complete export.
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
        }"""
assert code.count(old)==1, "Changed stalled-page parser: abort patch"
code=code.replace(old,new)
src.write_text(code,encoding="utf-8")
print("PASS: integrated HistoryCursor into the REAL ExportEngine (offline overlay)")
