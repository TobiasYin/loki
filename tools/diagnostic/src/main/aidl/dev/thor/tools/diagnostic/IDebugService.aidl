package dev.thor.tools.diagnostic;
import android.os.IBinder;
interface IDebugService {
    int identity() = 1;
    void openLease(String token, long deadlineElapsed, String root, IBinder owner) = 2;
    String execute(String token, String request) = 3;
    void cancel(String token) = 4;
    void destroy() = 16777114;
}
