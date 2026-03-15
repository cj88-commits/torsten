# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Gson – keep DTO field names used via @SerializedName
-keepclassmembers class com.recordcollection.app.data.api.dto.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**
