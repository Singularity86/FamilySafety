# R8 rules for FamilySafety.
#
# Stage 1: shrink and optimise the libraries, keep our own code whole. A missing rule doesn't
# fail the build; it fails at run time, in release only, so every change here is checked on
# the releaseCheck build (see app/build.gradle.kts) before it ships. Keep each release's
# mapping.txt with its AAB and upload it to Play, or crash reports can't be read.

# Our own code, unchanged for now. Stage 2 removes this once stage 1 has shipped cleanly.
-keep class com.example.familysafety.** { *; }

# Readable crash reports: line numbers stay, mapping.txt maps the rest.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# JNA: its native half calls back into these classes by name and reads Structure fields
# reflectively. Losing any of it breaks all encryption at run time.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# Lazysodium: JNA binds libsodium's functions to these interfaces by method name.
-keep class com.goterl.lazysodium.** { *; }

# SQLCipher: its JNI code looks up these classes and members by name.
-keep class net.zetetic.database.** { *; }

# Paho MQTT: network modules are loaded by class name, and messages come from a resource bundle.
-keep class org.eclipse.paho.client.mqttv3.** { *; }

# Bouncy Castle: the provider registers every algorithm by class name at run time, so R8
# can't see them in use. Without this, PBKDF2WithHmacSHA512 is missing and a recovery phrase
# can't become keys: nobody can join, create or restore a family (Bip39).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
