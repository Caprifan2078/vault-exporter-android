package org.vault.exporter;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.*;

/** Regression for TDLib request timeout leak, out-of-order replies, and close cancellation. */
public final class TestTdGatewayLifecycle {
  private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
  @SuppressWarnings("unchecked")
  private static Map<String,?> pending(TdGateway g)throws Exception {
    Field f=TdGateway.class.getDeclaredField("pending");f.setAccessible(true);
    return (Map<String,?>) f.get(g);
  }
  private static String sentId()throws Exception {
    String request=TdNative.outbound.poll(2,TimeUnit.SECONDS);
    check(request!=null,"Request was not dispatched");
    return new JSONObject(request).getString("@extra");
  }
  public static void main(String[] args)throws Exception {
    TdNative.flush();
    ExecutorService pool=Executors.newCachedThreadPool();
    TdGateway gw=new TdGateway(event->{});
    try {
      // 150 asynchronous unanswered calls, all cancelled: no pending map growth.
      List<CompletableFuture<JSONObject>> jobs=new ArrayList<>();
      for(int i=0;i<150;i++)jobs.add(gw.call(TdGateway.cmd("getChatHistory")));
      String first=sentId();
      for(int i=1;i<150;i++)sentId();
      check(pending(gw).size()==150,"Expected requests are not pending");
      jobs.forEach(j->j.cancel(false));
      check(pending(gw).isEmpty(),"Cancelled async calls leaked from pending map");
      TdNative.reply(91,first,"ok");
      Thread.sleep(75);
      check(pending(gw).isEmpty(),"Late reply resurrected cancelled request");

      // A real await() timeout must cancel and release its own entry.
      Future<?> timeout=pool.submit(()-> {
        try {gw.await(TdGateway.cmd("getChatHistory"),0);throw new AssertionError("Missing TimeoutException");}
        catch(TimeoutException expected){} catch(Exception x){throw new RuntimeException(x);}
      });
      String timedOutId=sentId();
      timeout.get(3,TimeUnit.SECONDS);
      check(pending(gw).isEmpty(),"Timed-out await retained request");
      TdNative.reply(91,timedOutId,"ok");
      Thread.sleep(75);
      check(pending(gw).isEmpty(),"Late timed-out response retained request");

      // A new request must only accept the response carrying its own @extra.
      CompletableFuture<JSONObject> one=gw.call(TdGateway.cmd("getChat"));
      CompletableFuture<JSONObject> two=gw.call(TdGateway.cmd("getChat"));
      String oneId=sentId(),twoId=sentId();
      TdNative.reply(91,twoId,"ok");
      check("ok".equals(two.get(2,TimeUnit.SECONDS).optString("@type")),"Wrong second reply");
      check(!one.isDone(),"Received reply for another request");
      TdNative.reply(91,oneId,"error");
      check("error".equals(one.get(2,TimeUnit.SECONDS).optString("@type")),"Wrong first reply");
      check(pending(gw).isEmpty(),"Completed requests retained");

      // A cancelled Android worker must retain its interrupted flag.
      Future<?> interrupted=pool.submit(()-> {
        Thread.currentThread().interrupt();
        try {gw.await(TdGateway.cmd("getChat"),10);throw new AssertionError("Missing interruption");}
        catch(InterruptedException expected){
          check(Thread.currentThread().isInterrupted(),"Interrupted flag was swallowed");
        } catch(Exception e){throw new RuntimeException(e);}
      });
      sentId();
      interrupted.get(2,TimeUnit.SECONDS);
      check(pending(gw).isEmpty(),"Interrupted await retained request");

      // Block a request INSIDE fake native send while shutdown races with it.
      // If close jumps ahead, an outbound read can be sent after client shutdown.
      TdNative.holdNextHistorySend();
      java.util.concurrent.atomic.AtomicReference<CompletableFuture<JSONObject>> active=
          new java.util.concurrent.atomic.AtomicReference<>();
      Future<?> sending=pool.submit(()->active.set(gw.call(TdGateway.cmd("getChatHistory"))));
      check(TdNative.historyEntered.await(2,TimeUnit.SECONDS),"History send gate did not start");
      CountDownLatch stopStarted=new CountDownLatch(1);
      Future<?> stopping=pool.submit(()->{stopStarted.countDown();gw.stop();});
      check(stopStarted.await(2,TimeUnit.SECONDS),"Shutdown did not start");
      try{Thread.sleep(70);}finally{TdNative.historyRelease.countDown();}
      sending.get(3,TimeUnit.SECONDS);
      stopping.get(3,TimeUnit.SECONDS);
      check(active.get()!=null && active.get().isCancelled(),"Stop did not cancel concurrent inflight request");
      check(pending(gw).isEmpty(),"Stop leaked concurrent request");
      JSONObject firstSent=new JSONObject(TdNative.outbound.poll(2,TimeUnit.SECONDS));
      JSONObject lastSent=new JSONObject(TdNative.outbound.poll(2,TimeUnit.SECONDS));
      check("getChatHistory".equals(firstSent.getString("@type")),"Client closed before last request dispatched");
      check("close".equals(lastSent.getString("@type")),"Client was not closed after last request");
      boolean denied=false;
      try{gw.call(TdGateway.cmd("getChat"));}catch(IllegalStateException expected){denied=true;}
      check(denied,"Stopped TDLib accepted new requests");
      System.out.println("PASS: cancellations, timeout, interrupt preservation, ordered concurrent shutdown and no pending leaks (fake TDLib)");
    } finally {gw.stop();pool.shutdownNow();}
  }
}
