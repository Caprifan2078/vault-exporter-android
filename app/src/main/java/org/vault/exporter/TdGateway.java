package org.vault.exporter;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class TdGateway {
  public static final class TelegramError extends IllegalStateException {
    public final int code;
    TelegramError(int code) {
      super("Telegram отклонил запрос, код " + code + ". При повторной ошибке останови экспорт.");
      this.code=code;
    }
  }
  public interface Listener { void onUpdate(JSONObject event); }
  private final long client;
  private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  // Serialize request dispatch with shutdown: no request may escape after stop().
  private final Object sendLock = new Object();
  private final Listener listener;
  private final Thread reader;
  private volatile boolean running = true;
  private volatile boolean closing = false;

  public TdGateway(Listener callback) {
    this(callback, null);
  }

  /** Only the Android production path passes the app-private cache root.
   * Reserve the global TDLib receiver BEFORE deleting any downloads, so a
   * concurrent client cannot be active while the stale cache is scrubbed.
   */
  public TdGateway(Listener callback, File appPrivateFilesDir) {
    this.listener = callback;
    // The TDLib C API has one global receive queue; multiple reader threads
    // race and can consume another client's responses. Fail closed instead.
    if (!TdNative.acquireReceiver())
      throw new IllegalStateException("TDLib уже используется другой операцией");
    try {
      if (appPrivateFilesDir != null) {
        try { TdCacheStartupCleaner.clean(appPrivateFilesDir); }
        catch (IOException e) { throw new IllegalStateException("Небезопасный кэш TDLib, подключение остановлено", e); }
      }
      client = TdNative.createClient();
      if (client <= 0) throw new IllegalStateException("TDLib не включена в APK: нет официальной libtdjson.so");
      reader = new Thread(() -> {
        boolean closeConfirmed = false;
        try {
          while (running) {
            String response = TdNative.receive(0.5);
            if (response == null) continue;
            try {
              JSONObject obj = new JSONObject(response);
              if (obj.optLong("@client_id", 0) != client) continue;
              JSONObject auth = obj.optJSONObject("authorization_state");
              if ("updateAuthorizationState".equals(obj.optString("@type")) &&
                  auth != null && "authorizationStateClosed".equals(auth.optString("@type"))) {
                closeConfirmed = true;
                running = false;
                if (!closing) listener.onUpdate(obj);
                continue;
              }
              String extra = obj.optString("@extra", "");
              if (!extra.isEmpty()) {
                CompletableFuture<JSONObject> promise = pending.remove(extra);
                if (promise != null) { promise.complete(obj); continue; }
              }
              if (obj.optString("@type").startsWith("update")) listener.onUpdate(obj);
            } catch (Exception ignored) { /* Don't log private Telegram content. */ }
          }
        } finally {
          synchronized(sendLock) {
            running = false;
            for(CompletableFuture<JSONObject> v:pending.values())v.cancel(true);
            pending.clear();
          }
          // A failed reader cannot establish that the native client is closed.
          // Retain the global receiver reservation until process restart.
          if (closeConfirmed) TdNative.releaseReceiver();
        }
      }, "tdlib-event-pump");
      reader.setDaemon(true);
      reader.start();
    } catch (RuntimeException | Error failure) {
      TdNative.releaseReceiver();
      throw failure;
    }
  }
  public CompletableFuture<JSONObject> call(JSONObject request) {
    synchronized(sendLock) {
      if(!running || closing)throw new IllegalStateException("TDLib client is closing or closed");
      String id = Long.toString(sequence.incrementAndGet());
      CompletableFuture<JSONObject> result = new CompletableFuture<>();
      pending.put(id, result);
      // Do not retain timed-out, cancelled or otherwise completed calls indefinitely.
      // Remove only OUR future: late replies must never resolve a different request.
      result.whenComplete((value, error) -> pending.remove(id, result));
      try { request.put("@extra", id); TdNative.send(client, request.toString()); }
      catch (Exception e) { result.completeExceptionally(e); }
      return result;
    }
  }
  public JSONObject await(JSONObject request, long timeoutSeconds) throws Exception {
    CompletableFuture<JSONObject> future = call(request);
    JSONObject response;
    try {
      response = future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException ex) {
      // No retry here: the caller owns resume/retry policy. Late responses are ignored.
      future.cancel(false);
      if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
      throw ex;
    }
    if ("error".equals(response.optString("@type"))) {
      int code = response.optInt("code");
      String message = response.optString("message");
      long seconds = TelegramRateLimit.waitSeconds(code, message);
      if (seconds >= 0) throw new TelegramRateLimit.Delay(seconds);
      // Never include the serialized request or message contents in the exception.
      throw new TelegramError(code);
    }
    return response;
  }
  public static JSONObject cmd(String name) {
    JSONObject req = new JSONObject();
    try { req.put("@type", name); } catch (Exception e) { throw new IllegalStateException(e); }
    return req;
  }
  public void stop() {
    synchronized(sendLock) {
      if(!running || closing)return;
      closing=true;
      // Close the native client while preserving the separate TDLib authorization for restart.
      // This is NOT logOut: only the explicit button ends the Telegram account session.
      try{TdNative.send(client,"{\"@type\":\"close\"}");}catch(Throwable ignored){}
      for(CompletableFuture<JSONObject> v:pending.values())v.cancel(true);
      pending.clear();
    }
    if(Thread.currentThread()!=reader){
      try{reader.join(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
  }
}
