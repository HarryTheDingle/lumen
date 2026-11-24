# The phone apps: dx.bi for phones (the mobile flavor) and Lumen for phones (lumenphone)

# Casting: Google's framework finds its options by this class's name (src/phonecommon/AndroidManifest.xml)
-keep class com.notdxbi.dxbi.tv.CastSetup { *; }

# Live TV (lumenphone): the IPTV models use kotlinx.serialization sealed classes, and the engine's models are read by name
-keep class lumos.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class lumos.**$$serializer { *; }
-keepclassmembers class lumos.** { *** Companion; }
# FFmpeg audio decoder (Media3 builds it by reflection)
-keep class androidx.media3.decoder.ffmpeg.** { *; }
