# Только обфускация имён в коде приложения. Без сжатия и оптимизации: так безопаснее для Compose, JNA и Skiko.
-dontshrink
-dontoptimize
-dontwarn **

# Чужие библиотеки не трогаем (их имена нужны отражению и нативным вызовам).
-keep class !ru.suslikovanora.media.desktop.** { *; }
-keep class kotlin.Metadata { *; }

# Точка входа, перечисления, JNA-структуры и интерфейсы библиотек в нашем коде.
-keep class ru.suslikovanora.media.desktop.MainKt { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers enum ru.suslikovanora.media.desktop.** { *; }

# Для разбора hs_err и журналов оставляем номера строк, имена файлов прячем.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
