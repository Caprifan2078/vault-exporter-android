// OFFLINE TEST ONLY. Intentionally fake: never bundle in an Android APK.
#include <string>
static std::string reply;
extern "C" int td_create_client_id() { return 17; }
extern "C" void td_send(int id, const char* data) {
  reply = (id == 17 && data) ? std::string(data) : "";
}
extern "C" const char* td_receive(double) {
  return reply.empty() ? nullptr : reply.c_str();
}
