#pragma once
void diagnostic_test_log(const char *,const char *,const char *);
#define ESP_LOGI(tag,format,line) diagnostic_test_log(tag,format,line)
