# dadb uses okio and plain JVM crypto; keep its public API intact.
-keep class dadb.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
