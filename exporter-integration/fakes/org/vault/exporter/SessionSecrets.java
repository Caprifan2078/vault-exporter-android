package org.vault.exporter;
import android.content.Context;
import java.util.Arrays;
public final class SessionSecrets {
  public SessionSecrets(Context unused){}
  public byte[] getOrCreate(){byte[] key=new byte[32];Arrays.fill(key,(byte)7);return key;}
}
