idf_component_register(
    SRCS "ble_transport.cpp"
    INCLUDE_DIRS "include"
    REQUIRES esp_common
    PRIV_REQUIRES bt esp_hw_support
)
