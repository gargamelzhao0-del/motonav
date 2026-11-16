idf_component_register(SRCS "format.cpp" "serial_bridge.cpp" INCLUDE_DIRS "include"
    REQUIRES motonav_ble freertos PRIV_REQUIRES motonav_platform log)
