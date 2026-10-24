#include <jni.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <cstdint>

// Runtime-link against OFFICIAL libtdjson.so, installed into APK jniLibs for each ABI.
// Do not load or ship modified/unknown third-party Telegram libraries.
static void* library = nullptr;
static int (*create_client)() = nullptr;
static void (*send_request)(int, const char*) = nullptr;
static const char* (*receive_result)(double) = nullptr;
static std::once_flag init_once;

static bool ready() {
  std::call_once(init_once, [] {
    library = dlopen("libtdjson.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return;
    create_client = (int (*)())dlsym(library, "td_create_client_id");
    send_request = (void (*)(int, const char*))dlsym(library, "td_send");
    receive_result = (const char* (*)(double))dlsym(library, "td_receive");
  });
  return library && create_client && send_request && receive_result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_vault_exporter_TdNative_createClient(JNIEnv*, jclass) {
  if (!ready()) return 0;
  return create_client();
}
extern "C" JNIEXPORT void JNICALL
Java_org_vault_exporter_TdNative_send(JNIEnv* env, jclass, jlong client, jstring json) {
  if (!ready() || !json || client == 0) return;
  // GetStringUTFChars returns JNI *modified* UTF-8 (CESU-8 for emoji).
  // TDLib's JSON interface requires standard UTF-8. Convert UTF-16 ourselves
  // so supplementary Unicode characters are transmitted as 4-byte sequences.
  const jchar* chars = env->GetStringChars(json, nullptr);
  if (!chars) return;
  const jsize length = env->GetStringLength(json);
  std::string utf8;
  utf8.reserve(static_cast<size_t>(length) * 3u);
  for (jsize i = 0; i < length; ++i) {
    uint32_t c = static_cast<uint32_t>(chars[i]);
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length &&
        chars[i+1] >= 0xDC00 && chars[i+1] <= 0xDFFF) {
      c = 0x10000u + ((c - 0xD800u) << 10) +
          (static_cast<uint32_t>(chars[++i]) - 0xDC00u);
    } else if (c >= 0xD800 && c <= 0xDFFF) {
      c = 0xFFFDu;
    }
    if (c < 0x80) utf8.push_back(static_cast<char>(c));
    else if (c < 0x800) {
      utf8.push_back(static_cast<char>(0xC0 | (c >> 6)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    } else if (c < 0x10000) {
      utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    } else {
      utf8.push_back(static_cast<char>(0xF0 | (c >> 18)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
      utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
    }
  }
  env->ReleaseStringChars(json, chars);
  send_request(static_cast<int>(client), utf8.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_vault_exporter_TdNative_receive(JNIEnv* env, jclass, jdouble seconds) {
  if (!ready()) return nullptr;
  const char* response = receive_result(seconds);
  if (!response) return nullptr;
  // TDLib uses standard UTF-8, while JNI NewStringUTF expects modified UTF-8.
  // Convert explicitly to UTF-16 to preserve emoji and non-BMP code points.
  std::u16string decoded;
  const unsigned char *p = reinterpret_cast<const unsigned char*>(response);
  while (*p) {
    uint32_t c = 0;
    if (*p < 0x80) { c=*p++; }
    else if ((*p & 0xE0) == 0xC0 && (p[1] & 0xC0) == 0x80) {
      c=((p[0]&0x1F)<<6)|(p[1]&0x3F); p+=2;
      if (c<0x80) c=0xFFFD;
    } else if ((*p & 0xF0) == 0xE0 && p[1] && p[2] &&
          (p[1]&0xC0)==0x80 && (p[2]&0xC0)==0x80) {
      c=((p[0]&0x0F)<<12)|((p[1]&0x3F)<<6)|(p[2]&0x3F); p+=3;
      if(c<0x800 || (c>=0xD800 && c<=0xDFFF)) c=0xFFFD;
    } else if ((*p & 0xF8) == 0xF0 && p[1] && p[2] && p[3] &&
          (p[1]&0xC0)==0x80 && (p[2]&0xC0)==0x80 && (p[3]&0xC0)==0x80) {
      c=((p[0]&7)<<18)|((p[1]&0x3F)<<12)|((p[2]&0x3F)<<6)|(p[3]&0x3F); p+=4;
      if(c<0x10000 || c>0x10FFFF) c=0xFFFD;
    } else { c=0xFFFD; p++; }
    if (c<0x10000) decoded.push_back((char16_t)c);
    else { c-=0x10000; decoded.push_back((char16_t)(0xD800|(c>>10)));
      decoded.push_back((char16_t)(0xDC00|(c&0x3FF))); }
  }
  return env->NewString(reinterpret_cast<const jchar*>(decoded.data()), (jsize)decoded.size());
}
