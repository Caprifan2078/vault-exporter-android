package org.vault.exporter;

/** JNI wrapper around the official TDLib JSON C interface. Does not contain an MTProto implementation. */
public final class TdNative {
  static { System.loadLibrary("vaultbridge"); }
  // td_receive is process-global and must have only one consumer, including diagnostics.
  private static final java.util.concurrent.atomic.AtomicBoolean RECEIVER_IN_USE = new java.util.concurrent.atomic.AtomicBoolean();
  static boolean acquireReceiver() { return RECEIVER_IN_USE.compareAndSet(false,true); }
  static void releaseReceiver() { RECEIVER_IN_USE.set(false); }
  public static native long createClient();
  public static native void send(long client, String json);
  public static native String receive(double seconds);
  private TdNative() {}
}
