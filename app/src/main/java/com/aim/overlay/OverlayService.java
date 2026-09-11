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
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
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
    private View cueHandle;
    private View targetHandle;
    private View tlHandle;
    private View brHandle;
    private LinearLayout menuView;
    private Button resetTableBtn;

    private WindowManager.LayoutParams cueParams;
    private WindowManager.LayoutParams targetParams;
    private WindowManager.LayoutParams tlParams;
    private WindowManager.LayoutParams brParams;
    private WindowManager.LayoutParams menuParams;

    private final RectF tableBounds = new RectF();
    private boolean isCalibrating = false;
    private SharedPreferences prefs;

    private int bounces = 1;
    private int handleSize = 100; // Adjustable ball diameter (px)
    private final int CORNER_HANDLE_SIZE = 100;
    private Button sizeLabelBtn;

    private int screenW;
    private int screenH;
    private int screenDpi;

    // Magnifier state
    private boolean isDragging = false;
    private float dragX = 0f;
    private float dragY = 0f;

    // MediaProjection screen capture
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Bitmap screenBitmap;

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

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        // Force landscape logic (8BP is exclusively landscape)
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        screenDpi = dm.densityDpi;

        // Load preferences
        handleSize = prefs.getInt("handle_size", 100);
        recalculateTableBoundsFromRatios();

        // 1. Pass-through line drawing overlay
        lineView = new LineOverlayView(this);
        WindowManager.LayoutParams lineParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
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

        // 5. Clean Control Panel
        createMenuView();
    }

    private void recalculateTableBoundsFromRatios() {
        float rL = prefs.getFloat("r_left", 0.115f);
        float rT = prefs.getFloat("r_top", 0.165f);
        float rR = prefs.getFloat("r_right", 0.885f);
        float rB = prefs.getFloat("r_bottom", 0.835f);

        // Clamp ratios to prevent overflow or inversion
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
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        mediaProjection = mpm.getMediaProjection(code, data);
        if (mediaProjection == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {}, new Handler(Looper.getMainLooper()));
        }

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
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
    }

    private WindowManager.LayoutParams createHandleParams(int x, int y, int size) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size,
                size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
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
                            dragX = p.x + curSize / 2f;
                            dragY = p.y + curSize / 2f;
                            lineView.invalidate();
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        // Clamp drag inside screen boundaries to prevent handle loss/overflow
                        p.x = Math.max(0, Math.min(screenW - curSize, initialX + (int) (event.getRawX() - touchX)));
                        p.y = Math.max(0, Math.min(screenH - curSize, initialY + (int) (event.getRawY() - touchY)));
                        if (isBall) {
                            dragX = p.x + curSize / 2f;
                            dragY = p.y + curSize / 2f;
                        }
                        if (onDragCallback != null) onDragCallback.run();
                        wm.updateViewLayout(v, p);
                        lineView.invalidate();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (isBall) {
                            isDragging = false;
                            lineView.invalidate();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void createMenuView() {
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.HORIZONTAL);
        menuView.setGravity(Gravity.CENTER_VERTICAL);

        // Modern card background
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xEE1E2128);
        panelBg.setCornerRadius(30f);
        panelBg.setStroke(2, 0x44FFFFFF);
        menuView.setBackground(panelBg);
        menuView.setPadding(18, 10, 18, 10);

        // 1. Grip / Title
        TextView grip = new TextView(this);
        grip.setText("⠿ 8BP");
        grip.setTextColor(0xFFAAAAAA);
        grip.setTextSize(11f);
        grip.setPadding(6, 0, 14, 0);
        menuView.addView(grip);

        // 2. Cushion Button
        Button cushionBtn = createStyledButton("Cush: 1x", 0xFF00E5FF, 0x2200E5FF);
        cushionBtn.setOnClickListener(v -> {
            bounces = (bounces + 1) % 3;
            cushionBtn.setText(bounces == 0 ? "Cush: OFF" : "Cush: " + bounces + "x");
            lineView.invalidate();
        });
        menuView.addView(cushionBtn);

        // 3. Table Button
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
                // Save normalized relative ratios (immune to resolution/overflow issues)
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

        // Reset Table Button (visible only in EDIT mode)
        resetTableBtn = createStyledButton("Reset", 0xFFFF9100, 0x22FF9100);
        resetTableBtn.setVisibility(View.GONE);
        resetTableBtn.setOnClickListener(v -> resetTableBoundsToDefault());
        menuView.addView(resetTableBtn);

        // 4. Circle Size Controls ([-] Size: 100 [+])
        Button sizeMinus = createStyledButton("–", Color.WHITE, 0x22FFFFFF);
        sizeMinus.setOnClickListener(v -> updateHandleSize(handleSize - 10));
        menuView.addView(sizeMinus);

        sizeLabelBtn = createStyledButton("Size: " + handleSize, 0xFFFFD600, 0x22FFD600);
        sizeLabelBtn.setOnClickListener(v -> updateHandleSize(handleSize >= 160 ? 60 : handleSize + 20));
        menuView.addView(sizeLabelBtn);

        Button sizePlus = createStyledButton("+", Color.WHITE, 0x22FFFFFF);
        sizePlus.setOnClickListener(v -> updateHandleSize(handleSize + 10));
        menuView.addView(sizePlus);

        // 5. Close Button
        Button closeBtn = createStyledButton("✕", 0xFFFF5252, 0x33FF5252);
        closeBtn.setOnClickListener(v -> stopSelf());
        menuView.addView(closeBtn);

        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        menuParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        menuParams.y = 20;

        // Draggable panel
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

    private Button createStyledButton(String text, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setPadding(20, 8, 20, 8);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(18f);
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(4, 0, 4, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateHandleSize(int newSize) {
        handleSize = Math.max(50, Math.min(220, newSize));
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

        // Loupe paints
        private final Paint loupeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();

        private final float LOUPE_RADIUS = 110f;
        private final float CROP_RADIUS = 44f;

        public LineOverlayView(Context context) {
            super(context);
            aimPaint.setColor(Color.WHITE);
            aimPaint.setStrokeWidth(4f);
            aimPaint.setStyle(Paint.Style.STROKE);

            cushionPaint.setColor(Color.CYAN);
            cushionPaint.setStrokeWidth(4f);
            cushionPaint.setStyle(Paint.Style.STROKE);

            tangentPaint.setColor(Color.YELLOW);
            tangentPaint.setStrokeWidth(3f);
            tangentPaint.setStyle(Paint.Style.STROKE);
            tangentPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

            tableBorderPaint.setStyle(Paint.Style.STROKE);

            loupeBorderPaint.setColor(Color.WHITE);
            loupeBorderPaint.setStyle(Paint.Style.STROKE);
            loupeBorderPaint.setStrokeWidth(5f);

            crosshairPaint.setColor(Color.RED);
            crosshairPaint.setStyle(Paint.Style.STROKE);
            crosshairPaint.setStrokeWidth(2.5f);

            pointerPaint.setColor(0xCCFFFFFF);
            pointerPaint.setStyle(Paint.Style.STROKE);
            pointerPaint.setStrokeWidth(3f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                screenW = w;
                screenH = h;
                recalculateTableBoundsFromRatios();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            // Update screen capture buffer if available
            if (imageReader != null) {
                try {
                    Image img = imageReader.acquireLatestImage();
                    if (img != null) {
                        Image.Plane plane = img.getPlanes()[0];
                        ByteBuffer buf = plane.getBuffer();
                        int pixelStride = plane.getPixelStride();
                        int rowStride = plane.getRowStride();
                        int rowPadding = rowStride - pixelStride * screenW;
                        int bmpW = screenW + rowPadding / pixelStride;
                        if (screenBitmap == null || screenBitmap.getWidth() != bmpW) {
                            screenBitmap = Bitmap.createBitmap(bmpW, screenH, Bitmap.Config.ARGB_8888);
                        }
                        buf.rewind();
                        screenBitmap.copyPixelsFromBuffer(buf);
                        img.close();
                    }
                } catch (Exception ignored) {}
            }

            // Draw table cushion boundary
            tableBorderPaint.setColor(isCalibrating ? 0xAA00FF00 : 0x3300FF00);
            tableBorderPaint.setStrokeWidth(isCalibrating ? 4f : 2f);
            canvas.drawRect(tableBounds, tableBorderPaint);

            float cx = cueParams.x + handleSize / 2f;
            float cy = cueParams.y + handleSize / 2f;
            float tx = targetParams.x + handleSize / 2f;
            float ty = targetParams.y + handleSize / 2f;

            // 1. Direct aim line (Cue to Target)
            canvas.drawLine(cx, cy, tx, ty, aimPaint);

            float dx = tx - cx;
            float dy = ty - cy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1f) return;
            dx /= len;
            dy /= len;

            // 2. 90-degree tangent line at target impact
            float tanX = -dy * 120f;
            float tanY = dx * 120f;
            canvas.drawLine(tx - tanX, ty - tanY, tx + tanX, ty + tanY, tangentPaint);

            // 3. Cushion Bank Shot Raycast (with overflow & corner clamp protection)
            if (bounces > 0 && tableBounds.width() > 100 && tableBounds.height() > 100) {
                float curX = Math.max(tableBounds.left, Math.min(tableBounds.right, tx));
                float curY = Math.max(tableBounds.top, Math.min(tableBounds.bottom, ty));
                float dirX = dx;
                float dirY = dy;

                for (int b = 0; b < bounces; b++) {
                    float minT = Float.MAX_VALUE;
                    int side = 0; // 1=L, 2=R, 3=T, 4=B

                    if (dirX < -0.0001f) {
                        float t = (tableBounds.left - curX) / dirX;
                        if (t > 0.01f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= tableBounds.top - 2f && testY <= tableBounds.bottom + 2f) {
                                minT = t;
                                side = 1;
                            }
                        }
                    } else if (dirX > 0.0001f) {
                        float t = (tableBounds.right - curX) / dirX;
                        if (t > 0.01f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= tableBounds.top - 2f && testY <= tableBounds.bottom + 2f) {
                                minT = t;
                                side = 2;
                            }
                        }
                    }

                    if (dirY < -0.0001f) {
                        float t = (tableBounds.top - curY) / dirY;
                        if (t > 0.01f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= tableBounds.left - 2f && testX <= tableBounds.right + 2f) {
                                minT = t;
                                side = 3;
                            }
                        }
                    } else if (dirY > 0.0001f) {
                        float t = (tableBounds.bottom - curY) / dirY;
                        if (t > 0.01f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= tableBounds.left - 2f && testX <= tableBounds.right + 2f) {
                                minT = t;
                                side = 4;
                            }
                        }
                    }

                    if (side == 0 || minT == Float.MAX_VALUE || minT <= 0.01f) break;

                    float nextX = curX + minT * dirX;
                    float nextY = curY + minT * dirY;

                    // Strictly clamp to prevent visual overflow outside table borders
                    nextX = Math.max(tableBounds.left, Math.min(tableBounds.right, nextX));
                    nextY = Math.max(tableBounds.top, Math.min(tableBounds.bottom, nextY));

                    canvas.drawLine(curX, curY, nextX, nextY, cushionPaint);
                    canvas.drawCircle(nextX, nextY, 8f, cushionPaint);

                    if (side == 1 || side == 2) dirX = -dirX;
                    else dirY = -dirY;

                    curX = nextX;
                    curY = nextY;
                }
            }

            // 4. Magnifier Loupe above active finger position
            if (isDragging) {
                float lx = dragX;
                float ly = dragY - 220f;
                if (ly - LOUPE_RADIUS < 20f) {
                    ly = dragY + 220f;
                }

                // Guide needle from loupe to contact center
                canvas.drawLine(lx, ly + (ly < dragY ? LOUPE_RADIUS : -LOUPE_RADIUS), dragX, dragY, pointerPaint);

                canvas.save();
                clipPath.reset();
                clipPath.addCircle(lx, ly, LOUPE_RADIUS, Path.Direction.CW);
                canvas.clipPath(clipPath);

                if (screenBitmap != null) {
                    int srcL = (int) Math.max(0, dragX - CROP_RADIUS);
                    int srcT = (int) Math.max(0, dragY - CROP_RADIUS);
                    int srcR = (int) Math.min(screenBitmap.getWidth(), dragX + CROP_RADIUS);
                    int srcB = (int) Math.min(screenBitmap.getHeight(), dragY + CROP_RADIUS);
                    Rect src = new Rect(srcL, srcT, srcR, srcB);
                    Rect dst = new Rect(
                            (int) (lx - LOUPE_RADIUS),
                            (int) (ly - LOUPE_RADIUS),
                            (int) (lx + LOUPE_RADIUS),
                            (int) (ly + LOUPE_RADIUS)
                    );
                    canvas.drawBitmap(screenBitmap, src, dst, null);
                } else {
                    canvas.drawColor(0xDD111111);
                }

                // Crosshair at center of magnifying glass
                canvas.drawLine(lx - 25f, ly, lx + 25f, ly, crosshairPaint);
                canvas.drawLine(lx, ly - 25f, lx, ly + 25f, crosshairPaint);
                canvas.drawCircle(lx, ly, 3f, crosshairPaint);

                canvas.restore();

                // Loupe border
                canvas.drawCircle(lx, ly, LOUPE_RADIUS, loupeBorderPaint);
            }
        }
    }

    // Draggable circular handle
    private static class BallHandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;

        public BallHandleView(Context context, int color, String label) {
            super(context);
            this.label = label;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);

            textPaint.setColor(color);
            textPaint.setTextSize(20f);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float r = getWidth() / 2f;
            canvas.drawCircle(r, r, r - 5f, paint);
            canvas.drawCircle(r, r, 4f, paint);
            canvas.drawText(label, r, r - 10f, textPaint);
        }
    }
}
