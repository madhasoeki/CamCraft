# CamCraft R8 / ProGuard Rules

# Preserve annotations and metadata required by kotlinx.serialization & CameraX
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# CamCraft CCFT v1 wire protocol, JSON control channel, & GitHub release updater models
-keep class org.artcraft.camcraft.protocol.** { *; }
-keepclassmembers class org.artcraft.camcraft.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class org.artcraft.camcraft.protocol.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class org.artcraft.camcraft.protocol.** {
    static final *;
}
-keep class org.artcraft.camcraft.update.** { *; }
-keepclassmembers class org.artcraft.camcraft.update.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class org.artcraft.camcraft.update.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class org.artcraft.camcraft.update.** {
    static final *;
}

# kotlinx.serialization runtime safety for sealed discriminators and serializer lookup
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# CameraX & Camera2Interop reflection safety
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class * implements androidx.camera.core.CameraXConfig$Provider { *; }
-keep class androidx.camera.camera2.interop.** { *; }
-keepclassmembers class android.hardware.camera2.CaptureRequest$Key { *; }
-keepclassmembers class android.hardware.camera2.CaptureResult$Key { *; }
