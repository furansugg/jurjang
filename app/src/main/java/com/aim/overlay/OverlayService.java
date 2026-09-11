package com.aim.overlay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;

public class OverlayService extends Service {
    private WindowManager wm;
    private LineOverlayView lineView;
    private BallHandleView cueHandle;
    private BallHandleView targetHandle;
    private View tlHandle;
    private View brHandle;
    private LinearLayout menuView;
    private Button resetTableBtn;
    private Button zoomBtn;
    private Button nudgeTargetBtn;

    private WindowManager.LayoutParams cueParams;
    private WindowManager.LayoutParams targetParams;
    private WindowManager.LayoutParams tlParams;
    private WindowManager.LayoutParams brParams;
    private WindowManager.LayoutParams menuParams;

    private final RectF tableBounds = new RectF();
    private boolean isCalibrating = false;
    private SharedPreferences prefs;

    private int bounces = 1;
    private int handleSize = 100;
    private final int CORNER_HANDLE_SIZE = 100;
    private Button sizeLabelBtn;
    private boolean nudgeAim = true; // true = AIM handle, false = CUE handle

    private int screenW;
    private int screenH;
    private int screenDpi;

    // Zoom level: 1 = 2x, 2 = 3x, 0 = OFF
    private int zoomLevel = 2;
    private int cropSize = 80;
    private final float LOUPE_RADIUS = 120f;

    // Magnifier state
    private boolean isDragging = false;
    private float dragX = 0f;
    private float dragY = 0f;

    // MediaProjection screen capture
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;

    // Raw full-frame buffer cache
    private final Object screenBufferLock = new Object();
    private ByteBuffer screenRawBuffer;
    private int lastRowStride = 0;
    private int lastPixelStride = 4;
    private boolean hasScreenFrame = false;

    // Fast reusable crop buffers
    private final Object cropLock = new Object();
    private Bitmap cropBitmap;
    private ByteBuffer cropByteBuffer;
    private final byte[] pixelBuffer = new byte[4];

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences("8bp_aim_prefs", MODE_PRIVATE);

        updateScreenMetrics();

        handleSize = prefs.getInt("handle_size", 100);
        zoomLevel = prefs.getInt("zoom_level", 2);
        updateCropDimensions();

        recalculateTableBoundsFromRatios();

        // 1. Pass-through line drawing overlay with true physical screen layout
        lineView = new LineOverlayView(this);
        WindowManager.LayoutParams lineParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lineParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(lineView, lineParams);

        // 2. Cue Ball Handle
        cueParams = createHandleParams(screenW / 3, screenH / 2, handleSize);
        cueHandle = new BallHandleView(this, Color.WHITE, "CUE");
        attachDragListener(cueHandle, cueParams, null, true);
        wm.addView(cueHandle, cueParams);

        // 3. Target Ball Handle
        targetParams = createHandleParams((screenW / 3) * 2, screenH / 2, handleSize);
        targetHandle = new BallHandleView(this, Color.RED, "AIM");
        attachDragListener(targetHandle, targetParams, null, true);
        wm.addView(targetHandle, targetParams);

        // 4. Table Corner Handles
        tlParams = createHandleParams((int) (tableBounds.left - CORNER_HANDLE_SIZE / 2f), (int) (tableBounds.top - CORNER_HANDLE_SIZE / 2f), CORNER_HANDLE_SIZE);
        tlHandle = new BallHandleView(this, Color.GREEN, "TL");
        tlHandle.setVisibility(View.GONE);
        attachDragListener(tlHandle, tlParams, () -> {
            tableBounds.left = Math.max(10f, Math.min(tableBounds.right - 120f, tlParams.x + CORNER_HANDLE_SIZE / 2f));
            tableBounds.top = Math.max(10f, Math.min(tableBounds.bottom - 120f, tlParams.y + CORNER_HANDLE_SIZE / 2f));
        }, false);
        wm.addView(tlHandle, tlParams);

        brParams = createHandleParams((int) (tableBounds.right - CORNER_HANDLE_SIZE / 2f), (int) (tableBounds.bottom - CORNER_HANDLE_SIZE / 2f), CORNER_HANDLE_SIZE);
        brHandle = new BallHandleView(this, Color.GREEN, "BR");
        brHandle.setVisibility(View.GONE);
        attachDragListener(brHandle, brParams, () -> {
            tableBounds.right = Math.min(screenW - 10f, Math.max(tableBounds.left + 120f, brParams.x + CORNER_HANDLE_SIZE / 2f));
            tableBounds.bottom = Math.min(screenH - 10f, Math.max(tableBounds.top + 120f, brParams.y + CORNER_HANDLE_SIZE / 2f));
        }, false);
        wm.addView(brHandle, brParams);

        // 5. Control Panel
        createMenuView();

        // 6. Background thread for screen capture
        imageThread = new HandlerThread("ScreenCaptureThread");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
    }

    private void updateScreenMetrics() {
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        screenW = Math.max(size.x, size.y);
        screenH = Math.min(size.x, size.y);
        screenDpi = getResources().getDisplayMetrics().densityDpi;
    }

    private void updateCropDimensions() {
        if (zoomLevel == 1) cropSize = 120; // 2x
        else if (zoomLevel == 2) cropSize = 80;  // 3x
        else cropSize = 80;

        synchronized (cropLock) {
            cropBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888);
            cropByteBuffer = ByteBuffer.allocateDirect(cropSize * cropSize * 4);
        }
    }

    private void recalculateTableBoundsFromRatios() {
        float rL = prefs.getFloat("r_left", 0.115f);
        float rT = prefs.getFloat("r_top", 0.165f);
        float rR = prefs.getFloat("r_right", 0.885f);
        float rB = prefs.getFloat("r_bottom", 0.835f);

        if (rL < 0f || rL > 0.4f) rL = 0.115f;
        if (rT < 0f || rT > 0.4f) rT = 0.165f;
        if (rR > 1f || rR < 0.6f) rR = 0.885f;
        if (rB > 1f || rB < 0.6f) rB = 0.835f;

        tableBounds.left = screenW * rL;
        tableBounds.top = screenH * rT;
        tableBounds.right = screenW * rR;
        tableBounds.bottom = screenH * rB;

        syncCornerHandlePositions();
        if (lineView != null) lineView.invalidate();
    }

    private void syncCornerHandlePositions() {
        if (tlParams != null && brParams != null) {
            tlParams.x = (int) (tableBounds.left - CORNER_HANDLE_SIZE / 2f);
            tlParams.y = (int) (tableBounds.top - CORNER_HANDLE_SIZE / 2f);
            brParams.x = (int) (tableBounds.right - CORNER_HANDLE_SIZE / 2f);
            brParams.y = (int) (tableBounds.bottom - CORNER_HANDLE_SIZE / 2f);
            if (tlHandle != null) wm.updateViewLayout(tlHandle, tlParams);
            if (brHandle != null) wm.updateViewLayout(brHandle, brParams);
        }
    }

    private void resetTableBoundsToDefault() {
        prefs.edit()
                .putFloat("r_left", 0.115f)
                .putFloat("r_top", 0.165f)
                .putFloat("r_right", 0.885f)
                .putFloat("r_bottom", 0.835f)
                .apply();
        recalculateTableBoundsFromRatios();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int code = intent.getIntExtra("code", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("data");
            if (code == Activity.RESULT_OK && data != null && mediaProjection == null) {
                initMediaProjection(code, data);
            }
        }
        return START_STICKY;
    }

    private void initMediaProjection(int code, Intent data) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return;
            mediaProjection = mpm.getMediaProjection(code, data);
            if (mediaProjection == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {}, new Handler(Looper.getMainLooper()));
            }

            setupVirtualDisplay();
        } catch (Exception ignored) {}
    }

    private void setupVirtualDisplay() {
        if (mediaProjection == null || screenW <= 0 || screenH <= 0) return;

        if (imageReader != null) {
            imageReader.close();
        }

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    synchronized (screenBufferLock) {
                        lastRowStride = plane.getRowStride();
                        lastPixelStride = plane.getPixelStride();
                        if (screenRawBuffer == null || screenRawBuffer.capacity() < buf.capacity()) {
                            screenRawBuffer = ByteBuffer.allocateDirect(buf.capacity());
                        }
                        screenRawBuffer.rewind();
                        buf.rewind();
                        screenRawBuffer.put(buf);
                        hasScreenFrame = true;
                    }

                    if (isDragging && zoomLevel > 0) {
                        extractCropFromBuffer((int) dragX, (int) dragY);
                        if (lineView != null) {
                            lineView.postInvalidate();
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, imageHandler);

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AimLoupe",
                    screenW,
                    screenH,
                    screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );
        } else {
            virtualDisplay.resize(screenW, screenH, screenDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
        }
    }

    // Zero-offset direct extraction from screen buffer; center of crop is 100% exact to (cx, cy)
    private void extractCropFromBuffer(int cx, int cy) {
        synchronized (screenBufferLock) {
            if (!hasScreenFrame || screenRawBuffer == null || lastRowStride == 0) return;
            synchronized (cropLock) {
                if (cropByteBuffer == null || cropBitmap == null) return;
                cropByteBuffer.rewind();

                int startX = cx - cropSize / 2;
                int startY = cy - cropSize / 2;

                for (int r = 0; r < cropSize; r++) {
                    int y = startY + r;
                    if (y >= 0 && y < screenH) {
                        for (int c = 0; c < cropSize; c++) {
                            int x = startX + c;
                            if (x >= 0 && x < screenW) {
                                int offset = y * lastRowStride + x * lastPixelStride;
                                if (offset + 4 <= screenRawBuffer.capacity()) {
                                    screenRawBuffer.position(offset);
                                    screenRawBuffer.get(pixelBuffer, 0, 4);
                                    cropByteBuffer.put(pixelBuffer, 0, 4);
                                    continue;
                                }
                            }
                            // Neutral padding for edge coordinates
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0xFF);
                        }
                    } else {
                        for (int c = 0; c < cropSize; c++) {
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0x1A);
                            cropByteBuffer.put((byte) 0xFF);
                        }
                    }
                }

                cropByteBuffer.rewind();
                cropBitmap.copyPixelsFromBuffer(cropByteBuffer);
            }
        }
    }

    private WindowManager.LayoutParams createHandleParams(int x, int y, int size) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size,
                size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    private void attachDragListener(View view, WindowManager.LayoutParams p, Runnable onDragCallback, boolean isBall) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int curSize = isBall ? handleSize : CORNER_HANDLE_SIZE;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = p.x;
                        initialY = p.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        if (isBall) {
                            isDragging = true;
                            int[] loc = new int[2];
                            v.getLocationOnScreen(loc);
                            dragX = loc[0] + curSize / 2f;
                            dragY = loc[1] + curSize / 2f;
                            if (v instanceof BallHandleView) {
                                ((BallHandleView) v).setDragging(true);
                            }
                            extractCropFromBuffer((int) dragX, (int) dragY);
                            lineView.invalidate();
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        p.x = Math.max(0, Math.min(screenW - curSize, initialX + (int) (event.getRawX() - touchX)));
                        p.y = Math.max(0, Math.min(screenH - curSize, initialY + (int) (event.getRawY() - touchY)));
                        wm.updateViewLayout(v, p);

                        if (isBall) {
                            int[] loc = new int[2];
                            v.getLocationOnScreen(loc);
                            dragX = loc[0] + curSize / 2f;
                            dragY = loc[1] + curSize / 2f;
                            extractCropFromBuffer((int) dragX, (int) dragY);
                        }
                        if (onDragCallback != null) onDragCallback.run();
                        lineView.invalidate();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (isBall) {
                            isDragging = false;
                            if (v instanceof BallHandleView) {
                                ((BallHandleView) v).setDragging(false);
                            }
                            lineView.invalidate();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void nudge(int dx, int dy) {
        WindowManager.LayoutParams p = nudgeAim ? targetParams : cueParams;
        View v = nudgeAim ? targetHandle : cueHandle;
        p.x = Math.max(0, Math.min(screenW - handleSize, p.x + dx));
        p.y = Math.max(0, Math.min(screenH - handleSize, p.y + dy));
        wm.updateViewLayout(v, p);

        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        dragX = loc[0] + handleSize / 2f;
        dragY = loc[1] + handleSize / 2f;
        extractCropFromBuffer((int) dragX, (int) dragY);
        lineView.invalidate();
    }

    private void createMenuView() {
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.HORIZONTAL);
        menuView.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xEE1E2128);
        panelBg.setCornerRadius(30f);
        panelBg.setStroke(2, 0x44FFFFFF);
        menuView.setBackground(panelBg);
        menuView.setPadding(14, 8, 14, 8);

        // 1. Grip / Title
        TextView grip = new TextView(this);
        grip.setText("⠿ 8BP");
        grip.setTextColor(0xFFAAAAAA);
        grip.setTextSize(11f);
        grip.setPadding(4, 0, 10, 0);
        menuView.addView(grip);

        // 2. Cushion Button
        Button cushionBtn = createStyledButton("Cush: 1x", 0xFF00E5FF, 0x2200E5FF);
        cushionBtn.setOnClickListener(v -> {
            bounces = (bounces + 1) % 3;
            cushionBtn.setText(bounces == 0 ? "Cush: OFF" : "Cush: " + bounces + "x");
            lineView.invalidate();
        });
        menuView.addView(cushionBtn);

        // 3. Zoom Button (OFF / 2x / 3x)
        zoomBtn = createStyledButton(getZoomText(), 0xFFFF4081, 0x22FF4081);
        zoomBtn.setOnClickListener(v -> {
            zoomLevel = (zoomLevel + 1) % 3;
            prefs.edit().putInt("zoom_level", zoomLevel).apply();
            zoomBtn.setText(getZoomText());
            updateCropDimensions();
            lineView.invalidate();
        });
        menuView.addView(zoomBtn);

        // 4. Table Button
        Button tableBtn = createStyledButton("Table: LOCK", 0xFF00E676, 0x2200E676);
        tableBtn.setOnClickListener(v -> {
            isCalibrating = !isCalibrating;
            tableBtn.setText(isCalibrating ? "Table: EDIT" : "Table: LOCK");
            if (isCalibrating) {
                syncCornerHandlePositions();
            }
            tlHandle.setVisibility(isCalibrating ? View.VISIBLE : View.GONE);
            brHandle.setVisibility(isCalibrating ? View.VISIBLE : View.GONE);
            if (resetTableBtn != null) {
                resetTableBtn.setVisibility(isCalibrating ? View.VISIBLE : View.GONE);
            }
            if (!isCalibrating) {
                prefs.edit()
                        .putFloat("r_left", tableBounds.left / screenW)
                        .putFloat("r_top", tableBounds.top / screenH)
                        .putFloat("r_right", tableBounds.right / screenW)
                        .putFloat("r_bottom", tableBounds.bottom / screenH)
                        .apply();
            }
            lineView.invalidate();
        });
        menuView.addView(tableBtn);

        resetTableBtn = createStyledButton("Reset", 0xFFFF9100, 0x22FF9100);
        resetTableBtn.setVisibility(View.GONE);
        resetTableBtn.setOnClickListener(v -> resetTableBoundsToDefault());
        menuView.addView(resetTableBtn);

        // 5. Circle Size Controls ([-] Size [+]
        Button sizeMinus = createStyledButton("–", Color.WHITE, 0x22FFFFFF);
        sizeMinus.setOnClickListener(v -> updateHandleSize(handleSize - 10));
        menuView.addView(sizeMinus);

        sizeLabelBtn = createStyledButton("Size: " + handleSize, 0xFFFFD600, 0x22FFD600);
        sizeLabelBtn.setOnClickListener(v -> updateHandleSize(handleSize >= 160 ? 60 : handleSize + 20));
        menuView.addView(sizeLabelBtn);

        Button sizePlus = createStyledButton("+", Color.WHITE, 0x22FFFFFF);
        sizePlus.setOnClickListener(v -> updateHandleSize(handleSize + 10));
        menuView.addView(sizePlus);

        // 6. 1-Pixel Micro-Adjustment Nudge D-Pad
        nudgeTargetBtn = createStyledButton("Nudge: AIM", 0xFFE040FB, 0x22E040FB);
        nudgeTargetBtn.setOnClickListener(v -> {
            nudgeAim = !nudgeAim;
            nudgeTargetBtn.setText(nudgeAim ? "Nudge: AIM" : "Nudge: CUE");
        });
        menuView.addView(nudgeTargetBtn);

        Button btnLeft = createStyledButton("◀", Color.WHITE, 0x22FFFFFF);
        btnLeft.setOnClickListener(v -> nudge(-1, 0));
        menuView.addView(btnLeft);

        Button btnUp = createStyledButton("▲", Color.WHITE, 0x22FFFFFF);
        btnUp.setOnClickListener(v -> nudge(0, -1));
        menuView.addView(btnUp);

        Button btnDown = createStyledButton("▼", Color.WHITE, 0x22FFFFFF);
        btnDown.setOnClickListener(v -> nudge(0, 1));
        menuView.addView(btnDown);

        Button btnRight = createStyledButton("▶", Color.WHITE, 0x22FFFFFF);
        btnRight.setOnClickListener(v -> nudge(1, 0));
        menuView.addView(btnRight);

        // 7. Close Button
        Button closeBtn = createStyledButton("✕", 0xFFFF5252, 0x33FF5252);
        closeBtn.setOnClickListener(v -> stopSelf());
        menuView.addView(closeBtn);

        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            menuParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        menuParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        menuParams.y = 15;

        menuView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = menuParams.x;
                        initialY = menuParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        menuParams.x = initialX + (int) (event.getRawX() - touchX);
                        menuParams.y = initialY + (int) (event.getRawY() - touchY);
                        wm.updateViewLayout(menuView, menuParams);
                        return true;
                }
                return false;
            }
        });

        wm.addView(menuView, menuParams);
    }

    private String getZoomText() {
        if (zoomLevel == 0) return "Zoom: OFF";
        if (zoomLevel == 1) return "Zoom: 2x";
        return "Zoom: 3x";
    }

    private Button createStyledButton(String text, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(10.5f);
        b.setAllCaps(false);
        b.setPadding(16, 6, 16, 6);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(16f);
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(3, 0, 3, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateHandleSize(int newSize) {
        handleSize = Math.max(40, Math.min(200, newSize));
        prefs.edit().putInt("handle_size", handleSize).apply();
        if (sizeLabelBtn != null) {
            sizeLabelBtn.setText("Size: " + handleSize);
        }

        cueParams.width = handleSize;
        cueParams.height = handleSize;
        targetParams.width = handleSize;
        targetParams.height = handleSize;

        wm.updateViewLayout(cueHandle, cueParams);
        wm.updateViewLayout(targetHandle, targetParams);

        cueHandle.invalidate();
        targetHandle.invalidate();
        lineView.invalidate();
    }

    private void startForegroundServiceNotification() {
        String channelId = "overlay_service";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "8BP Aim Overlay", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        Notification notification = nb.setContentTitle("8BP Overlay Active")
                .setContentText("Aim line & cushion active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (imageThread != null) imageThread.quitSafely();
        if (mediaProjection != null) mediaProjection.stop();
        if (lineView != null) wm.removeView(lineView);
        if (cueHandle != null) wm.removeView(cueHandle);
        if (targetHandle != null) wm.removeView(targetHandle);
        if (tlHandle != null) wm.removeView(tlHandle);
        if (brHandle != null) wm.removeView(brHandle);
        if (menuView != null) wm.removeView(menuView);
    }

    // Pass-through full-screen canvas
    private class LineOverlayView extends View {
        private final Paint aimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cushionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tangentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tableBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ghostBallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Loupe paints
        private final Paint loupeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();

        public LineOverlayView(Context context) {
            super(context);
            aimPaint.setColor(Color.WHITE);
            aimPaint.setStrokeWidth(3.5f);
            aimPaint.setStyle(Paint.Style.STROKE);

            cushionPaint.setColor(Color.CYAN);
            cushionPaint.setStrokeWidth(3.5f);
            cushionPaint.setStyle(Paint.Style.STROKE);

            tangentPaint.setColor(Color.YELLOW);
            tangentPaint.setStrokeWidth(3f);
            tangentPaint.setStyle(Paint.Style.STROKE);
            tangentPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

            tableBorderPaint.setStyle(Paint.Style.STROKE);

            ghostBallPaint.setColor(0x88FFFFFF);
            ghostBallPaint.setStrokeWidth(2f);
            ghostBallPaint.setStyle(Paint.Style.STROKE);

            loupeBorderPaint.setColor(Color.WHITE);
            loupeBorderPaint.setStyle(Paint.Style.STROKE);
            loupeBorderPaint.setStrokeWidth(5f);

            crosshairPaint.setColor(0xFFFF1744);
            crosshairPaint.setStyle(Paint.Style.STROKE);
            crosshairPaint.setStrokeWidth(2f);

            pointerPaint.setColor(0x88FFFFFF);
            pointerPaint.setStyle(Paint.Style.STROKE);
            pointerPaint.setStrokeWidth(2.5f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0 && (w != screenW || h != screenH)) {
                screenW = w;
                screenH = h;
                recalculateTableBoundsFromRatios();
                setupVirtualDisplay();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            // 1. Draw outer cushion rail
            tableBorderPaint.setColor(isCalibrating ? 0xAA00FF00 : 0x3300FF00);
            tableBorderPaint.setStrokeWidth(isCalibrating ? 4f : 2f);
            canvas.drawRect(tableBounds, tableBorderPaint);

            float ballRadius = handleSize / 2f;

            // Physical effective bounce boundary indented by exact ball radius
            float innerL = tableBounds.left + ballRadius;
            float innerT = tableBounds.top + ballRadius;
            float innerR = tableBounds.right - ballRadius;
            float innerB = tableBounds.bottom - ballRadius;

            // Cue and Aim centers
            float cx = cueParams.x + ballRadius;
            float cy = cueParams.y + ballRadius;
            float tx = targetParams.x + ballRadius;
            float ty = targetParams.y + ballRadius;

            // 2. Direct Cue-to-Target Aim Line
            canvas.drawLine(cx, cy, tx, ty, aimPaint);

            // Ghost ball indicator at target impact
            canvas.drawCircle(tx, ty, ballRadius, ghostBallPaint);
            canvas.drawCircle(tx, ty, 3f, aimPaint);

            float dx = tx - cx;
            float dy = ty - cy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1f) return;
            dx /= len;
            dy /= len;

            // 3. 90-degree tangent deflection line
            float tanX = -dy * 120f;
            float tanY = dx * 120f;
            canvas.drawLine(tx - tanX, ty - tanY, tx + tanX, ty + tanY, tangentPaint);

            // 4. Cushion Reflection with Ball-Radius Offset Precision
            if (bounces > 0 && (innerR > innerL + 50) && (innerB > innerT + 50)) {
                float curX = Math.max(innerL, Math.min(innerR, tx));
                float curY = Math.max(innerT, Math.min(innerB, ty));
                float dirX = dx;
                float dirY = dy;

                for (int b = 0; b < bounces; b++) {
                    float minT = Float.MAX_VALUE;
                    int side = 0; // 1=L, 2=R, 3=T, 4=B

                    if (dirX < -0.0001f) {
                        float t = (innerL - curX) / dirX;
                        if (t > 0.01f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= innerT - 2f && testY <= innerB + 2f) {
                                minT = t;
                                side = 1;
                            }
                        }
                    } else if (dirX > 0.0001f) {
                        float t = (innerR - curX) / dirX;
                        if (t > 0.01f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= innerT - 2f && testY <= innerB + 2f) {
                                minT = t;
                                side = 2;
                            }
                        }
                    }

                    if (dirY < -0.0001f) {
                        float t = (innerT - curY) / dirY;
                        if (t > 0.01f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= innerL - 2f && testX <= innerR + 2f) {
                                minT = t;
                                side = 3;
                            }
                        }
                    } else if (dirY > 0.0001f) {
                        float t = (innerB - curY) / dirY;
                        if (t > 0.01f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= innerL - 2f && testX <= innerR + 2f) {
                                minT = t;
                                side = 4;
                            }
                        }
                    }

                    if (side == 0 || minT == Float.MAX_VALUE || minT <= 0.01f) break;

                    float nextX = curX + minT * dirX;
                    float nextY = curY + minT * dirY;

                    nextX = Math.max(innerL, Math.min(innerR, nextX));
                    nextY = Math.max(innerT, Math.min(innerB, nextY));

                    // Cushion segment
                    canvas.drawLine(curX, curY, nextX, nextY, cushionPaint);

                    // Ghost ball at rail contact
                    canvas.drawCircle(nextX, nextY, ballRadius, ghostBallPaint);
                    canvas.drawCircle(nextX, nextY, 4f, cushionPaint);

                    if (side == 1 || side == 2) dirX = -dirX;
                    else dirY = -dirY;

                    curX = nextX;
                    curY = nextY;
                }
            }

            // 5. Magnifier Loupe above active finger position
            if (isDragging && zoomLevel > 0) {
                float lx = dragX;
                float ly = dragY - 180f;

                // Edge avoidance: flip horizontally if close to top
                if (ly - LOUPE_RADIUS < 15f) {
                    ly = dragY;
                    lx = (dragX + 200f + LOUPE_RADIUS < screenW) ? (dragX + 200f) : (dragX - 200f);
                }

                // Guide line to ball
                canvas.drawLine(lx, ly, dragX, dragY, pointerPaint);

                canvas.save();
                clipPath.reset();
                clipPath.addCircle(lx, ly, LOUPE_RADIUS, Path.Direction.CW);
                canvas.clipPath(clipPath);

                synchronized (cropLock) {
                    if (cropBitmap != null) {
                        Rect src = new Rect(0, 0, cropSize, cropSize);
                        Rect dst = new Rect(
                                (int) (lx - LOUPE_RADIUS),
                                (int) (ly - LOUPE_RADIUS),
                                (int) (lx + LOUPE_RADIUS),
                                (int) (ly + LOUPE_RADIUS)
                        );
                        canvas.drawBitmap(cropBitmap, src, dst, null);
                    } else {
                        canvas.drawColor(0xEE1A1A1A);
                    }
                }

                // Sub-pixel crosshair centered on the ball
                canvas.drawLine(lx - 25f, ly, lx + 25f, ly, crosshairPaint);
                canvas.drawLine(lx, ly - 25f, lx, ly + 25f, crosshairPaint);
                canvas.drawCircle(lx, ly, 3f, crosshairPaint);

                canvas.restore();

                // Outer border
                canvas.drawCircle(lx, ly, LOUPE_RADIUS, loupeBorderPaint);
            }
        }
    }

    // Draggable circular handle with drag transparency
    private static class BallHandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private boolean isDragging = false;

        public BallHandleView(Context context, int color, String label) {
            super(context);
            this.label = label;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f);

            textPaint.setColor(color);
            textPaint.setTextSize(18f);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setDragging(boolean dragging) {
            this.isDragging = dragging;
            setAlpha(dragging ? 0.25f : 1.0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float r = getWidth() / 2f;
            canvas.drawCircle(r, r, r - 4f, paint);
            canvas.drawCircle(r, r, 3f, paint);
            if (!isDragging) {
                canvas.drawText(label, r, r - 8f, textPaint);
            }
        }
    }
}
