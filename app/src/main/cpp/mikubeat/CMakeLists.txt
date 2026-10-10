# libmikubeat.so: whole-track tempo (QM-DSP TempoTrackV2) and live beat tracking (BTrack).
# GPL: QM-DSP is GPL-2.0-or-later, BTrack GPL-3.0-or-later, kissfft BSD-3-Clause. See
# ATTRIBUTIONS.md at the repo root and the license files next to each vendored tree.
include(${CMAKE_CURRENT_LIST_DIR}/sources.cmake)

add_library(mikubeat SHARED ${MIKUBEAT_SOURCES} ${MIKUBEAT_DIR}/miku_beat_jni.cpp)
target_include_directories(mikubeat PRIVATE ${MIKUBEAT_INCLUDES})
target_compile_definitions(mikubeat PRIVATE ${MIKUBEAT_DEFINES})
target_compile_options(mikubeat PRIVATE -O2 -Wno-vla-extension -Wno-unused-parameter -Wno-sign-compare)
set_target_properties(mikubeat PROPERTIES DEBUG_POSTFIX "")
find_library(mikubeat-log-lib log)
target_link_libraries(mikubeat ${mikubeat-log-lib})
