package com.sanji.control;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared singleton holding the current connection state and the number of
 * bytes sent/received this session. The ControlService updates it; the
 * MainActivity observes it via listeners and refreshes the status line.
 * All listener callbacks are posted to the main thread.
 */
public final class AppState {

    /** Connection state shown in the UI. */
    public enum State { OFF, CONNECTING, ON }

    /** Listener notified whenever the state or the byte counters change. */
    public interface Listener {
        void onChanged();
    }

    private static final AppState INSTANCE = new AppState();

    public static AppState get() {
        return INSTANCE;
    }

    private volatile State state = State.OFF;
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Main-thread handler, created lazily. Null when there is no Looper
     *  (e.g. a JVM unit test) — listeners then run inline. */
    private volatile Handler mainHandler;

    private AppState() {}

    public State getState() {
        return state;
    }

    public void setState(State newState) {
        state = newState;
        notifyListeners();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public void addSent(long n) {
        bytesSent.addAndGet(n);
        notifyListeners();
    }

    public void addReceived(long n) {
        bytesReceived.addAndGet(n);
        notifyListeners();
    }

    /** Reset the byte counters (called when the service stops). */
    public void resetCounters() {
        bytesSent.set(0);
        bytesReceived.set(0);
        notifyListeners();
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        Handler handler = getMainHandler();
        if (handler != null) {
            handler.post(() -> {
                for (Listener listener : listeners) {
                    listener.onChanged();
                }
            });
        } else {
            // No Looper (JVM test): run listeners inline.
            for (Listener listener : listeners) {
                listener.onChanged();
            }
        }
    }

    private Handler getMainHandler() {
        Handler handler = mainHandler;
        if (handler != null) return handler;
        try {
            Looper looper = Looper.getMainLooper();
            if (looper == null) return null;
            handler = new Handler(looper);
            mainHandler = handler;
            return handler;
        } catch (Exception e) {
            return null;
        }
    }

    /** Human-readable byte count, e.g. "0 B", "12.4 KB", "1.2 MB". */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
