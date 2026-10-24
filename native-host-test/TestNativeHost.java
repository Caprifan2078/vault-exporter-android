import org.vault.exporter.TdNative;
public final class TestNativeHost {
  private static void verify(String value) {
    TdNative.send(17, value);
    String actual=TdNative.receive(0.01);
    if(!value.equals(actual))
      throw new AssertionError("JNI UTF-8 roundtrip corrupted: expected="+value+" actual="+actual);
  }
  public static void main(String[] args) {
    if (TdNative.createClient()!=17)throw new AssertionError("Incorrect client ID");
    verify("{\"@type\":\"getChat\",\"chat_id\":1234}");
    verify("{\"name\":\"Привет, мир\"}");
    verify("{\"title\":\"Чат 🐈💙🚀\"}");
    verify("{\"text\":\"\uD83D\uDE01\uD83E\uDDD1\u200D\uD83D\uDCBB\"}");
    System.out.println("PASS JNI C++/Java native offline roundtrip: client ID, ASCII, Cyrillic, emoji, ZWJ");
  }
}
