#!/usr/bin/env python3
"""Fail-closed chat identity checks for the pinned real v0.9 ExportEngine.

Apply AFTER patch_export_engine.py, with NO Telegram connection.
"""
import sys
from pathlib import Path

def once(text, needle, replacement, name):
    if text.count(needle) != 1:
        raise ValueError(f'{name}: expected one pinned upstream match, refusing changed source')
    return text.replace(needle, replacement)

def guard(code):
    code = once(code,
        '    if(chat.optBoolean("has_protected_content",false))',
        '    if (!chat.has("id") || chat.optLong("id") != chatId)\n'
        '      throw new IOException("TDLib вернула другой чат при проверке выбранной переписки");\n'
        '    if(chat.optBoolean("has_protected_content",false))',
        'getChat metadata')
    code = once(code,
        '          rawIds[i]=msg.optLong("id");',
        '          if (!msg.has("chat_id") || msg.optLong("chat_id") != chatId)\n'
        '            throw new IOException("TDLib вернула сообщение другого чата");\n'
        '          rawIds[i]=msg.optLong("id");',
        'ordinary history page')
    code = once(code,
        '            candidateIds[i]=candidate.optLong("id");',
        '            if (!candidate.has("chat_id") || candidate.optLong("chat_id") != chatId)\n'
        '              throw new IOException("TDLib вернула сообщение другого чата при восстановлении курсора");\n'
        '            candidateIds[i]=candidate.optLong("id");',
        'stalled-cursor recovery page')
    return code

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: guard_chat_identity.py <ExportEngine.java>')
    p=Path(sys.argv[1])
    p.write_text(guard(p.read_text(encoding='utf-8')), encoding='utf-8')
    print('PASS: all three chat identity checks added to the real ExportEngine')
