# WireGuard: нативная библиотека вызывает методы по именам
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.config.** { *; }
-keep class com.wireguard.crypto.** { *; }

# Журнал ошибок: стеки из релизной сборки должны читаться без mapping.txt
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
