-keep class com.termux.terminal.** { *; }

# Release 包里彻底移除 TermLou 的 Logcat 输出；debug 包不 shrink，仍保留。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}
