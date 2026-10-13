package android.util;

/** 纯 JVM 测试中的 Android 日志宿主替身；仅忽略日志输出，不处理业务异常。 */
public final class Log {
    private Log() {}

    public static boolean isLoggable(String tag, int priority) {
        return false;
    }

    public static int i(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }
}
