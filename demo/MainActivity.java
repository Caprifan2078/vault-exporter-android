package org.vault.exporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fully OFFLINE demonstration: no Telegram account, no socket, no INTERNET permission.
 * Creates synthetic messages + real generated media and encrypts an actual TVA archive.
 */
public final class MainActivity extends Activity {
  private static final int SAVE_FILE=2026;
  private static final int BG=0xff090f20, CARD=0xff141d36, WHITE=0xfff4f7ff,
      MUTED=0xffa8b8d2, BLUE=0xff617cf7, CYAN=0xff4cdac7;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private LinearLayout body;
  private TextView state, progressLabel;
  private ProgressBar progress;
  private char[] pendingPassword;
  private volatile boolean busy;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
    render();
  }
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  private GradientDrawable shape(int color,int radius){
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
  }
  private TextView text(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
    if(bold)t.setTypeface(null,Typeface.BOLD);return t;
  }
  private void addText(LinearLayout target,String s,int size,int color,boolean bold,int bottom){
    TextView t=text(s,size,color,bold);t.setPadding(0,0,0,dp(bottom));target.addView(t);
  }
  private LinearLayout panel(int background){
    LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
    p.setPadding(dp(20),dp(20),dp(20),dp(20));p.setBackground(shape(background,23));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);body.addView(p,lp);return p;
  }
  private void button(LinearLayout target,String label,boolean primary,Runnable run){
    TextView b=text(label,16,primary?WHITE:MUTED,true);b.setGravity(Gravity.CENTER);
    GradientDrawable d=primary
        ?new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff526de8,0xff7967df})
        :shape(0xff27334e,16);
    d.setCornerRadius(dp(16));b.setBackground(d);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(55));lp.topMargin=dp(10);
    target.addView(b,lp);b.setOnClickListener(v->{if(!busy)run.run();});
  }
  private void render(){
    ScrollView outer=new ScrollView(this);outer.setFillViewport(true);outer.setBackgroundColor(BG);
    body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20),dp(28),dp(20),dp(32));outer.addView(body);setContentView(outer);

    TextView top=text("✦  VAULT LABS  /  OFFLINE",11,CYAN,true);
    top.setLetterSpacing(.15f);body.addView(top);
    addText(body,"Vault Exporter",31,WHITE,true,4);
    addText(body,"Личный архив. Под твоим контролем.",14,MUTED,false,18);

    LinearLayout hero=panel(CARD);
    addText(hero,"ДЕМОВЕРСИЯ · БЕЗ ИНТЕРНЕТА",11,CYAN,true,12);
    addText(hero,"Сохрани разговор\nна долгие годы.",24,WHITE,true,10);
    addText(hero,"Проверь настоящий зашифрованный архив на вымышленной переписке. Никакой вход в Telegram не требуется.",14,MUTED,false,14);
    addText(hero,"●  Сеть отключена   ●  Файлы только на телефоне",12,CYAN,true,0);

    LinearLayout summary=panel(0xff111b31);
    addText(summary,"Что будет в тестовом архиве",17,WHITE,true,10);
    addText(summary,"✓  120 сообщений и ответы",14,MUTED,false,8);
    addText(summary,"✓  Тестовые фотографии PNG",14,MUTED,false,8);
    addText(summary,"✓  Настоящее голосовое WAV",14,MUTED,false,8);
    addText(summary,"✓  Тестовый видеокружочек MP4",14,MUTED,false,8);
    addText(summary,"✓  Связь вложений с сообщениями",14,MUTED,false,0);

    LinearLayout controls=panel(CARD);
    addText(controls,"Попробовать самостоятельно",18,WHITE,true,8);
    addText(controls,"Создание архива проходит на устройстве. Ты выбираешь место сохранения и собственный пароль.",14,MUTED,false,4);
    button(controls,"Создать защищённый тестовый архив  →",true,this::askPassword);
    button(controls,"Посмотреть вымышленную переписку",false,this::showConversation);

    LinearLayout stage=panel(0xff111b31);
    addText(stage,"Состояние",15,WHITE,true,8);
    state=text("Готово к безопасному офлайн-тесту",13,MUTED,false);stage.addView(state);
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(5));p.topMargin=dp(12);stage.addView(progress,p);
    progressLabel=text("0%",12,CYAN,true);progressLabel.setPadding(0,dp(8),0,0);stage.addView(progressLabel);

    addText(body,"Важно: это проверка архивирования, а НЕ подключение к Telegram. Ни твой номер, ни аккаунт, ни переписки не используются.",12,MUTED,false,0);
  }
  private void showConversation(){
    String[] msg={"10:02 • Аня: Привет! Сохраним эту переписку?",
      "10:03 • Максим: Да. И все медиа тоже.",
      "10:05 • Аня: Добавила фотографию 📷",
      "10:06 • Максим: Записал голосовое 🎙",
      "10:07 • Аня: А вот видеокружочек ⭕",
      "10:10 • Максим: Отлично! Архив проверим по SHA-256."};
    new AlertDialog.Builder(this).setTitle("Пример · вымышленные собеседники")
      .setMessage(android.text.TextUtils.join("\n\n",msg))
      .setPositiveButton("Закрыть",null).show();
  }
  private void askPassword(){
    LinearLayout inputs=new LinearLayout(this);inputs.setOrientation(LinearLayout.VERTICAL);inputs.setPadding(dp(20),dp(8),dp(20),0);
    EditText p=new EditText(this),check=new EditText(this);
    for(EditText e:new EditText[]{p,check}){
      e.setSingleLine(true);e.setTextColor(0xff15233d);
      e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
      e.setHint(e==p?"Пароль архива (от 14 символов)":"Повтори пароль");
      inputs.addView(e,new LinearLayout.LayoutParams(-1,dp(62)));
    }
    new AlertDialog.Builder(this).setTitle("Защити тестовый архив")
      .setMessage("Пароль понадобится, чтобы открыть файл .tva. Он не отправляется никуда.")
      .setView(inputs).setNegativeButton("Отмена",null)
      .setPositiveButton("Выбрать папку",(d,w)->{
        char[] first=new char[p.length()],other=new char[check.length()];
        p.getText().getChars(0,p.length(),first,0);check.getText().getChars(0,check.length(),other,0);
        p.setText("");check.setText("");
        boolean good=first.length>=14&&Arrays.equals(first,other);
        Arrays.fill(other,'\0');
        if(!good){Arrays.fill(first,'\0');showMessage("Пароли не совпадают или короче 14 символов.");return;}
        pendingPassword=first;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE,"vault-demo-encrypted.tva");
        try{startActivityForResult(intent,SAVE_FILE);}
        catch(Exception e){destroyPassword();showMessage("Не удалось открыть выбор места сохранения.");}
      }).show();
  }
  private void destroyPassword(){
    if(pendingPassword!=null){Arrays.fill(pendingPassword,'\0');pendingPassword=null;}
  }
  @Override protected void onActivityResult(int req,int res,Intent data){
    super.onActivityResult(req,res,data);
    if(req!=SAVE_FILE)return;
    if(res!=RESULT_OK||data==null||data.getData()==null){destroyPassword();return;}
    final Uri target=data.getData();
    final char[] pass=pendingPassword;pendingPassword=null;
    if(pass==null)return;
    busy=true;progress.setProgress(2);showStatus("Собираем вымышленные сообщения…",2);
    worker.execute(()->{
      File staged=null;
      try{
        staged=File.createTempFile("vault-demo-",".tva",getCacheDir());
        try(OutputStream o=new BufferedOutputStream(new FileOutputStream(staged));
            ArchiveWriter archive=new ArchiveWriter(o,pass)){
          archive.writeBytes("messages/page-000001.jsonl",syntheticMessages());
          postProgress("Фотография и вложения…",32);
          byte[] png=makeImage();
          archive.writeBytes("media/103/photo.png",png);
          archive.writeBytes("media/105/voice.wav",makeVoice());
          postProgress("Видео и индекс файлов…",56);
          try(InputStream in=getAssets().open("demo_round.mp4")){
            archive.writeStream("media/107/round.mp4",in);
          }
          archive.writeBytes("media-index/page-000001.jsonl",(
            "{\"message_id\":103,\"type\":\"photo\",\"path\":\"media/103/photo.png\"}\n"+
            "{\"message_id\":105,\"type\":\"voice\",\"path\":\"media/105/voice.wav\"}\n"+
            "{\"message_id\":107,\"type\":\"round_video\",\"path\":\"media/107/round.mp4\"}\n"
          ).getBytes(StandardCharsets.UTF_8));
          archive.writeBytes("manifest.json",(
            "{\"schema\":\"tg-vault-v1\",\"synthetic\":true,\"messages\":120,\"media\":3,"+
            "\"source\":\"offline_demo\",\"warning\":\"FAKE DATA - NOT FROM TELEGRAM\"}"
          ).getBytes(StandardCharsets.UTF_8));
        }
        postProgress("Проверяем зашифрованный файл…",76);
        try(InputStream in=new FileInputStream(staged)){
          if(ArchiveVerifier.verify(in,pass)!=5)
            throw new IOException("Неожиданная структура архива");
        }
        postProgress("Сохраняем в выбранную папку…",88);
        try(InputStream in=new BufferedInputStream(new FileInputStream(staged));
            OutputStream out=getContentResolver().openOutputStream(target,"w")){
          if(out==null)throw new IOException("Папка не разрешила запись");
          byte[] block=new byte[65536];int n;
          while((n=in.read(block))!=-1)out.write(block,0,n);
          out.flush();
        }
        postProgress("Готово: архив проверен и сохранён",100);
        runOnUiThread(()->new AlertDialog.Builder(this)
          .setTitle("Архив готов")
          .setMessage("Тестовый .tva сохранён. Он зашифрован, а содержимое проверено. Это вымышленные данные; Telegram не использовался.")
          .setPositiveButton("Отлично",null).show());
      }catch(Exception e){
        postProgress("Не удалось сохранить: "+e.getClass().getSimpleName(),0);
      }finally{
        Arrays.fill(pass,'\0');
        if(staged!=null&&!staged.delete())staged.deleteOnExit();
        busy=false;
      }
    });
  }
  private byte[] syntheticMessages() {
    StringBuilder out=new StringBuilder();
    for(int i=1;i<=120;i++){
      String sender=i%2==0?"Максим":"Аня";
      String content=i==103?"[Фото]":i==105?"[Голосовое]":i==107?"[Кружочек]":"Вымышленное сообщение "+i+". Архив должен сохранять историю.";
      out.append("{\"id\":").append(i).append(",\"date\":1792760400,")
        .append("\"sender\":\"").append(sender).append("\",\"text\":\"").append(content)
        .append("\",\"reply_to_message_id\":").append(i>1?i-1:0).append("}\n");
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }
  private byte[] makeImage() throws Exception{
    Bitmap b=Bitmap.createBitmap(480,300,Bitmap.Config.ARGB_8888);
    Canvas c=new Canvas(b);c.drawColor(0xff153352);
    Paint p=new Paint(3);p.setColor(CYAN);p.setTextSize(42);
    c.drawText("VAULT  /  DEMO",40,110,p);
    p.setTextSize(21);p.setColor(WHITE);c.drawText("Synthetic image — not Telegram data",40,170,p);
    ByteArrayOutputStream o=new ByteArrayOutputStream();
    b.compress(Bitmap.CompressFormat.PNG,100,o);b.recycle();return o.toByteArray();
  }
  private byte[] makeVoice() throws Exception{
    int rate=8000,count=rate*2;
    ByteArrayOutputStream o=new ByteArrayOutputStream();
    DataOutputStream d=new DataOutputStream(o);
    writeAscii(d,"RIFF");writeLE(d,36+count*2,4);writeAscii(d,"WAVEfmt ");
    writeLE(d,16,4);writeLE(d,1,2);writeLE(d,1,2);writeLE(d,rate,4);
    writeLE(d,rate*2,4);writeLE(d,2,2);writeLE(d,16,2);
    writeAscii(d,"data");writeLE(d,count*2,4);
    for(int i=0;i<count;i++){
      double a=Math.sin(2*Math.PI*440*i/rate)*Math.sin(Math.PI*i/count);
      writeLE(d,(short)(a*3500)&0xffff,2);
    }
    return o.toByteArray();
  }
  private static void writeAscii(DataOutputStream d,String s)throws Exception{d.write(s.getBytes(StandardCharsets.US_ASCII));}
  private static void writeLE(DataOutputStream d,int x,int bytes)throws Exception{for(int i=0;i<bytes;i++)d.write((x>>>(8*i))&255);}
  private void showMessage(String msg){new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Понятно",null).show();}
  private void showStatus(String s,int n){runOnUiThread(()->{state.setText(s);progress.setProgress(n);progressLabel.setText(n+"%");});}
  private void postProgress(String s,int n){showStatus(s,n);}
  @Override protected void onDestroy(){destroyPassword();worker.shutdown();super.onDestroy();}
}