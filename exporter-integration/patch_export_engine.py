#!/usr/bin/env python3
"""Apply a small, auditable safety patch to the exact pinned v0.9 ExportEngine.
Does NOT connect to Telegram; only used to run fake-TDLib contract tests.
"""
from pathlib import Path
import sys

src = Path(sys.argv[1])
text = src.read_text(encoding="utf-8")
old = "long nextFrom=s.cursor<=1?0:s.cursor-1;"
new = """// TDLib documents from_message_id as the last received message ID.
        // Telegram message IDs are sparse: subtracting one is NOT a valid cursor.
        long nextFrom=s.cursor;"""
assert text.count(old) == 1, "ExportEngine source changed: fail rather than misapply patch"
text = text.replace(old, new)

old2 = '''        if(page.isEmpty())
          throw new IOException("История не продвигается: получены только уже сохранённые сообщения. Архив не объявлен полным.");'''
new2 = '''        if(page.isEmpty()) {
          // An inclusive page may contain only the cursor (the oldest message).
          // Probe older history explicitly; never interpret a short page as EOF.
          if(s.cursor<=1) throw new IOException("Невозможно безопасно продолжить историю: некорректный курсор");
          long probe=s.cursor-1;
          JSONObject older=safeRead(req("getChatHistory","chat_id",chatId)
            .put("from_message_id",probe).put("offset",0).put("limit",100).put("only_local",false),120);
          JSONArray olderList=older.optJSONArray("messages");
          if(olderList==null)throw new IOException("Неверный ответ TDLib при проверке конца истории");
          if(olderList.length()==0) {
            JSONObject confirmation=safeRead(req("getChatHistory","chat_id",chatId)
              .put("from_message_id",probe).put("offset",0).put("limit",100)
              .put("only_local",false),120);
            JSONArray check=confirmation.optJSONArray("messages");
            if(check==null || check.length()!=0)
              throw new IOException("Конец истории не подтверждён двумя запросами");
            s=new ResumeStore.State(chatId,s.cursor,s.pages,s.messages,s.media,true);
            stage.checkpoint(s);
            break;
          }
          for(int i=0;i<olderList.length();i++) {
            JSONObject msg=olderList.optJSONObject(i);
            if(msg==null)continue;
            long id=msg.optLong("id");
            if(id<=0 || id>=s.cursor)continue;
            page.add(msg);
            oldest=Math.min(oldest,id);
          }
          if(page.isEmpty())
            throw new IOException("История не продвигается: TDLib возвращает только сохранённые сообщения");
        }'''
assert text.count(old2)==1, "Missing stalled-history guard; fail closed"
text=text.replace(old2,new2)
src.write_text(text, encoding="utf-8")
print("Patched actual ExportEngine source; no Telegram access")
