#!/usr/bin/env python3
"""Instrument the pinned synthetic TDLib fake for negative identity tests.

Never used in Android sources or shipping builds. Abort on changed fake revision.
"""
from pathlib import Path
import sys

def once(code, old, new, label):
    if code.count(old)!=1:
        raise ValueError(f'{label}: fake revision unexpected; abort')
    return code.replace(old,new)

def patch(code):
    code=once(code,
      '  private int failFileId=0;',
      '  private int failFileId=0;\n'
      '  private boolean wrongChat, wrongHistory, wrongProbe;\n'
      '  public void rejectMetadataFixture(){wrongChat=true;}\n'
      '  public void rejectHistoryFixture(){wrongHistory=true;}\n'
      '  public void rejectProbeFixture(){wrongProbe=true;}', 'flags')
    code=once(code,
      'if(type.equals("getChat"))return new JSONObject().put("title","Synthetic")',
      'if(type.equals("getChat"))return new JSONObject().put("id",wrongChat?9999L:req.getLong("chat_id")).put("title","Synthetic")', 'getChat')
    code=once(code,
      '      if(start>=ids.length)return new JSONObject().put("messages",arr);',
      '      if(start>=ids.length)return new JSONObject().put("messages",arr);\n'
      '      if(wrongProbe && from==ids[6]) {\n'
      '        arr.put(new JSONObject().put("id",ids[6]).put("chat_id",req.getLong("chat_id"))\n'
      '          .put("content",contentForPosition(6,ids[6])));\n'
      '        return new JSONObject().put("messages",arr);\n'
      '      }', 'stalled probe')
    code=once(code,
      '        arr.put(new JSONObject().put("id",ids[j]).put("content",contentForPosition(j,ids[j])));',
      '        arr.put(new JSONObject().put("id",ids[j])\n'
      '          .put("chat_id", ((wrongHistory && j==10) ||\n'
      '            (wrongProbe && from==ids[6]-1 && j==7)) ? 9999L : req.getLong("chat_id"))\n'
      '          .put("content",contentForPosition(j,ids[j])));', 'chat id content')
    return code

if __name__=='__main__':
    p=Path(sys.argv[1]);p.write_text(patch(p.read_text(encoding='utf-8')),encoding='utf-8')
    print('PASS: synthetic TDLib fixture instrumented for three fail-closed cases')
