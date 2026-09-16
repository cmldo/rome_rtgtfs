# protobuf-java (full) usa la reflection sui messaggi generati per toString/equals.
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-dontwarn com.google.protobuf.**
