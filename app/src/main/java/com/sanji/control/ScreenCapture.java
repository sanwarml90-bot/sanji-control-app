package com.sanji.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * On-demand screen capture using MediaProjection.
 * A frame is captured ONLY when the controller sends a "screenshot" command
 * (never a stream — saves data). The frame is downscaled to max width 540
 * and compressed to JPEG quality 55.
 *
 * Usage: create with the MediaProjectionManager result from MainActivity
 * (createScreenCaptureIntent result). Call captureOnce() per screenshot
 * command. Call release() when the service stops.
 */
public class ScreenCapture {

    private static final int MAX_WIDTH = 540;
    private static final int JPEG_QUALITY = 50;

    private final Context context;
    private final MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    /** Real display size in pixels (from getRealMetrics, BEFORE the
     * 540px downscale). Sent with every screenshot as "sw"/"sh" so the
     * web panel can map tap coordinates from image space back to real
     * screen pixels. 0 until ensureSetup() has run. */
    private int realWidth;
    private int realHeight;

    /** Create from the MainActivity's screen-capture permission result. */
    public ScreenCapture(Context context, int resultCode, Intent data) {
        this.context = context.getApplicationContext();
        this.projectionManager =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        this.projection = projectionManager.getMediaProjection(resultCode, data);
        // v7: EAGER VirtualDisplay setup. The display used to be created
        // lazily on the first screenshot — which failed when the app was
        // fully in the background (fullscreen browser, no split-screen),
        // because createVirtualDisplay does not work reliably from the
        // background. In split-screen the app is visible, so the first
        // screenshot worked and later ones reused the display. Creating it
        // NOW (app is in the foreground, user just tapped "Start now")
        // makes background screenshots reuse the ready display. A failure
        // here is non-fatal: captureOnce() still retries lazily.
        try {
            ensureSetup();
        } catch (Exception ignored) {
        }
    }

    /** True when MediaProjection was granted and a projection exists. */
    public boolean isGranted() {
        return projection != null;
    }

    /** Real screen width in pixels (0 until the first capture runs). */
    public synchronized int getRealWidth() {
        return realWidth;
    }

    /** Real screen height in pixels (0 until the first capture runs). */
    public synchronized int getRealHeight() {
        return realHeight;
    }

    /**
     * Capture one frame and return it as a base64 JPEG string.
     * Returns null on any failure (caller replies ok=false).
     * Synchronized: the live-stream timer thread and the command thread
     * can both capture, and ensureSetup() must not run twice.
     */
    public synchronized String captureOnce() {
        if (projection == null) return null;
        try {
            ensureSetup();
            if (imageReader == null) return null;
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                // No frame ready yet; wait briefly and retry once.
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
                image = imageReader.acquireLatestImage();
            }
            if (image == null) return null;
            try {
                return imageToJpegBase64(image);
            } finally {
                image.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Release the VirtualDisplay, ImageReader and MediaProjection. */
    public synchronized void release() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {
        }
        virtualDisplay = null;
        try {
            if (imageReader != null) imageReader.close();
        } catch (Exception ignored) {
        }
        imageReader = null;
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        projection = null;
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
    }

    // ---- Internals ----

    private void ensureSetup() {
        if (virtualDisplay != null && imageReader != null) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;
        if (width <= 0 || height <= 0) return;
        // Remember the REAL size (before downscaling) for the "sw"/"sh"
        // fields the panel needs to map taps back to screen pixels.
        realWidth = width;
        realHeight = height;
        // Downscale to max width 540, keep aspect ratio.
        if (width > MAX_WIDTH) {
            double scale = (double) MAX_WIDTH / width;
            width = MAX_WIDTH;
            height = (int) Math.round(height * scale);
        }
        // v7: build into LOCALS first. The old code assigned the thread
        // and ImageReader to fields BEFORE createVirtualDisplay ran — so a
        // failure there leaked a started thread + an open ImageReader on
        // every retry, and the next attempt piled on more. Now a failure
        // cleans up and leaves the fields untouched, so the next
        // ensureSetup() retries from a clean state.
        HandlerThread thread = new HandlerThread("sanji-capture");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        ImageReader reader = null;
        VirtualDisplay display = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay(
                    "sanji-control",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception e) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            thread.quitSafely();
            return;
        }
        captureThread = thread;
        captureHandler = handler;
        imageReader = reader;
        virtualDisplay = display;
    }

    private String imageToJpegBase64(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != bitmap) bitmap.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        cropped.recycle();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Exposed for tests: the JPEG quality used. */
    public static int jpegQuality() {
        return JPEG_QUALITY;
    }

    /** Exposed for tests: the max capture width. */
    public static int maxWidth() {
        return MAX_WIDTH;
    }

    /** Helper for MainActivity: build the screen-capture permission intent. */
    public static Intent captureIntent(Context context) {
        MediaProjectionManager mgr =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mgr != null ? mgr.createScreenCaptureIntent() : null;
    }

    /** True when the given Activity result is a granted screen-capture permission. */
    public static boolean isCaptureResultOk(int resultCode, Intent data) {
        return resultCode == Activity.RESULT_OK && data != null;
    }
}
