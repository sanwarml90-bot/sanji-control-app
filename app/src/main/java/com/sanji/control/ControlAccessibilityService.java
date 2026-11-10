package com.sanji.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * AccessibilityService that performs the remote-control actions.
 * All actions are exposed as static helpers the ControlService calls.
 * Every action returns an {@link ActionResult} (ok + info).
 * If the service is not enabled/connected, every action returns
 * ok=false "accessibility off".
 */
public class ControlAccessibilityService extends AccessibilityService {

    private static volatile ControlAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event handling needed; this service only performs actions.
    }

    @Override
    public void onInterrupt() {
        // Nothing to clean up.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }

    /** Result of one control action. */
    public static final class ActionResult {
        public final boolean ok;
        public final String info;

        public ActionResult(boolean ok, String info) {
            this.ok = ok;
            this.info = info;
        }

        public static ActionResult ok(String info) {
            return new ActionResult(true, info);
        }

        public static ActionResult fail(String info) {
            return new ActionResult(false, info);
        }
    }

    /** True when the accessibility service is enabled and connected. */
    public static boolean isEnabled() {
        return instance != null;
    }

    // ---- Action implementations (all static, called by ControlService) ----

    /** Tap at (x, y) in screen pixels via dispatchGesture. */
    public static ActionResult tap(int x, int y) {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        final boolean[] dispatched = {false};
        boolean ok = svc.dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                dispatched[0] = true;
            }
        }, null);
        if (!ok) return ActionResult.fail("gesture dispatch failed");
        return ActionResult.ok("tapped " + x + "," + y);
    }

    /** Swipe from (x1,y1) to (x2,y2) over durationMs milliseconds. */
    public static ActionResult swipe(int x1, int y1, int x2, int y2, long durationMs) {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        boolean ok = svc.dispatchGesture(gesture, null, null);
        if (!ok) return ActionResult.fail("gesture dispatch failed");
        return ActionResult.ok("swiped " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    /**
     * Type text into the currently focused editable node.
     * Falls back to clipboard + ACTION_PASTE when ACTION_SET_TEXT is not
     * supported by the focused node.
     */
    public static ActionResult typeText(String text) {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        AccessibilityNodeInfo focused = svc.getRootInActiveWindow() != null
                ? findFocused(svc.getRootInActiveWindow())
                : null;
        if (focused == null) {
            focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (focused == null) return ActionResult.fail("no focused input");
        if (!focused.isEditable()) return ActionResult.fail("focused node not editable");

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok) return ActionResult.ok("typed " + text.length() + " chars");
        // Fallback: paste via clipboard.
        try {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("sanji-control", text));
                boolean pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                if (pasted) return ActionResult.ok("pasted " + text.length() + " chars");
            }
        } catch (Exception e) {
            return ActionResult.fail("paste failed: " + e.getMessage());
        }
        return ActionResult.fail("type failed");
    }

    /** Press the system Back button. */
    public static ActionResult pressBack() {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        boolean ok = svc.performGlobalAction(GLOBAL_ACTION_BACK);
        return ok ? ActionResult.ok("back pressed") : ActionResult.fail("back failed");
    }

    /** Press the system Home button. */
    public static ActionResult pressHome() {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        boolean ok = svc.performGlobalAction(GLOBAL_ACTION_HOME);
        return ok ? ActionResult.ok("home pressed") : ActionResult.fail("home failed");
    }

    /** Open the Recents screen. */
    public static ActionResult pressRecents() {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        boolean ok = svc.performGlobalAction(GLOBAL_ACTION_RECENTS);
        return ok ? ActionResult.ok("recents opened") : ActionResult.fail("recents failed");
    }

    /** Launch an app by package name. */
    public static ActionResult openApp(String packageName) {
        ControlAccessibilityService svc = instance;
        if (svc == null) return ActionResult.fail("accessibility off");
        try {
            Intent launch = svc.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) return ActionResult.fail("no launch intent for " + packageName);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(launch);
            return ActionResult.ok("opened " + packageName);
        } catch (Exception e) {
            return ActionResult.fail("open failed: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    /** Depth-first search for the focused node under root. */
    private static AccessibilityNodeInfo findFocused(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isFocused()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findFocused(child);
            if (found != null) return found;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static Rect nodeBounds(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        return r;
    }
}
