# NavBlind ProGuard Rules

# Keep Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keep class com.smartwalker.data.remote.** { *; }
-keep class com.smartwalker.domain.model.** { *; }

# Keep ARCore
-keep class com.google.ar.** { *; }

# Keep Kakao Maps SDK
-keep class com.kakao.vectormap.** { *; }
-keep interface com.kakao.vectormap.** { *; }
