package android.content;

import java.io.File;

/** Test-only Android Context replacement. Never packaged into the APK. */
public class Context {
  private final File files;
  public Context(File files){ this.files=files; }
  public Context getApplicationContext(){ return this; }
  public File getFilesDir(){ return files; }
}
