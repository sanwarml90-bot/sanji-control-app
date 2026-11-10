package com.sanji.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the WebSocket connection to the relay alive
 * while the app is ON. Receives JSON commands, dispatches them through
 * {@link CommandDispatcher}, and replies with results / screenshots.
 * Reconnects with backoff when the connection drops (only while ON).
 * v10: reconnect backoff caps at 5s (was 30s) so the phone comes back
 * fast after a drop instead of taking half a minute.
 * Tracks bytes sent/received in {@link AppState}.
 *
 * Started by MainActivity with the MediaProjection permission result
 * (extras "capture_result_code" + "capture_data"). Stopping the service
 * closes the socket (zero data when OFF).
 */
public class ControlService extends Service {

    private static final String TAG = "SanjiControlSvc";
    private static final String CHANNEL_ID = "sanji_control";
    private static final int NOTIFICATION_ID = 1;

    /** Extras MainActivity passes when starting the service. */
    public static final String EXTRA_CAPTURE_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_CAPTURE_DATA = "capture_data";

    private static final long RECONNECT_BASE_MS = 2000;
    // v10: max reconnect wait is 5s (was 30s). The old 30s cap made the
    // phone take half a minute to come back after every drop, which is
    // why control felt "bahut slow" — with drops every minute or two,
    // the backoff sat at 30s and every reconnect was slow. 5s is still
    // gentle on the relay and makes reconnection feel instant.
    private static final long RECONNECT_MAX_MS = 5000;

    private final AtomicBoolean wantConnection = new AtomicBoolean(false);
    /**
     * True while a service instance is alive. Set on the first start,
     * cleared in onDestroy. A fresh process starts with false — which is
     * exactly the case where MainActivity must auto-start the service on
     * reopen (see shouldAutoStart). A double start is harmless: the
     * grant manager already handles re-starts.
     */
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);
    /** Pure-Java grant state machine: every start intent is processed, so a
     * fresh MediaProjection grant is never silently dropped (see
     * CaptureGrantManager). Replaces the old `running` flag, which the old
     * onStartCommand used to early-return before reading the intent. */
    private final CaptureGrantManager grantManager = new CaptureGrantManager();
    private volatile WebSocketClient ws;
    private volatile ScreenCapture screenCapture;
    private ScheduledExecutorService reconnectExecutor;
    /** Live-screen stream timing (pure Java, see StreamController). */
    private final StreamController streamController = new StreamController();
    /** Frame timer: ticks every 250 ms, sends a frame only when a controller
     *  is present. Created on first start, shut down in onDestroy. */
    private ScheduledExecutorService streamExecutor;
    private long reconnectDelayMs = RECONNECT_BASE_MS;
    private CommandDispatcher dispatcher;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        dispatcher = new CommandDispatcher(
                new CommandDispatcher.ActionHandler() {
                    @Override
                    public ControlAccessibilityService.ActionResult tap(int x, int y) {
                        return ControlAccessibilityService.tap(x, y);
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult swipe(int x1, int y1, int x2, int y2, long d) {
                        return ControlAccessibilityService.swipe(x1, y1, x2, y2, d);
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult typeText(String text) {
                        return ControlAccessibilityService.typeText(text);
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult pressBack() {
                        return ControlAccessibilityService.pressBack();
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult pressHome() {
                        return ControlAccessibilityService.pressHome();
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult pressRecents() {
                        return ControlAccessibilityService.pressRecents();
                    }

                    @Override
                    public ControlAccessibilityService.ActionResult openApp(String pkg) {
                        return ControlAccessibilityService.openApp(pkg);
                    }
                },
                new CommandDispatcher.ScreenshotHandler() {
                    @Override
                    public String captureScreenshot() {
                        ScreenCapture sc = screenCapture;
                        if (sc == null || !sc.isGranted()) return null;
                        return sc.captureOnce();
                    }

                    @Override
                    public int[] screenSize() {
                        ScreenCapture sc = screenCapture;
                        if (sc == null) return new int[]{0, 0};
                        return new int[]{sc.getRealWidth(), sc.getRealHeight()};
                    }
                },
                new CommandDispatcher.LockHandler() {
                    @Override
                    public CommandDispatcher.LockResult lockNow() {
                        return doLockNow();
                    }
                });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Judge the grant from the intent's SHAPE only (result OK + data
        // non-null) — do NOT call getMediaProjection() yet. On API 34 it
        // throws SecurityException unless the service is already in the
        // foreground with the mediaProjection type (FIX 1: order bug — the
        // old code created the ScreenCapture BEFORE startForeground, so the
        // exception was caught and swallowed and capture stayed null).
        boolean grantOk = false;
        int grantResultCode = 0;
        Intent grantData = null;
        if (intent != null) {
            grantResultCode = intent.getIntExtra(EXTRA_CAPTURE_RESULT_CODE, 0);
            grantData = intent.getParcelableExtra(EXTRA_CAPTURE_DATA);
            grantOk = ScreenCapture.isCaptureResultOk(grantResultCode, grantData);
        }

        // FIX 2: never ignore a fresh intent. The old code early-returned
        // when already running, silently dropping a new grant when the user
        // toggled OFF -> ON quickly (old service not yet destroyed).
        // Every start is processed now.
        CaptureGrantManager.Decision decision = grantManager.onStart(grantOk);

        // Foreground FIRST, with the type matching the grant, BEFORE any
        // getMediaProjection() call (API 34 requirement). On a restart with
        // a fresh grant this switches the type to mediaProjection.
        if (decision.firstStart) {
            startForegroundWithType(grantOk);
            AppState.get().setState(AppState.State.CONNECTING);
        } else if (decision.installCapture) {
            startForegroundWithType(true);
        }

        // Install (or re-install) the capture now that we are foreground
        // with the right type.
        if (decision.installCapture) {
            try {
                ScreenCapture old = screenCapture;
                if (old != null) old.release();
                screenCapture = new ScreenCapture(this, grantResultCode, grantData);
                boolean ok = screenCapture != null && screenCapture.isGranted();
                grantManager.noteCaptureInstalled(ok);
                if (!ok) screenCapture = null;
            } catch (Exception e) {
                Log.w(TAG, "ScreenCapture init failed: " + e.getMessage());
                screenCapture = null;
                grantManager.noteCaptureInstalled(false);
            }
        }

        // Connection setup only once, on the first start.
        if (decision.firstStart) {
            serviceRunning.set(true);
            wantConnection.set(true);
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
            connectAsync();
            startFrameTimer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceRunning.set(false);
        grantManager.reset();
        streamController.reset();
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
            streamExecutor = null;
        }
        wantConnection.set(false);
        disconnect();
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            reconnectExecutor = null;
        }
        if (screenCapture != null) {
            screenCapture.release();
            screenCapture = null;
        }
        AppState.get().setState(AppState.State.OFF);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Called when the app's task is swiped away from recents. Try to come
     * back when the app was ON: first a direct restart, then a broadcast
     * to RestartReceiver as a second chance (the service may be killed
     * right after this returns). On Oppo/ColorOS a swipe can force-stop
     * the app, in which case neither works — reopening the app
     * auto-starts it instead (see MainActivity.onCreate). Everything is
     * wrapped: Android 12+ background-start restrictions may throw a
     * ForegroundServiceStartNotAllowedException, which must never crash
     * the service. When wasOn is false (user turned OFF) nothing happens.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!Prefs.wasOn(this)) return;
        try {
            ControlService.start(this, 0, null);
            Log.i(TAG, "onTaskRemoved: direct restart attempted (was ON)");
        } catch (Exception e) {
            Log.w(TAG, "onTaskRemoved direct restart failed: " + e.getMessage());
        }
        try {
            sendBroadcast(RestartReceiver.restartIntent(this));
        } catch (Exception e) {
            Log.w(TAG, "onTaskRemoved restart broadcast failed: " + e.getMessage());
        }
    }

    // ---- Connection management ----

    private void connectAsync() {
        new Thread(() -> {
            if (!wantConnection.get()) return;
            AppState.get().setState(AppState.State.CONNECTING);
            try {
                WebSocketClient client = new WebSocketClient(Config.RELAY_URL, new WebSocketClient.Listener() {
                    @Override
                    public void onOpen() {
                        reconnectDelayMs = RECONNECT_BASE_MS;
                        AppState.get().setState(AppState.State.ON);
                        sendHello();
                    }

                    @Override
                    public void onMessage(String text) {
                        AppState.get().addReceived(text.getBytes().length);
                        // Every relayed message comes from a controller:
                        // this is what keeps the live-screen stream alive.
                        streamController.onControllerMessage(System.currentTimeMillis());
                        handleCommand(text);
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        AppState.get().setState(AppState.State.CONNECTING);
                        scheduleReconnect();
                    }

                    @Override
                    public void onFailure(Exception e) {
                        Log.w(TAG, "ws failure: " + e.getMessage());
                        AppState.get().setState(AppState.State.CONNECTING);
                        scheduleReconnect();
                    }
                });
                ws = client;
                client.connect();
            } catch (Exception e) {
                Log.w(TAG, "ws connect failed: " + e.getMessage());
                AppState.get().setState(AppState.State.CONNECTING);
                scheduleReconnect();
            }
        }, "sanji-ws-connect").start();
    }

    private void scheduleReconnect() {
        if (!wantConnection.get()) return;
        ScheduledExecutorService exec = reconnectExecutor;
        if (exec == null || exec.isShutdown()) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
        try {
            exec.schedule(this::connectAsync, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private void disconnect() {
        WebSocketClient client = ws;
        ws = null;
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- Live screen stream ----

    /**
     * Start the frame timer. It ticks every 250 ms but sends nothing unless
     * a controller is present (see StreamController) and capture is
     * granted — so when nobody is watching, the stream costs zero data.
     * Stopped in onDestroy (app OFF = zero data).
     */
    private void startFrameTimer() {
        if (streamExecutor != null && !streamExecutor.isShutdown()) return;
        streamExecutor = Executors.newSingleThreadScheduledExecutor();
        streamExecutor.scheduleWithFixedDelay(() -> {
            try {
                maybeSendFrame();
            } catch (Exception ignored) {
            }
        }, 0, StreamController.FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Send one live frame when the stream is active, else do nothing. */
    private void maybeSendFrame() {
        long now = System.currentTimeMillis();
        if (!streamController.shouldSendFrame(now)) return;
        if (ws == null) return;
        ScreenCapture sc = screenCapture;
        if (sc == null || !sc.isGranted()) return;
        String data = sc.captureOnce();
        if (data == null) return;
        try {
            JSONObject frame = new JSONObject();
            frame.put("type", "screenshot");
            frame.put("data", data);
            // Real screen size so the panel can scale tap coordinates
            // from image space to real screen pixels (captureOnce has
            // run ensureSetup, so the fields are set when data != null).
            int sw = sc.getRealWidth();
            int sh = sc.getRealHeight();
            if (sw > 0 && sh > 0) {
                frame.put("sw", sw);
                frame.put("sh", sh);
            }
            sendJson(frame);
            streamController.noteFrameSent(now);
        } catch (Exception ignored) {
        }
    }

    // ---- Remote lock (Device Admin) ----

    /**
     * Lock the phone now. Only works when the user enabled Device Admin in
     * the app ("Enable Device Admin"). Nothing destructive: no wipe, no
     * password reset — lockNow() only.
     */
    private CommandDispatcher.LockResult doLockNow() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return new CommandDispatcher.LockResult(false, "device policy unavailable");
        }
        ComponentName admin = new ComponentName(this, ControlDeviceAdmin.class);
        if (!dpm.isAdminActive(admin)) {
            return new CommandDispatcher.LockResult(false, "device admin not enabled");
        }
        try {
            dpm.lockNow();
            return new CommandDispatcher.LockResult(true, "device locked");
        } catch (Exception e) {
            return new CommandDispatcher.LockResult(false, "lock failed: " + e.getMessage());
        }
    }

    // ---- Protocol ----

    private void sendHello() {
        try {
            // The permanent per-phone UID lets the control panel pick this
            // phone when the app is installed on many phones. The device
            // name is the real brand + model (e.g. "Oppo CPH2349") so the
            // panel's master-mode list shows which phone is which.
            sendJson(DeviceId.buildHelloJson(
                    DeviceId.deviceDisplayName(), DeviceId.getUid(this)));
        } catch (Exception ignored) {
        }
    }

    private void handleCommand(String rawJson) {
        if (dispatcher == null) return;
        CommandDispatcher.DispatchResult res = dispatcher.dispatch(rawJson);
        if (res.isScreenshot) {
            try {
                JSONObject cmd = new JSONObject(rawJson);
                String id = cmd.optString("id", "");
                JSONObject reply = dispatcher.screenshotReply(id);
                sendJson(reply);
            } catch (Exception e) {
                sendResult("", false, "bad json: " + e.getMessage());
            }
            return;
        }
        if (res.reply != null) sendJson(res.reply);
    }

    private void sendResult(String id, boolean ok, String info) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("type", "result");
            reply.put("id", id);
            reply.put("ok", ok);
            reply.put("info", info);
            sendJson(reply);
        } catch (Exception ignored) {
        }
    }

    private void sendJson(JSONObject json) {
        WebSocketClient client = ws;
        if (client == null) return;
        String text = json.toString();
        try {
            client.send(text);
            AppState.get().addSent(text.getBytes().length);
        } catch (Exception e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    // ---- Notification ----

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sanji Control",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Remote control is active");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /**
     * Start in the foreground with the type that matches the capture grant.
     * On API 29+ the 3-arg startForeground lets us pick the type at runtime;
     * on older APIs the manifest type is used.
     */
    private void startForegroundWithType(boolean captureGranted) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = captureGranted
                    ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Sanji Control ON")
                .setContentText("Remote control active · UID "
                        + DeviceId.shortUid(DeviceId.getUid(this)))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    /** True while a service instance is alive (fresh process: false). */
    public static boolean isRunning() {
        return serviceRunning.get();
    }

    /**
     * Pure decision (testable on the JVM): MainActivity auto-starts the
     * service on reopen only when the app was ON and the service is not
     * already running. When wasOn is false (user turned OFF) the app
     * stays OFF and uses zero data.
     */
    public static boolean shouldAutoStart(boolean wasOn, boolean serviceRunning) {
        return wasOn && !serviceRunning;
    }

    /** Start the service (called by MainActivity when turning ON). */
    public static void start(Context context, int captureResultCode, Intent captureData) {
        Intent intent = new Intent(context, ControlService.class);
        if (captureData != null) {
            intent.putExtra(EXTRA_CAPTURE_RESULT_CODE, captureResultCode);
            intent.putExtra(EXTRA_CAPTURE_DATA, captureData);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Stop the service (called by MainActivity when turning OFF). */
    public static void stop(Context context) {
        context.stopService(new Intent(context, ControlService.class));
    }
}
