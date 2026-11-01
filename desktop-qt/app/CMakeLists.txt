# Приложение: интерфейс на QML поверх ядра (core/). План — docs/QT-PORT.md.
find_package(Qt6 6.4 REQUIRED COMPONENTS Gui Quick OpenGL ShaderTools)

qt_add_executable(suslik_media WIN32
  main.cpp
  src/AppDir.cpp src/AppDir.h
  src/SingleInstance.cpp src/SingleInstance.h
  src/TitleBar.cpp src/TitleBar.h
  src/Library.cpp src/Library.h
  src/LibraryPage.cpp src/LibraryPage.h
  src/ImageProvider.cpp src/ImageProvider.h
  src/IconProvider.cpp src/IconProvider.h
  src/Ui.cpp src/Ui.h
  src/PlayModel.cpp src/PlayModel.h src/PlayRequest.h
  src/AddModel.cpp src/AddModel.h
  src/AudioAttach.cpp src/AudioAttach.h
  src/Settings.cpp src/Settings.h
  src/Updater.cpp src/Updater.h
  src/player/Mpv.cpp src/player/Mpv.h
  src/player/MpvEngine.cpp src/player/MpvEngine.h
  src/player/MpvVideo.cpp src/player/MpvVideo.h
  src/player/MpvInstall.cpp src/player/MpvInstall.h
  src/player/PlayerLog.cpp src/player/PlayerLog.h
  src/player/PlayerModel.cpp src/player/PlayerModel.h
  src/player/Reserve.cpp src/player/Reserve.h
  src/player/WindowMode.cpp src/player/WindowMode.h
  src/player/DisplayAwake.cpp src/player/DisplayAwake.h
  src/player/DiscordRpc.cpp src/player/DiscordRpc.h
  src/player/MediaControls.cpp src/player/MediaControls.h
  src/player/MediaSession.cpp src/player/MediaSession.h
)
# Заголовки libmpv (ISC): сама библиотека грузится при запуске фильма.
target_include_directories(suslik_media PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party)
set_target_properties(suslik_media PROPERTIES OUTPUT_NAME "SuslikMediaQt")

file(GLOB SM_QML RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} CONFIGURE_DEPENDS qml/*.qml)
# Файлы лежат в qml/, но в модуле — в корне: иначе неявный импорт папки прячет синглтон Theme.
foreach(f IN LISTS SM_QML)
  get_filename_component(n ${f} NAME)
  set_source_files_properties(${f} PROPERTIES QT_RESOURCE_ALIAS ${n})
endforeach()
set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
qt_add_qml_module(suslik_media
  URI Suslik
  VERSION 1.0
  # В ресурсах — там, где движок QML ищет модули по умолчанию (qrc:/qt/qml): loadFromModule находит его без путей.
  RESOURCE_PREFIX /qt/qml
  QML_FILES ${SM_QML}
)
# Скруглённые углы слоёв (постеры, баннер): маленький шейдер, собирается в .qsb для всех графических API.
qt_add_shaders(suslik_media sm_shaders
  PREFIX /sm
  FILES shaders/rounded.frag shaders/roundedimage.frag
)

# Шрифты, значки и строки — те же файлы, что у Kotlin-версии (desktop/src/main/resources), без копий.
set(SM_RES ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop/src/main/resources)
qt_add_resources(suslik_media sm_shared_res
  PREFIX /res
  BASE ${SM_RES}
  FILES
    ${SM_RES}/strings/ru.json
    ${SM_RES}/changelog.json
    ${SM_RES}/libmpv.properties
    ${SM_RES}/fonts/onest_regular.ttf
    ${SM_RES}/fonts/onest_medium.ttf
    ${SM_RES}/fonts/onest_semibold.ttf
    ${SM_RES}/fonts/unbounded_medium.ttf
    ${SM_RES}/fonts/unbounded_bold.ttf
    ${SM_RES}/icons/brand-mark.png
    ${SM_RES}/icons/icon-16.png
    ${SM_RES}/icons/icon-24.png
    ${SM_RES}/icons/icon-32.png
    ${SM_RES}/icons/icon-48.png
    ${SM_RES}/icons/icon-64.png
    ${SM_RES}/icons/icon-128.png
    ${SM_RES}/icons/icon-256.png
)

option(SM_DIAG "Отладочная сборка (своя папка данных, подробный журнал)" OFF)
target_compile_definitions(suslik_media PRIVATE
  SM_VERSION="${PROJECT_VERSION}"
  SM_SITE_URL="${SM_SITE_URL}"
  $<$<BOOL:${SM_DIAG}>:SM_DIAG>
)
target_link_libraries(suslik_media PRIVATE suslik_core Qt6::Gui Qt6::Quick Qt6::OpenGL)
if(WIN32)
  # Значок и версия в самом .exe; путь к значку — с прямыми слешами (rc.exe понимает)
  set(SM_ICON "${CMAKE_CURRENT_SOURCE_DIR}/../../desktop/icon.ico")
  if(SM_DIAG)
    set(SM_PRODUCT "Suslik Debug Qt")
  else()
    set(SM_PRODUCT "Suslik Media Qt")
  endif()
  configure_file(win/app.rc.in ${CMAKE_CURRENT_BINARY_DIR}/app.rc @ONLY)
  target_sources(suslik_media PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/app.rc)
  # windowsapp — системные медиа-кнопки (C++/WinRT)
  target_link_libraries(suslik_media PRIVATE dwmapi msi windowsapp)
endif()
if(MSVC)
  target_compile_options(suslik_media PRIVATE /utf-8)
  set_source_files_properties(src/player/MediaControls.cpp PROPERTIES COMPILE_OPTIONS "/bigobj")
endif()
