package com.zalexdev.stryker.utils;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zalexdev.stryker.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import com.stryker.terminal.bridge.StrykerLog;

public class LightfallView extends TextureView
        implements TextureView.SurfaceTextureListener, EffectBackdrop {

    private static final String TAG = "LightfallView";

    public static final int MAX_COLORS = 8;
    private static final int MAX_STREAKS = 16;

    private static final float[] SCALE_LADDER = {1.0f, 0.8f, 0.65f, 0.5f, 0.4f, 0.32f, 0.25f};
    private static final int LADDER_START = 3;

    private static final int TRACE_STEPS_DEFAULT = 32;
    private static final int TRACE_STEPS_FLOOR = 20;

    private static final float COST_HIGH = 0.85f;
    private static final float COST_LOW = 0.45f;
    private static final int STRIKES_TO_ADAPT = 12;
    private static final int ADAPT_COOLDOWN_FRAMES = 60;

    private static final float[] TRIANGLE = {
            -1f, -1f, 0f, 0f,
            3f, -1f, 2f, 0f,
            -1f, 3f, 0f, 2f,
    };

    private static final class Params {
        final float[] colors = new float[MAX_COLORS * 3];
        int colorCount = 3;
        final float[] bgColor = new float[3];
        final float[] pointerColor = new float[3];
        final float[] ink = {1f, 1f, 1f};

        float speed = 0.5f;
        int streakCount = 2;
        float streakWidth = 1f;
        float streakLength = 1f;
        float glow = 1f;
        float density = 0.6f;
        float twinkle = 1f;
        float zoom = 3f;
        float backgroundGlow = 0.5f;
        float opacity = 1f;
        float backgroundAlpha = 0f;

        boolean pointerEnabled = false;
        float pointerStrength = 0.5f;
        float pointerRadius = 1f;
        float pointerX = 0.5f;
        float pointerY = 0.5f;

        int traceSteps = TRACE_STEPS_DEFAULT;
        float maxScale = 0.8f;
        int targetFps = 60;

        Params copy() {
            Params p = new Params();
            System.arraycopy(colors, 0, p.colors, 0, colors.length);
            p.colorCount = colorCount;
            System.arraycopy(bgColor, 0, p.bgColor, 0, 3);
            System.arraycopy(pointerColor, 0, p.pointerColor, 0, 3);
            System.arraycopy(ink, 0, p.ink, 0, 3);
            p.speed = speed;
            p.streakCount = streakCount;
            p.streakWidth = streakWidth;
            p.streakLength = streakLength;
            p.glow = glow;
            p.density = density;
            p.twinkle = twinkle;
            p.zoom = zoom;
            p.backgroundGlow = backgroundGlow;
            p.opacity = opacity;
            p.backgroundAlpha = backgroundAlpha;
            p.pointerEnabled = pointerEnabled;
            p.pointerStrength = pointerStrength;
            p.pointerRadius = pointerRadius;
            p.pointerX = pointerX;
            p.pointerY = pointerY;
            p.traceSteps = traceSteps;
            p.maxScale = maxScale;
            p.targetFps = targetFps;
            return p;
        }
    }

    private final Params params = new Params();
    private volatile Params snapshot;

    private RenderThread thread;
    private boolean attached;
    private boolean pausedByCaller;

    public LightfallView(Context context) {
        super(context);
        init(null);
    }

    public LightfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public LightfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        setOpaque(false);
        setColors(0xFFA6C8FF, 0xFF5227FF, 0xFFFF9FFC);
        setBackgroundGlowColor(0xFF0A29FF);

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.LightfallView);
            try {
                if (a.hasValue(R.styleable.LightfallView_lf_color1)
                        || a.hasValue(R.styleable.LightfallView_lf_color2)
                        || a.hasValue(R.styleable.LightfallView_lf_color3)) {
                    setColors(
                            a.getColor(R.styleable.LightfallView_lf_color1, 0xFFA6C8FF),
                            a.getColor(R.styleable.LightfallView_lf_color2, 0xFF5227FF),
                            a.getColor(R.styleable.LightfallView_lf_color3, 0xFFFF9FFC));
                }
                if (a.hasValue(R.styleable.LightfallView_lf_backgroundGlowColor)) {
                    setBackgroundGlowColor(a.getColor(R.styleable.LightfallView_lf_backgroundGlowColor, 0xFF0A29FF));
                }
                if (a.hasValue(R.styleable.LightfallView_lf_inkColor)) {
                    setInkColor(a.getColor(R.styleable.LightfallView_lf_inkColor, 0xFFFFFFFF));
                }
                params.speed = a.getFloat(R.styleable.LightfallView_lf_speed, params.speed);
                params.streakCount = a.getInt(R.styleable.LightfallView_lf_streakCount, params.streakCount);
                params.streakWidth = a.getFloat(R.styleable.LightfallView_lf_streakWidth, params.streakWidth);
                params.streakLength = a.getFloat(R.styleable.LightfallView_lf_streakLength, params.streakLength);
                params.glow = a.getFloat(R.styleable.LightfallView_lf_glow, params.glow);
                params.density = a.getFloat(R.styleable.LightfallView_lf_density, params.density);
                params.twinkle = a.getFloat(R.styleable.LightfallView_lf_twinkle, params.twinkle);
                params.zoom = a.getFloat(R.styleable.LightfallView_lf_zoom, params.zoom);
                params.backgroundGlow = a.getFloat(R.styleable.LightfallView_lf_backgroundGlow, params.backgroundGlow);
                params.opacity = a.getFloat(R.styleable.LightfallView_lf_opacity, params.opacity);
                params.backgroundAlpha = a.getFloat(R.styleable.LightfallView_lf_backgroundAlpha, params.backgroundAlpha);
                params.traceSteps = a.getInt(R.styleable.LightfallView_lf_traceSteps, params.traceSteps);
                params.maxScale = a.getFloat(R.styleable.LightfallView_lf_maxScale, params.maxScale);
                params.targetFps = a.getInt(R.styleable.LightfallView_lf_targetFps, params.targetFps);
            } finally {
                a.recycle();
            }
        }

        clampParams();
        publish();
        setSurfaceTextureListener(this);
    }

    public void setColors(@ColorInt int... colors) {
        if (colors == null || colors.length == 0) return;
        int count = Math.min(colors.length, MAX_COLORS);
        float avgR = 0f, avgG = 0f, avgB = 0f;
        for (int i = 0; i < MAX_COLORS; i++) {
            int c = colors[Math.min(i, count - 1)];
            float r = Color.red(c) / 255f;
            float g = Color.green(c) / 255f;
            float b = Color.blue(c) / 255f;
            params.colors[i * 3] = r;
            params.colors[i * 3 + 1] = g;
            params.colors[i * 3 + 2] = b;
            if (i < count) {
                avgR += r;
                avgG += g;
                avgB += b;
            }
        }
        params.colorCount = count;
        params.pointerColor[0] = avgR / count;
        params.pointerColor[1] = avgG / count;
        params.pointerColor[2] = avgB / count;
        publish();
    }

    public void setInkColor(@ColorInt int color) {
        params.ink[0] = Color.red(color) / 255f;
        params.ink[1] = Color.green(color) / 255f;
        params.ink[2] = Color.blue(color) / 255f;
        publish();
    }

    public void setBackgroundGlowColor(@ColorInt int color) {
        params.bgColor[0] = Color.red(color) / 255f;
        params.bgColor[1] = Color.green(color) / 255f;
        params.bgColor[2] = Color.blue(color) / 255f;
        publish();
    }

    public void setSpeed(float speed) {
        params.speed = speed;
        publish();
    }

    public void setStreakCount(int count) {
        params.streakCount = clamp(count, 1, MAX_STREAKS);
        publish();
    }

    public void setStreakWidth(float width) {
        params.streakWidth = width;
        publish();
    }

    public void setStreakLength(float length) {
        params.streakLength = length;
        publish();
    }

    public void setGlow(float glow) {
        params.glow = glow;
        publish();
    }

    public void setDensity(float density) {
        params.density = density;
        publish();
    }

    public void setTwinkle(float twinkle) {
        params.twinkle = twinkle;
        publish();
    }

    public void setZoom(float zoom) {
        params.zoom = zoom;
        clampParams();
        publish();
    }

    public void setBackgroundGlowStrength(float strength) {
        params.backgroundGlow = strength;
        publish();
    }

    @Override
    public void setEffectOpacity(float opacity) {
        params.opacity = clamp01(opacity);
        publish();
    }

    public void setBackgroundAlpha(float alpha) {
        params.backgroundAlpha = clamp01(alpha);
        publish();
    }

    public void setPointerEnabled(boolean enabled) {
        params.pointerEnabled = enabled;
        publish();
    }

    public void setPointer(float xFraction, float yFraction) {
        params.pointerX = xFraction;
        params.pointerY = yFraction;
        publish();
    }

    public void setPointerStrength(float strength) {
        params.pointerStrength = strength;
        publish();
    }

    public void setPointerRadius(float radius) {
        params.pointerRadius = radius;
        publish();
    }

    public void setTraceSteps(int steps) {
        params.traceSteps = clamp(steps, 8, 48);
        publish();
    }

    public void setMaxScale(float scale) {
        params.maxScale = clamp(scale, SCALE_LADDER[SCALE_LADDER.length - 1], 1f);
        publish();
    }

    @Override
    public void setTargetFps(int fps) {
        params.targetFps = clamp(fps, 15, 120);
        publish();
    }

    @Override
    public void setPaused(boolean paused) {
        if (pausedByCaller == paused) return;
        pausedByCaller = paused;
        syncThread();
    }

    private void clampParams() {
        params.streakCount = clamp(params.streakCount, 1, MAX_STREAKS);
        params.traceSteps = clamp(params.traceSteps, 8, 48);
        params.maxScale = clamp(params.maxScale, SCALE_LADDER[SCALE_LADDER.length - 1], 1f);
        params.targetFps = clamp(params.targetFps, 15, 120);
        params.opacity = clamp01(params.opacity);
        params.backgroundAlpha = clamp01(params.backgroundAlpha);
        if (params.zoom < 0.1f) params.zoom = 0.1f;
    }

    private void publish() {
        snapshot = params.copy();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        syncThread();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        syncThread();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncThread();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncThread();
    }

    private boolean shouldRun() {
        return attached
                && !pausedByCaller
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE
                && isAvailable();
    }

    private void syncThread() {
        if (thread == null) return;
        thread.setRunning(shouldRun());
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        stopThread();
        thread = new RenderThread(surface, width, height);
        thread.start();
        syncThread();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        if (thread != null) thread.setSize(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        stopThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }

    private void stopThread() {
        if (thread == null) return;
        thread.shutdown();
        thread = null;
    }

    private final class RenderThread extends Thread {

        private final Object lock = new Object();
        private final SurfaceTexture surfaceTexture;

        private int viewWidth;
        private int viewHeight;
        private boolean sizeDirty = true;
        private boolean running;
        private boolean quit;
        private boolean resumed;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private EGLConfig eglConfig;

        private int program;
        private int vbo;
        private boolean derivatives;

        private int builtStreaks = -1;
        private int builtSteps = -1;

        private int uResolution, uMouse, uTime, uColorCount, uBgColor, uMouseColor, uInk;
        private int uSpeed, uStreakWidth, uStreakLength, uGlow, uDensity, uTwinkle, uZoom;
        private int uBgGlow, uOpacity, uBgAlpha, uMouseEnabled, uMouseStrength, uMouseRadius;
        private final int[] uColor = new int[MAX_COLORS];
        private int aPosition, aUv;

        private int bufferWidth;
        private int bufferHeight;
        private int ladderIndex = LADDER_START;
        private int stepsOverride;

        private float costEma;
        private int highStrikes;
        private int lowStrikes;
        private int cooldown;

        private float timeSeconds;
        private long lastTickNanos;

        RenderThread(SurfaceTexture surface, int width, int height) {
            super("LightfallRender");
            this.surfaceTexture = surface;
            this.viewWidth = width;
            this.viewHeight = height;
        }

        void setRunning(boolean value) {
            synchronized (lock) {
                if (running == value) return;
                running = value;
                if (value) resumed = true;
                lock.notifyAll();
            }
        }

        void setSize(int width, int height) {
            synchronized (lock) {
                if (viewWidth == width && viewHeight == height) return;
                viewWidth = width;
                viewHeight = height;
                sizeDirty = true;
                lock.notifyAll();
            }
        }

        void shutdown() {
            synchronized (lock) {
                quit = true;
                lock.notifyAll();
            }
            try {
                join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            try {
                if (!initEgl()) return;
                loop();
            } catch (Throwable t) {
                StrykerLog.w(TAG, "render thread stopped: " + t);
            } finally {
                releaseEgl();
            }
        }

        private void loop() {
            while (true) {
                Params p;
                int width, height;
                boolean resize;

                synchronized (lock) {
                    while (!quit && (!running || viewWidth <= 0 || viewHeight <= 0)) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (quit) return;
                    p = snapshot;
                    width = viewWidth;
                    height = viewHeight;
                    resize = sizeDirty;
                    sizeDirty = false;
                    if (resumed) {
                        resumed = false;
                        lastTickNanos = 0L;
                    }
                }

                if (p == null) continue;

                if (resize) applyScale(p, width, height);
                if (!ensureProgram(p)) return;
                holdBufferSize();

                long frameStart = SystemClock.elapsedRealtimeNanos();
                advanceClock(frameStart);

                drawFrame(p);
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    return;
                }

                long cost = SystemClock.elapsedRealtimeNanos() - frameStart;
                long budget = 1_000_000_000L / Math.max(p.targetFps, 1);
                adapt(p, cost, budget, width, height);
                throttle(frameStart, budget);
            }
        }

        private void advanceClock(long nowNanos) {
            if (lastTickNanos == 0L) {
                lastTickNanos = nowNanos;
                return;
            }
            long delta = nowNanos - lastTickNanos;
            lastTickNanos = nowNanos;
            if (delta > 100_000_000L) delta = 100_000_000L;
            timeSeconds += delta / 1_000_000_000f;
        }

        private void throttle(long frameStart, long budget) {
            long remaining = budget - (SystemClock.elapsedRealtimeNanos() - frameStart);
            if (remaining < 1_000_000L) return;
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void adapt(Params p, long cost, long budget, int width, int height) {
            costEma = costEma == 0f ? cost : costEma + (cost - costEma) * 0.1f;
            if (cooldown > 0) {
                cooldown--;
                return;
            }

            int ceiling = ceilingIndex(p.maxScale);
            if (costEma > budget * COST_HIGH) {
                highStrikes++;
                lowStrikes = 0;
                if (highStrikes < STRIKES_TO_ADAPT) return;
                highStrikes = 0;
                if (ladderIndex < SCALE_LADDER.length - 1) {
                    ladderIndex++;
                    applyScale(p, width, height);
                } else if (stepsOverride == 0 && p.traceSteps > TRACE_STEPS_FLOOR) {
                    stepsOverride = TRACE_STEPS_FLOOR;
                    builtSteps = -1;
                }
                costEma = 0f;
                cooldown = ADAPT_COOLDOWN_FRAMES;
                return;
            }

            highStrikes = 0;
            if (costEma < budget * COST_LOW && ladderIndex > ceiling) {
                lowStrikes++;
                if (lowStrikes < STRIKES_TO_ADAPT) return;
                lowStrikes = 0;
                ladderIndex--;
                applyScale(p, width, height);
                costEma = 0f;
                cooldown = ADAPT_COOLDOWN_FRAMES;
            }
        }

        private int ceilingIndex(float maxScale) {
            for (int i = 0; i < SCALE_LADDER.length; i++) {
                if (SCALE_LADDER[i] <= maxScale) return i;
            }
            return SCALE_LADDER.length - 1;
        }

        private void applyScale(Params p, int width, int height) {
            int ceiling = ceilingIndex(p.maxScale);
            if (ladderIndex < ceiling) ladderIndex = ceiling;

            float scale = SCALE_LADDER[ladderIndex];
            int bw = Math.max(2, Math.round(width * scale));
            int bh = Math.max(2, Math.round(height * scale));
            if (bw == bufferWidth && bh == bufferHeight && eglSurface != EGL14.EGL_NO_SURFACE) {
                surfaceTexture.setDefaultBufferSize(bw, bh);
                GLES20.glViewport(0, 0, bw, bh);
                return;
            }

            bufferWidth = bw;
            bufferHeight = bh;

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }

            surfaceTexture.setDefaultBufferSize(bw, bh);
            if (!createWindowSurface()) return;
            GLES20.glViewport(0, 0, bw, bh);
        }

        private void holdBufferSize() {
            if (bufferWidth <= 0 || bufferHeight <= 0) return;
            surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight);
            GLES20.glViewport(0, 0, bufferWidth, bufferHeight);
        }

        private boolean createWindowSurface() {
            int[] attribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, attribs, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                StrykerLog.w(TAG, "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                eglSurface = EGL14.EGL_NO_SURFACE;
                return false;
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                StrykerLog.w(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return false;
            }
            EGL14.eglSwapInterval(eglDisplay, 0);
            return true;
        }

        private boolean initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;

            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                return false;
            }

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] found = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, found, 0)
                    || found[0] <= 0) {
                StrykerLog.w(TAG, "no suitable EGL config");
                return false;
            }
            eglConfig = configs[0];

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                StrykerLog.w(TAG, "eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                eglContext = EGL14.EGL_NO_CONTEXT;
                return false;
            }

            Params p = snapshot;
            int width, height;
            synchronized (lock) {
                width = viewWidth;
                height = viewHeight;
                sizeDirty = false;
            }
            if (p == null || width <= 0 || height <= 0) return false;

            ladderIndex = Math.max(LADDER_START, ceilingIndex(p.maxScale));
            bufferWidth = Math.max(2, Math.round(width * SCALE_LADDER[ladderIndex]));
            bufferHeight = Math.max(2, Math.round(height * SCALE_LADDER[ladderIndex]));
            surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight);
            if (!createWindowSurface()) return false;

            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            derivatives = extensions != null && extensions.contains("GL_OES_standard_derivatives");

            GLES20.glViewport(0, 0, bufferWidth, bufferHeight);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDepthMask(false);
            GLES20.glClearColor(0f, 0f, 0f, 0f);

            createGeometry();
            return true;
        }

        private void createGeometry() {
            FloatBuffer data = ByteBuffer.allocateDirect(TRIANGLE.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            data.put(TRIANGLE).position(0);

            int[] buffers = new int[1];
            GLES20.glGenBuffers(1, buffers, 0);
            vbo = buffers[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, TRIANGLE.length * 4, data, GLES20.GL_STATIC_DRAW);
        }

        private boolean ensureProgram(Params p) {
            int steps = stepsOverride != 0 ? stepsOverride : p.traceSteps;
            if (program != 0 && builtStreaks == p.streakCount && builtSteps == steps) return true;

            int built = buildProgram(derivatives, p.streakCount, steps);
            if (built == 0) return false;
            if (program != 0) GLES20.glDeleteProgram(program);

            program = built;
            builtStreaks = p.streakCount;
            builtSteps = steps;
            bindLocations();
            return true;
        }

        private void bindLocations() {
            aPosition = GLES20.glGetAttribLocation(program, "position");
            aUv = GLES20.glGetAttribLocation(program, "uv");

            uResolution = GLES20.glGetUniformLocation(program, "iResolution");
            uMouse = GLES20.glGetUniformLocation(program, "iMouse");
            uTime = GLES20.glGetUniformLocation(program, "iTime");
            uColorCount = GLES20.glGetUniformLocation(program, "uColorCount");
            uBgColor = GLES20.glGetUniformLocation(program, "uBgColor");
            uMouseColor = GLES20.glGetUniformLocation(program, "uMouseColor");
            uInk = GLES20.glGetUniformLocation(program, "uInk");
            uSpeed = GLES20.glGetUniformLocation(program, "uSpeed");
            uStreakWidth = GLES20.glGetUniformLocation(program, "uStreakWidth");
            uStreakLength = GLES20.glGetUniformLocation(program, "uStreakLength");
            uGlow = GLES20.glGetUniformLocation(program, "uGlow");
            uDensity = GLES20.glGetUniformLocation(program, "uDensity");
            uTwinkle = GLES20.glGetUniformLocation(program, "uTwinkle");
            uZoom = GLES20.glGetUniformLocation(program, "uZoom");
            uBgGlow = GLES20.glGetUniformLocation(program, "uBgGlow");
            uOpacity = GLES20.glGetUniformLocation(program, "uOpacity");
            uBgAlpha = GLES20.glGetUniformLocation(program, "uBgAlpha");
            uMouseEnabled = GLES20.glGetUniformLocation(program, "uMouseEnabled");
            uMouseStrength = GLES20.glGetUniformLocation(program, "uMouseStrength");
            uMouseRadius = GLES20.glGetUniformLocation(program, "uMouseRadius");
            for (int i = 0; i < MAX_COLORS; i++) {
                uColor[i] = GLES20.glGetUniformLocation(program, "uColor" + i);
            }
        }

        private void drawFrame(Params p) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glUniform3f(uResolution, bufferWidth, bufferHeight, 1f);
            GLES20.glUniform1f(uTime, timeSeconds);
            GLES20.glUniform2f(uMouse, p.pointerX * bufferWidth, (1f - p.pointerY) * bufferHeight);

            for (int i = 0; i < MAX_COLORS; i++) {
                GLES20.glUniform3f(uColor[i], p.colors[i * 3], p.colors[i * 3 + 1], p.colors[i * 3 + 2]);
            }
            GLES20.glUniform1i(uColorCount, p.colorCount);
            GLES20.glUniform3f(uBgColor, p.bgColor[0], p.bgColor[1], p.bgColor[2]);
            GLES20.glUniform3f(uMouseColor, p.pointerColor[0], p.pointerColor[1], p.pointerColor[2]);
            GLES20.glUniform3f(uInk, p.ink[0], p.ink[1], p.ink[2]);

            GLES20.glUniform1f(uSpeed, p.speed);
            GLES20.glUniform1f(uStreakWidth, p.streakWidth);
            GLES20.glUniform1f(uStreakLength, p.streakLength);
            GLES20.glUniform1f(uGlow, p.glow);
            GLES20.glUniform1f(uDensity, p.density);
            GLES20.glUniform1f(uTwinkle, p.twinkle);
            GLES20.glUniform1f(uZoom, p.zoom);
            GLES20.glUniform1f(uBgGlow, p.backgroundGlow);
            GLES20.glUniform1f(uOpacity, p.opacity);
            GLES20.glUniform1f(uBgAlpha, p.backgroundAlpha);
            GLES20.glUniform1f(uMouseEnabled, p.pointerEnabled ? 1f : 0f);
            GLES20.glUniform1f(uMouseStrength, p.pointerStrength);
            GLES20.glUniform1f(uMouseRadius, p.pointerRadius);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, 0);
            if (aUv >= 0) {
                GLES20.glEnableVertexAttribArray(aUv);
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, 8);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        }

        private void releaseEgl() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            program = 0;
            vbo = 0;
        }
    }

    private static final String VERTEX_SHADER =
            "attribute vec2 position;\n"
                    + "attribute vec2 uv;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "  vUv = uv;\n"
                    + "  gl_Position = vec4(position, 0.0, 1.0);\n"
                    + "}\n";

    private static String buildFragmentShader(boolean derivatives, int streaks, int steps) {
        StringBuilder s = new StringBuilder(4096);

        if (derivatives) s.append("#extension GL_OES_standard_derivatives : enable\n");
        s.append("#ifdef GL_FRAGMENT_PRECISION_HIGH\n")
                .append("precision highp float;\n")
                .append("#else\n")
                .append("precision mediump float;\n")
                .append("#endif\n");
        if (derivatives) s.append("#define USE_DFD 1\n");
        s.append("#define STREAKS ").append(streaks).append('\n');
        s.append("#define STEPS ").append(steps).append('\n');
        s.append("#define TAU 6.28318530718\n");

        s.append("uniform vec3 iResolution;\n")
                .append("uniform vec2 iMouse;\n")
                .append("uniform float iTime;\n");
        for (int i = 0; i < MAX_COLORS; i++) {
            s.append("uniform vec3 uColor").append(i).append(";\n");
        }
        s.append("uniform int uColorCount;\n")
                .append("uniform vec3 uBgColor;\n")
                .append("uniform vec3 uMouseColor;\n")
                .append("uniform vec3 uInk;\n")
                .append("uniform float uSpeed;\n")
                .append("uniform float uStreakWidth;\n")
                .append("uniform float uStreakLength;\n")
                .append("uniform float uGlow;\n")
                .append("uniform float uDensity;\n")
                .append("uniform float uTwinkle;\n")
                .append("uniform float uZoom;\n")
                .append("uniform float uBgGlow;\n")
                .append("uniform float uOpacity;\n")
                .append("uniform float uBgAlpha;\n")
                .append("uniform float uMouseEnabled;\n")
                .append("uniform float uMouseStrength;\n")
                .append("uniform float uMouseRadius;\n")
                .append("varying vec2 vUv;\n");

        s.append("vec3 palette(float h) {\n")
                .append("  int count = uColorCount;\n")
                .append("  if (count < 1) count = 1;\n")
                .append("  int idx = int(floor(clamp(h, 0.0, 0.999999) * float(count)));\n");
        for (int i = 0; i < MAX_COLORS - 1; i++) {
            s.append(i == 0 ? "  if (idx <= 0) return uColor0;\n"
                    : "  if (idx == " + i + ") return uColor" + i + ";\n");
        }
        s.append("  return uColor").append(MAX_COLORS - 1).append(";\n")
                .append("}\n");

        s.append("vec3 tanhv(vec3 x) {\n")
                .append("  vec3 e = exp(-2.0 * x);\n")
                .append("  return (1.0 - e) / (1.0 + e);\n")
                .append("}\n");

        s.append("vec2 sceneC(vec2 frag, vec2 r) {\n")
                .append("  vec2 P = (frag + frag - r) / r.x;\n")
                .append("  vec3 n = normalize(vec3(P, uZoom));\n")
                .append("  vec3 c = vec3(0.0, 4.0, 1.0) / 4.5;\n")
                .append("  float z = 0.0;\n")
                .append("  float d = 1e3;\n")
                .append("  vec3 O = vec3(0.0);\n")
                .append("  for (int k = 0; k < STEPS; k++) {\n")
                .append("    if (d <= 1e-4) break;\n")
                .append("    O = z * n - c;\n")
                .append("    vec3 q = O * O;\n")
                .append("    d = 1.0 - sqrt(sqrt(dot(q, q)));\n")
                .append("    z += d;\n")
                .append("  }\n")
                .append("  return vec2(O.x, atan(O.z, O.y));\n")
                .append("}\n");

        s.append("void main() {\n")
                .append("  vec2 r = iResolution.xy;\n")
                .append("  vec2 C = vUv * r;\n")
                .append("  vec2 uv0 = (C + C - r) / r.x;\n")
                .append("  float T = 0.1 * iTime * uSpeed + 9.0;\n")
                .append("  float angRings = max(1.0, floor(TAU * max(uDensity, 0.05) + 0.5));\n")
                .append("  vec2 Y = vec2(5e-3, TAU / angRings);\n")
                .append("  vec2 c0 = sceneC(C, r);\n");

        s.append("#ifdef USE_DFD\n")
                .append("  vec2 dCx = dFdx(c0);\n")
                .append("  vec2 dCy = dFdy(c0);\n")
                .append("#else\n")
                .append("  vec2 dCx = sceneC(C + vec2(1.0, 0.0), r) - c0;\n")
                .append("  vec2 dCy = sceneC(C + vec2(0.0, 1.0), r) - c0;\n")
                .append("#endif\n")
                .append("  dCx.y -= TAU * floor(dCx.y / TAU + 0.5);\n")
                .append("  dCy.y -= TAU * floor(dCy.y / TAU + 0.5);\n")
                .append("  vec2 fw = abs(dCx) + abs(dCy);\n")
                .append("  C = c0;\n");

        s.append("  vec2 P = vec2(2.0, 1.0) * uv0 - (r / r.x) * vec2(0.0, 1.0);\n")
                .append("  vec3 O = uBgColor * 90.0 * uBgGlow / (1e3 * dot(P, P) + 6.0);\n")
                .append("  float mGlow = 0.0;\n")
                .append("  if (uMouseEnabled > 0.5) {\n")
                .append("    vec2 mN = (iMouse + iMouse - r) / r.x;\n")
                .append("    float md = length(uv0 - mN);\n")
                .append("    mGlow = exp(-md * md / max(uMouseRadius * uMouseRadius, 1e-4)) * uMouseStrength;\n")
                .append("    O += uMouseColor * mGlow * 0.25;\n")
                .append("  }\n")
                .append("  float zr = 5e-4 * uStreakWidth;\n")
                .append("  vec2 rr = vec2(max(length(fw), 1e-5));\n")
                .append("  float tail = 19.0 / max(uStreakLength, 0.05);\n")
                .append("  for (int m = 0; m < STREAKS; m++) {\n")
                .append("    float jf = float(m) + 1.0;\n")
                .append("    float ic = fract(sin(dot(vec2(jf, floor(C.x / Y.x + 0.5)), vec2(7.0, 11.0)) * 73.0));\n")
                .append("    vec2 Pp = C - (T + T * ic) * vec2(0.0, 1.0);\n")
                .append("    Pp -= floor(Pp / Y + 0.5) * Y;\n")
                .append("    float h = fract(8663.0 * ic);\n")
                .append("    vec3 col = palette(h);\n")
                .append("    float weight = mix(1.5, 1.0 + sin(T + 7.0 * h + 4.0), uTwinkle);\n")
                .append("    weight *= (1.0 + mGlow * 2.0);\n")
                .append("    vec2 inner = vec2(length(max(Pp, vec2(-1.0, 0.0))), length(Pp) - zr) - zr;\n")
                .append("    vec2 sm = vec2(1.0) - smoothstep(-rr, rr, inner);\n")
                .append("    O += dot(sm, vec2(exp(tail * Pp.y), 3.0)) * col * weight;\n")
                .append("    C.x += Y.x / 8.0;\n")
                .append("  }\n")
                .append("  vec3 colr = sqrt(tanhv(max(O * uGlow - vec3(0.04, 0.08, 0.02), 0.0)));\n")
                .append("  float lum = max(max(colr.r, colr.g), colr.b);\n")
                .append("  vec3 hue = colr / max(lum, 1e-4);\n")
                .append("  float a = clamp(max(lum, uBgAlpha) * uOpacity, 0.0, 1.0);\n")
                .append("  gl_FragColor = vec4(uInk * hue * a, a);\n")
                .append("}\n");

        return s.toString();
    }

    private static int buildProgram(boolean derivatives, int streaks, int steps) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vertex == 0) return 0;

        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, buildFragmentShader(derivatives, streaks, steps));
        if (fragment == 0) {
            GLES20.glDeleteShader(vertex);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            return 0;
        }

        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            StrykerLog.w(TAG, "link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) return 0;
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            StrykerLog.w(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }
}
