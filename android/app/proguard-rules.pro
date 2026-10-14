# Release builds are not minified (isMinifyEnabled = false).
# If you enable R8, keep the kotlinx.serialization DTOs:
-keep,includedescriptorclasses class edu.cse499a.rocobangla.data.**$$serializer { *; }
-keepclassmembers class edu.cse499a.rocobangla.data.** { *** Companion; }
