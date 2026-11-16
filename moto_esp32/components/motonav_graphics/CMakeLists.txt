idf_component_register(
    SRCS "route_map.cpp"
    INCLUDE_DIRS "include"
    REQUIRES lvgl
    PRIV_REQUIRES heap
)
# This component contains the bounded per-frame software rasterizer. Preserve
# IEEE validation/geometry semantics; do not use fast-math or global flags.
target_compile_options(${COMPONENT_LIB} PRIVATE -O3)
