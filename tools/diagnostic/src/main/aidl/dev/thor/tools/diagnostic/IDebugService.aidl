package dev.thor.tools.diagnostic;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
interface IDebugService {
    int identity() = 1;
    void openLease(String token, long deadlineElapsed, String root, IBinder owner) = 2;
    String execute(String token, String request) = 3;
    void cancel(String token) = 4;
    String startShell(String token, String command, int timeoutMs, int maxBytes, String approvalHash) = 5;
    String awaitShell(String token) = 6;
    String thorBrightness(String token, String action, String screen, int value) = 7;
    String stageApk(String token, in ParcelFileDescriptor source, long size, String sha256) = 8;
    String startInstall(String token, String sha256, String approvalHash) = 9;
    String gamepadStatus() = 10;
    String startGamepad(String token, String request) = 11;
    String pollGamepad(String token) = 12;
    String releaseGamepad(String runId) = 13;
    String prepareGamepad(String token, String request) = 14;
    String presentationEvidence(int displayId, in int[] windowIds) = 15;
    void destroy() = 16777114;
}
