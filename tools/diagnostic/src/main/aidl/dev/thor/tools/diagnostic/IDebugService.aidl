package dev.thor.tools.diagnostic;
import android.os.IBinder;
interface IDebugService {
    int identity() = 1;
    void openLease(String token, long deadlineElapsed, String root, IBinder owner) = 2;
    String execute(String token, String request) = 3;
    void cancel(String token) = 4;
    String startShell(String token, String command, int timeoutMs, int maxBytes, String approvalHash) = 5;
    String awaitShell(String token) = 6;
    String thorBrightness(String token, String action, String screen, int value) = 7;
    void destroy() = 16777114;
}
