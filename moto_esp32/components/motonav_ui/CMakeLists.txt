idf_component_register(SRCS "navigation_ui.cpp" "navigation_hud.cpp" "map_annotations.cpp" "dashboard.cpp" "label_cache.cpp" "transparent_label_cache.cpp"
    "fonts/motonav_speed_66.c" "fonts/motonav_distance_36.c" "fonts/motonav_small_14.c" "fonts/motonav_ui_18.c" "fonts/motonav_ui_18_ext.c"
    INCLUDE_DIRS "include" REQUIRES motonav_core motonav_graphics lvgl)
