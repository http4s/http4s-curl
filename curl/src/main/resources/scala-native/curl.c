#if __has_include(<curl/curl.h>)

#include <curl/curl.h>

CURLMSG org_http4s_curl_CURLMsg_msg(CURLMsg *curlMsg) {
  return curlMsg->msg;
}

CURL *org_http4s_curl_CURLMsg_easy_handle(CURLMsg *curlMsg) {
  return curlMsg->easy_handle;
}

CURLcode org_http4s_curl_CURLMsg_data_result(CURLMsg *curlMsg) {
  return curlMsg->data.result;
}

const char * const * org_http4s_curl_get_protocols(curl_version_info_data *data){
  return data -> protocols;
}

unsigned int org_http4s_curl_get_version_num(curl_version_info_data *data){
  return data -> version_num;
}

CURLversion org_http4s_curl_version_now(){
  // This is the minimum version we need currently
  return CURLVERSION_FIRST;
}

#endif // has_include
