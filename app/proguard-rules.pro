# ConnectX ships without service-role secrets. Keep device JWTs out of logs.
-keep class com.ems.connectx.** { *; }
