package com.aim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

public class OverlayService extends Service {
    private WindowManager wm;
    private LineOverlayView lineView;
    private View cueHandle;
    private View targetHandle;
    private LinearLayout menuView;

    private WindowManager.LayoutParams cueParams;
    private WindowManager.LayoutParams targetParams;

    private int bounces = 1; // 0 = off, 1 = 1 bounce, 2 = 2 bounces
    private final int HANDLE_SIZE = 120;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();

        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

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
        cueParams = createHandleParams(screenW / 3, screenH / 2);
        cueHandle = new BallHandleView(this, Color.WHITE, "CUE");
        attachDragListener(cueHandle, cueParams);
        wm.addView(cueHandle, cueParams);

        // 3. Target Ball Handle
        targetParams = createHandleParams((screenW / 3) * 2, screenH / 2);
        targetHandle = new BallHandleView(this, Color.RED, "AIM");
        attachDragListener(targetHandle, targetParams);
        wm.addView(targetHandle, targetParams);

        // 4. Quick Menu (Toggle Cushion / Exit)
        createMenuView(screenW);
    }

    private WindowManager.LayoutParams createHandleParams(int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                HANDLE_SIZE,
                HANDLE_SIZE,
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

    private void attachDragListener(View view, WindowManager.LayoutParams p) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = p.x;
                        initialY = p.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x = initialX + (int) (event.getRawX() - touchX);
                        p.y = initialY + (int) (event.getRawY() - touchY);
                        wm.updateViewLayout(v, p);
                        lineView.invalidate();
                        return true;
                }
                return false;
            }
        });
    }

    private void createMenuView(int screenW) {
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.HORIZONTAL);
        menuView.setBackgroundColor(0x88000000);

        Button toggleBtn = new Button(this);
        toggleBtn.setText("Cushion: 1x");
        toggleBtn.setTextColor(Color.YELLOW);
        toggleBtn.setTextSize(11);
        toggleBtn.setOnClickListener(v -> {
            bounces = (bounces + 1) % 3;
            toggleBtn.setText(bounces == 0 ? "Cushion: OFF" : "Cushion: " + bounces + "x");
            lineView.invalidate();
        });

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.RED);
        closeBtn.setTextSize(11);
        closeBtn.setOnClickListener(v -> stopSelf());

        menuView.addView(toggleBtn);
        menuView.addView(closeBtn);

        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(
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
        wm.addView(menuView, menuParams);
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

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (lineView != null) wm.removeView(lineView);
        if (cueHandle != null) wm.removeView(cueHandle);
        if (targetHandle != null) wm.removeView(targetHandle);
        if (menuView != null) wm.removeView(menuView);
    }

    // Pass-through full-screen canvas
    private class LineOverlayView extends View {
        private final Paint aimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cushionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tangentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tableBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

            tableBorderPaint.setColor(0x3300FF00);
            tableBorderPaint.setStrokeWidth(2f);
            tableBorderPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            // ponytail: static standard 8bp playing field bounds; add calibration if screen aspect ratio varies
            RectF table = new RectF(w * 0.115f, h * 0.165f, w * 0.885f, h * 0.835f);
            canvas.drawRect(table, tableBorderPaint);

            float cx = cueParams.x + HANDLE_SIZE / 2f;
            float cy = cueParams.y + HANDLE_SIZE / 2f;
            float tx = targetParams.x + HANDLE_SIZE / 2f;
            float ty = targetParams.y + HANDLE_SIZE / 2f;

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

            // 3. Cushion Bank Shot Raycast
            if (bounces > 0) {
                float curX = tx;
                float curY = ty;
                float dirX = dx;
                float dirY = dy;

                for (int b = 0; b < bounces; b++) {
                    float minT = Float.MAX_VALUE;
                    int side = 0; // 1=L, 2=R, 3=T, 4=B

                    if (dirX < -0.0001f) {
                        float t = (table.left - curX) / dirX;
                        if (t > 0.001f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= table.top && testY <= table.bottom) { minT = t; side = 1; }
                        }
                    } else if (dirX > 0.0001f) {
                        float t = (table.right - curX) / dirX;
                        if (t > 0.001f && t < minT) {
                            float testY = curY + t * dirY;
                            if (testY >= table.top && testY <= table.bottom) { minT = t; side = 2; }
                        }
                    }

                    if (dirY < -0.0001f) {
                        float t = (table.top - curY) / dirY;
                        if (t > 0.001f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= table.left && testX <= table.right) { minT = t; side = 3; }
                        }
                    } else if (dirY > 0.0001f) {
                        float t = (table.bottom - curY) / dirY;
                        if (t > 0.001f && t < minT) {
                            float testX = curX + t * dirX;
                            if (testX >= table.left && testX <= table.right) { minT = t; side = 4; }
                        }
                    }

                    if (side == 0 || minT == Float.MAX_VALUE) break;

                    float nextX = curX + minT * dirX;
                    float nextY = curY + minT * dirY;
                    canvas.drawLine(curX, curY, nextX, nextY, cushionPaint);
                    canvas.drawCircle(nextX, nextY, 8f, cushionPaint);

                    if (side == 1 || side == 2) dirX = -dirX;
                    else dirY = -dirY;

                    curX = nextX;
                    curY = nextY;
                }
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
            textPaint.setTextSize(22f);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float r = getWidth() / 2f;
            canvas.drawCircle(r, r, r - 6f, paint);
            canvas.drawCircle(r, r, 4f, paint); // Center dot
            canvas.drawText(label, r, r - 12f, textPaint);
        }
    }
}
