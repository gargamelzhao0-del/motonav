idf_component_register(
    SRCS "ble_transport.cpp"
    INCLUDE_DIRS "include"
    REQUIRES esp_common
    PRIV_REQUIRES bt esp_hw_support
)

# ESP-IDF 5.5 diagnostic build only: inspect error headers, never ATT payloads.
if(MOTONAV_NAV_METRICS)
    target_sources(${COMPONENT_LIB} PRIVATE "att_error_trace.cpp")
    target_link_libraries(${COMPONENT_LIB} INTERFACE "-Wl,--wrap=ble_att_tx" "-Wl,--undefined=__wrap_ble_att_tx")
endif()
