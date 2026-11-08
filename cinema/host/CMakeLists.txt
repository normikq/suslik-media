# Suslik Cinema — хост кинотеатра для Cyberpunk 2077 (docs/CYBERPUNK.md). Подключается из desktop-qt с -DSM_CINEMA=ON:
# берёт оттуда ядро (suslik_core) и плеер mpv (MpvEngine, MpvVideo) без копий.
find_package(Qt6 6.4 REQUIRED COMPONENTS Gui Quick OpenGL)

set(SM_CINEMA_VERSION 0.5.0)
set(SM_APP_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop-qt/app/src)

# Геометрия экрана — отдельной библиотекой, чтобы проверять тестами без окна.
add_library(suslik_cinema_geom STATIC QuadMath.cpp QuadMath.h GameView.cpp GameView.h)
target_include_directories(suslik_cinema_geom PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(suslik_cinema_geom PUBLIC Qt6::Gui)
# Выбор релиза и проверка адреса зеркала — тоже без окна и сети
add_library(suslik_cinema_pick STATIC ReleasePick.cpp ReleasePick.h)
target_include_directories(suslik_cinema_pick PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(suslik_cinema_pick PUBLIC suslik_core)

qt_add_executable(suslik_cinema WIN32
  main.cpp
  Host.h
  ScreenQuad.cpp ScreenQuad.h
  Overlay.cpp Overlay.h
  GameAudio.cpp GameAudio.h
  GameLink.cpp GameLink.h
  CinemaSettings.cpp
  CinemaSession.cpp CinemaSession.h
  RemoteLink.cpp RemoteLink.h
  CinemaParty.cpp CinemaParty.h
  CinemaTrackers.cpp
  ${SM_APP_SRC}/party/Party.cpp ${SM_APP_SRC}/party/Party.h
  ${SM_APP_SRC}/AppDir.cpp ${SM_APP_SRC}/AppDir.h
  ${SM_APP_SRC}/SingleInstance.cpp ${SM_APP_SRC}/SingleInstance.h
  ${SM_APP_SRC}/Library.cpp ${SM_APP_SRC}/Library.h
  ${SM_APP_SRC}/AudioAttach.cpp ${SM_APP_SRC}/AudioAttach.h
  ${SM_APP_SRC}/player/Reserve.cpp ${SM_APP_SRC}/player/Reserve.h
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

# Строки интерфейса ПК-версии (Library пишет статусы через tx) — тот же файл, без копии
set(SM_RES ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop-qt/resources)
qt_add_resources(suslik_cinema sm_cinema_res PREFIX /res BASE ${SM_RES} FILES ${SM_RES}/strings/ru.json)

# Адрес сервера — как у ПК-версии: SITE_URL / -DSM_SITE_URL (в репозиторий не пишем); иначе --site при запуске
target_compile_definitions(suslik_cinema PRIVATE SM_VERSION="${SM_CINEMA_VERSION}" SM_SITE_URL="${SM_SITE_URL}")
target_link_libraries(suslik_cinema PRIVATE suslik_core suslik_cinema_geom suslik_cinema_pick Qt6::Gui Qt6::Quick Qt6::OpenGL)
if(WIN32)
  target_link_libraries(suslik_cinema PRIVATE opengl32 ole32 user32 dwmapi)
endif()
if(MSVC)
  target_compile_options(suslik_cinema PRIVATE /utf-8)
  target_compile_options(suslik_cinema_geom PRIVATE /utf-8)
  target_compile_options(suslik_cinema_pick PRIVATE /utf-8)
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
  add_executable(ReleasePickTest tests/ReleasePickTest.cpp)
  target_link_libraries(ReleasePickTest PRIVATE suslik_cinema_pick Qt6::Test)
  if(MSVC)
    target_compile_options(ReleasePickTest PRIVATE /utf-8)
  endif()
  add_test(NAME ReleasePickTest COMMAND ReleasePickTest)
  add_executable(RemoteLinkTest tests/RemoteLinkTest.cpp RemoteLink.cpp RemoteLink.h
    ${SM_APP_SRC}/player/PlayerLog.cpp ${SM_APP_SRC}/AppDir.cpp)
  target_include_directories(RemoteLinkTest PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${SM_APP_SRC} ${SM_APP_SRC}/player)
  target_link_libraries(RemoteLinkTest PRIVATE Qt6::Core Qt6::Test)
  if(MSVC)
    target_compile_options(RemoteLinkTest PRIVATE /utf-8)
  endif()
  add_test(NAME RemoteLinkTest COMMAND RemoteLinkTest)
endif()
