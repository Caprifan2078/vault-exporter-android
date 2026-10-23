package org.vault.exporter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Strict verifier for OFFLINE SYNTHETIC demo archives only.
 * A valid encryption tag alone does not prove a complete conversation.
 * Does not contact Telegram or open sockets.
 */
public final class DemoArchiveAudit {
  private DemoArchiveAudit(){}
  private static final long MAX_TOTAL_UNCOMPRESSED=256L*1024*1024;
  private static final long MAX_SINGLE_MEDIA=200L*1024*1024;
  /** Populated only after every encrypted block and every indexed attachment passes validation. */
  public static final class Report {
    public final int messages;
    public final int media;
    public final String preview;
    private Report(int messages,int media,String preview){
      this.messages=messages;this.media=media;this.preview=preview;
    }
  }
  private static final Pattern SENDER=Pattern.compile("\\\"sender\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
  private static final Pattern TEXT=Pattern.compile("\\\"text\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
  private static String shortField(Pattern regex,String json){
    Matcher m=regex.matcher(json);
    if(!m.find())return "—";
    String result=m.group(1);
    if(result.length()>140)result=result.substring(0,140)+"…";
    return result.replace("\\n"," ").replace("\\r"," ").replace("\\t"," ");
  }
  private static final Pattern MESSAGE_ID=Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
  private static final Pattern MESSAGE_LINK=Pattern.compile("\"message_id\"\\s*:\\s*(\\d+)");
  private static final Pattern PATH=Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern HASH=Pattern.compile("\"sha256\"\\s*:\\s*\"([a-fA-F0-9]{64})\"");
  private static final Pattern MESSAGE_COUNT=Pattern.compile("\"messages\"\\s*:\\s*(\\d+)");
  private static final Pattern MEDIA_COUNT=Pattern.compile("\"media\"\\s*:\\s*(\\d+)");
  private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
  private static final class MediaLink {
    final long messageId; final String expectedHash;
    MediaLink(long id,String hash){messageId=id; expectedHash=hash;}
  }
  private static String group(Pattern p,String source,String field)throws IOException{
    Matcher m=p.matcher(source);
    if(!m.find())throw new IOException("Нет поля "+field);
    return m.group(1);
  }
  private static String hex(byte[] bytes){
    StringBuilder s=new StringBuilder();
    for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255));
    return s.toString();
  }
  private static String readBounded(InputStream in,int bound)throws IOException{
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192];int n,total=0;
    while((n=in.read(buf))!=-1){
      total+=n;
      if(total>bound)throw new IOException("Слишком большой файл JSON внутри архива");
      out.write(buf,0,n);
    }
    return new String(out.toByteArray(),StandardCharsets.UTF_8);
  }
  private static void checkHeader(String path,byte[] first,int length)throws IOException{
    if(length==0)throw new IOException("Пустой медиафайл "+path);
    boolean valid=true;
    if(path.endsWith(".png")){
      valid=length>=8;
      for(int i=0;valid&&i<PNG.length;i++)if(first[i]!=PNG[i])valid=false;
    }else if(path.endsWith(".wav")){
      valid=length>=12&&new String(first,0,4,StandardCharsets.US_ASCII).equals("RIFF")
          &&new String(first,8,4,StandardCharsets.US_ASCII).equals("WAVE");
    }else if(path.endsWith(".mp4")){
      valid=length>=8&&new String(first,4,4,StandardCharsets.US_ASCII).equals("ftyp");
    }
    if(!valid)throw new IOException("Неверная сигнатура вложения: "+path);
  }
  public static void verify(InputStream file,char[] password)throws Exception{
    inspect(file,password);
  }
  public static Report inspect(InputStream file,char[] password)throws Exception{
    Set<String> entryNames=new HashSet<>();
    Set<Long> messageIds=new HashSet<>();
    Map<String,MediaLink> links=new HashMap<>();
    Map<String,String> present=new HashMap<>();
    String manifest=null;
    int textMessages=0;
    long totalUncompressed=0;
    ArrayDeque<String> lastMessages=new ArrayDeque<>();
    try(ChunkedVault.Decrypt decrypted=new ChunkedVault.Decrypt(file,password);
        ZipInputStream zip=new ZipInputStream(decrypted)){
      ZipEntry entry;
      byte[] buffer=new byte[65536];
      while((entry=zip.getNextEntry())!=null){
        String name=entry.getName();
        ArchiveWriter.validate(name);
        if(entry.isDirectory()||!entryNames.add(name))throw new IOException("Повтор/папка архива: "+name);
        if(entryNames.size()>10000)throw new IOException("Слишком много файлов");
        if(name.equals("manifest.json")){
          if(manifest!=null)throw new IOException("Повтор манифеста");
          manifest=readBounded(zip,1024*1024);
          totalUncompressed+=manifest.getBytes(StandardCharsets.UTF_8).length;
        }else if(name.startsWith("messages/")&&name.endsWith(".jsonl")){
          String content=readBounded(zip,8*1024*1024);
          totalUncompressed+=content.getBytes(StandardCharsets.UTF_8).length;
          for(String line:content.split("\n")){
            if(line.trim().isEmpty())continue;
            long id=Long.parseLong(group(MESSAGE_ID,line,"id сообщения"));
            if(id<1||!messageIds.add(id))throw new IOException("Повтор ID сообщения: "+id);
            textMessages++;
            if(textMessages>200000)throw new IOException("Слишком много сообщений в демо");
            lastMessages.addLast("• "+shortField(SENDER,line)+": "+shortField(TEXT,line));
            if(lastMessages.size()>6)lastMessages.removeFirst();
          }
        }else if(name.startsWith("media-index/")&&name.endsWith(".jsonl")){
          String content=readBounded(zip,4*1024*1024);
          totalUncompressed+=content.getBytes(StandardCharsets.UTF_8).length;
          for(String line:content.split("\n")){
            if(line.trim().isEmpty())continue;
            long id=Long.parseLong(group(MESSAGE_LINK,line,"message_id"));
            String path=group(PATH,line,"path");
            ArchiveWriter.validate(path);
            String sha=group(HASH,line,"sha256").toLowerCase(Locale.ROOT);
            if(links.put(path,new MediaLink(id,sha))!=null)
              throw new IOException("Медиа повторяется в индексе: "+path);
          }
        }else if(name.startsWith("media/")){
          MessageDigest hash=MessageDigest.getInstance("SHA-256");
          byte[] first=new byte[12];int firstLen=0,n;
          long length=0;
          while((n=zip.read(buffer))!=-1){
            hash.update(buffer,0,n);
            int extra=Math.min(n,first.length-firstLen);
            if(extra>0){System.arraycopy(buffer,0,first,firstLen,extra);firstLen+=extra;}
            length+=n;
            totalUncompressed+=n;
            if(length>MAX_SINGLE_MEDIA)throw new IOException("Слишком большой медиафайл демо");
            if(totalUncompressed>MAX_TOTAL_UNCOMPRESSED)throw new IOException("Превышен лимит распакованных данных");
          }
          checkHeader(name,first,firstLen);
          present.put(name,hex(hash.digest()));
        }else throw new IOException("Неизвестный элемент архива: "+name);
        if(totalUncompressed>MAX_TOTAL_UNCOMPRESSED)throw new IOException("Превышен лимит распакованных данных");
        zip.closeEntry();
      }
      // ZipInputStream ends at central directory. Force authentication of the
      // encrypted suffix/terminal block, including any trailing data.
      while(decrypted.read(buffer)!=-1){}
    }
    if(manifest==null||!manifest.contains("\"schema\":\"tg-vault-v1\"")
        ||!manifest.contains("\"synthetic\":true"))
      throw new IOException("Нет валидного манифеста искусственного демо");
    int expectedMessages=Integer.parseInt(group(MESSAGE_COUNT,manifest,"messages"));
    int expectedMedia=Integer.parseInt(group(MEDIA_COUNT,manifest,"media"));
    if(textMessages!=expectedMessages||messageIds.size()!=expectedMessages)
      throw new IOException("Не все сообщения сохранены");
    if(links.size()!=expectedMedia||present.size()!=expectedMedia)
      throw new IOException("Не все медиафайлы сохранены");
    for(Map.Entry<String,MediaLink> link:links.entrySet()){
      MediaLink metadata=link.getValue();
      if(!messageIds.contains(metadata.messageId))
        throw new IOException("Вложение ссылается на отсутствующее сообщение");
      String sha=present.get(link.getKey());
      if(sha==null||!sha.equals(metadata.expectedHash))
        throw new IOException("Неверная контрольная сумма/нет медиа "+link.getKey());
    }
    StringBuilder preview=new StringBuilder();
    for(String text:lastMessages)preview.append(text).append('\n');
    return new Report(textMessages,present.size(),preview.toString().trim());
  }
}