# Приложение: интерфейс на QML поверх ядра (core/). План — docs/QT-PORT.md.
find_package(Qt6 6.4 REQUIRED COMPONENTS Gui Quick ShaderTools)

qt_add_executable(suslik_media WIN32
  main.cpp
  src/AppDir.cpp src/AppDir.h
  src/SingleInstance.cpp src/SingleInstance.h
  src/TitleBar.cpp src/TitleBar.h
  src/Library.cpp src/Library.h
  src/LibraryPage.cpp src/LibraryPage.h
  src/ImageProvider.cpp src/ImageProvider.h
  src/Ui.cpp src/Ui.h
  src/PlayModel.cpp src/PlayModel.h src/PlayRequest.h
  src/AddModel.cpp src/AddModel.h
  src/AudioAttach.cpp src/AudioAttach.h
  src/Settings.cpp src/Settings.h
  src/Updater.cpp src/Updater.h
)
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
  FILES shaders/rounded.frag
)

# Шрифты, значки и строки — те же файлы, что у Kotlin-версии (desktop/src/main/resources), без копий.
set(SM_RES ${CMAKE_CURRENT_SOURCE_DIR}/../../desktop/src/main/resources)
qt_add_resources(suslik_media sm_shared_res
  PREFIX /res
  BASE ${SM_RES}
  FILES
    ${SM_RES}/strings/ru.json
    ${SM_RES}/changelog.json
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
target_link_libraries(suslik_media PRIVATE suslik_core Qt6::Gui Qt6::Quick)
if(WIN32)
  target_link_libraries(suslik_media PRIVATE dwmapi msi)
endif()
if(MSVC)
  target_compile_options(suslik_media PRIVATE /utf-8)
endif()
