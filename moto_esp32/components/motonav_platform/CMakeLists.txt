idf_component_register(
    SRCS "platform.cpp"
    INCLUDE_DIRS "include"
    REQUIRES esp_common
    PRIV_REQUIRES nvs_flash esp_hw_support esp_timer esp_app_format heap
)
