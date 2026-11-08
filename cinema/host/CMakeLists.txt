# Suslik Cinema — хост кинотеатра для Cyberpunk 2077 (docs/CYBERPUNK.md). Подключается из desktop-qt с -DSM_CINEMA=ON:
# берёт оттуда ядро (suslik_core) и плеер mpv (MpvEngine, MpvVideo) без копий.
find_package(Qt6 6.4 REQUIRED COMPONENTS Gui Quick OpenGL)

set(SM_CINEMA_VERSION 0.2.0)
set(SM_APP_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop-qt/app/src)

# Геометрия экрана — отдельной библиотекой, чтобы проверять тестами без окна.
add_library(suslik_cinema_geom STATIC QuadMath.cpp QuadMath.h GameView.cpp GameView.h)
target_include_directories(suslik_cinema_geom PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(suslik_cinema_geom PUBLIC Qt6::Gui)

qt_add_executable(suslik_cinema WIN32
  main.cpp
  Host.h
  ScreenQuad.cpp ScreenQuad.h
  Overlay.cpp Overlay.h
  GameAudio.cpp GameAudio.h
  GameLink.cpp GameLink.h
  CinemaSettings.cpp
  ${SM_APP_SRC}/AppDir.cpp ${SM_APP_SRC}/AppDir.h
  ${SM_APP_SRC}/player/Mpv.cpp ${SM_APP_SRC}/player/Mpv.h
  ${SM_APP_SRC}/player/MpvEngine.cpp ${SM_APP_SRC}/player/MpvEngine.h
  ${SM_APP_SRC}/player/MpvVideo.cpp ${SM_APP_SRC}/player/MpvVideo.h
  ${SM_APP_SRC}/player/PlayerLog.cpp ${SM_APP_SRC}/player/PlayerLog.h
)
set_target_properties(suslik_cinema PROPERTIES OUTPUT_NAME "suslik-cinema")
target_include_directories(suslik_cinema PRIVATE
  ${SM_APP_SRC} ${SM_APP_SRC}/player
  ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop-qt/third_party
)

file(GLOB SM_CINEMA_QML RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} CONFIGURE_DEPENDS qml/*.qml)
foreach(f IN LISTS SM_CINEMA_QML)
  get_filename_component(n ${f} NAME)
  set_source_files_properties(${f} PROPERTIES QT_RESOURCE_ALIAS ${n})
endforeach()
qt_add_qml_module(suslik_cinema
  URI SuslikCinema
  VERSION 1.0
  RESOURCE_PREFIX /qt/qml
  QML_FILES ${SM_CINEMA_QML}
)

target_compile_definitions(suslik_cinema PRIVATE SM_VERSION="${SM_CINEMA_VERSION}")
target_link_libraries(suslik_cinema PRIVATE suslik_core suslik_cinema_geom Qt6::Gui Qt6::Quick Qt6::OpenGL)
if(WIN32)
  target_link_libraries(suslik_cinema PRIVATE opengl32 ole32 user32 dwmapi)
endif()
if(MSVC)
  target_compile_options(suslik_cinema PRIVATE /utf-8)
  target_compile_options(suslik_cinema_geom PRIVATE /utf-8)
endif()

if(SM_BUILD_TESTS)
  find_package(Qt6 REQUIRED COMPONENTS Test)
  add_executable(QuadMathTest tests/QuadMathTest.cpp)
  target_link_libraries(QuadMathTest PRIVATE suslik_cinema_geom Qt6::Test)
  if(MSVC)
    target_compile_options(QuadMathTest PRIVATE /utf-8)
  endif()
  add_test(NAME QuadMathTest COMMAND QuadMathTest)
  add_executable(GameViewTest tests/GameViewTest.cpp)
  target_link_libraries(GameViewTest PRIVATE suslik_cinema_geom Qt6::Test)
  if(MSVC)
    target_compile_options(GameViewTest PRIVATE /utf-8)
  endif()
  add_test(NAME GameViewTest COMMAND GameViewTest)
endif()
