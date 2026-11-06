package org.vault.exporter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
/** In-process synthetic TDLib responses; does not load JNI or contact Telegram. */
public final class TdNative {
  static final BlockingQueue<String> inbound=new LinkedBlockingQueue<>();
  static final BlockingQueue<String> outbound=new LinkedBlockingQueue<>();
  static final AtomicInteger nextClient=new AtomicInteger(91);
  static final AtomicBoolean gateNextHistory=new AtomicBoolean();
  static volatile CountDownLatch historyEntered=new CountDownLatch(0);
  static volatile CountDownLatch historyRelease=new CountDownLatch(0);
  static void holdNextHistorySend(){
    historyEntered=new CountDownLatch(1);
    historyRelease=new CountDownLatch(1);
    gateNextHistory.set(true);
  }
  static int createClient(){return nextClient.getAndIncrement();}
  static void send(long client,String request){
    if(request.contains("\"@type\":\"getChatHistory\"") && gateNextHistory.compareAndSet(true,false)){
      historyEntered.countDown();
      try {
        if(!historyRelease.await(5,TimeUnit.SECONDS))
          throw new IllegalStateException("Synthetic TDLib send gate timeout");
      } catch(InterruptedException e){
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Synthetic TDLib send interrupted",e);
      }
    }
    outbound.add(request);
  }
  static String receive(double seconds){
    try{return inbound.poll(Math.max(1,(long)(seconds*1000)), TimeUnit.MILLISECONDS);}
    catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
  }
  static void reply(long client,String id,String type){
    inbound.add(new JSONObject().put("@client_id",client).put("@extra",id).put("@type",type).toString());
  }
  static void error(long client,String id,int code){
    inbound.add(new JSONObject().put("@client_id",client).put("@extra",id).put("@type","error").put("code",code).put("message","SYNTHETIC").toString());
  }
  static void flush(){inbound.clear();outbound.clear();}
}
