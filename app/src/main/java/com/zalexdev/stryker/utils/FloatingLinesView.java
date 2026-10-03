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
import java.util.Locale;
import com.stryker.terminal.bridge.StrykerLog;

public class FloatingLinesView extends TextureView
        implements TextureView.SurfaceTextureListener, EffectBackdrop {

    private static final String TAG = "FloatingLinesView";

    private static final int BOTTOM = 0;
    private static final int MIDDLE = 1;
    private static final int TOP = 2;
    private static final int BANDS = 3;

    public static final int MAX_STOPS = 4;
    private static final int MAX_LINES_PER_BAND = 16;
    private static final int MAX_LINES_TOTAL = 48;
    private static final int RESERVED_UNIFORM_VECTORS = 16;

    private static final float[] PHASE_BASE = {1.5f, 2.0f, 1.0f};
    private static final float[] PHASE_STEP = {0.2f, 0.15f, 0.2f};
    private static final float[] BAND_GAIN = {0.2f * 0.5f, 1.0f * 0.5f, 0.1f * 0.5f};

    private static final float TIME_PERIOD = 62.831853f;

    private static final float[] SCALE_LADDER = {1.0f, 0.85f, 0.7f, 0.55f, 0.45f, 0.35f};
    private static final int LADDER_START = 1;

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
        final float[] stops = new float[MAX_STOPS * 3];
        int stopCount = 3;
        final float[] ink = {1f, 1f, 1f};

        final boolean[] enabled = {true, true, true};
        final int[] lineCount = {8, 8, 8};
        final float[] lineDistance = {0.08f, 0.08f, 0.08f};
        final float[] waveX = {2.0f, 5.0f, 10.0f};
        final float[] waveY = {-0.7f, 0.0f, 0.5f};
        final float[] waveTwist = {-1.0f, 0.2f, -0.4f};

        float speed = 1f;
        float haze = 0f;
        float opacity = 1f;
        float maxScale = 0.85f;
        int targetFps = 60;

        Params copy() {
            Params p = new Params();
            System.arraycopy(stops, 0, p.stops, 0, stops.length);
            p.stopCount = stopCount;
            System.arraycopy(ink, 0, p.ink, 0, 3);
            System.arraycopy(enabled, 0, p.enabled, 0, BANDS);
            System.arraycopy(lineCount, 0, p.lineCount, 0, BANDS);
            System.arraycopy(lineDistance, 0, p.lineDistance, 0, BANDS);
            System.arraycopy(waveX, 0, p.waveX, 0, BANDS);
            System.arraycopy(waveY, 0, p.waveY, 0, BANDS);
            System.arraycopy(waveTwist, 0, p.waveTwist, 0, BANDS);
            p.speed = speed;
            p.haze = haze;
            p.opacity = opacity;
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

    public FloatingLinesView(Context context) {
        super(context);
        init(null);
    }

    public FloatingLinesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public FloatingLinesView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        setOpaque(false);
        setColors(0xFFA6C8FF, 0xFF4A90E2, 0xFFFFFFFF);

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.FloatingLinesView);
            try {
                if (a.hasValue(R.styleable.FloatingLinesView_fl_color1)
                        || a.hasValue(R.styleable.FloatingLinesView_fl_color2)
                        || a.hasValue(R.styleable.FloatingLinesView_fl_color3)) {
                    setColors(
                            a.getColor(R.styleable.FloatingLinesView_fl_color1, 0xFFA6C8FF),
                            a.getColor(R.styleable.FloatingLinesView_fl_color2, 0xFF4A90E2),
                            a.getColor(R.styleable.FloatingLinesView_fl_color3, 0xFFFFFFFF));
                }
                if (a.hasValue(R.styleable.FloatingLinesView_fl_inkColor)) {
                    setInkColor(a.getColor(R.styleable.FloatingLinesView_fl_inkColor, 0xFFFFFFFF));
                }
                int count = a.getInt(R.styleable.FloatingLinesView_fl_lineCount, params.lineCount[0]);
                float distance = a.getFloat(R.styleable.FloatingLinesView_fl_lineDistance, 8f);
                for (int b = 0; b < BANDS; b++) {
                    params.lineCount[b] = count;
                    params.lineDistance[b] = distance * 0.01f;
                }
                params.enabled[BOTTOM] = a.getBoolean(R.styleable.FloatingLinesView_fl_enableBottom, true);
                params.enabled[MIDDLE] = a.getBoolean(R.styleable.FloatingLinesView_fl_enableMiddle, true);
                params.enabled[TOP] = a.getBoolean(R.styleable.FloatingLinesView_fl_enableTop, true);
                params.speed = a.getFloat(R.styleable.FloatingLinesView_fl_animationSpeed, params.speed);
                params.haze = a.getFloat(R.styleable.FloatingLinesView_fl_haze, params.haze);
                params.opacity = a.getFloat(R.styleable.FloatingLinesView_fl_opacity, params.opacity);
                params.maxScale = a.getFloat(R.styleable.FloatingLinesView_fl_maxScale, params.maxScale);
                params.targetFps = a.getInt(R.styleable.FloatingLinesView_fl_targetFps, params.targetFps);
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
        int count = Math.min(colors.length, MAX_STOPS);
        for (int i = 0; i < MAX_STOPS; i++) {
            int c = colors[Math.min(i, count - 1)];
            params.stops[i * 3] = Color.red(c) / 255f;
            params.stops[i * 3 + 1] = Color.green(c) / 255f;
            params.stops[i * 3 + 2] = Color.blue(c) / 255f;
        }
        params.stopCount = count;
        publish();
    }

    public void setInkColor(@ColorInt int color) {
        params.ink[0] = Color.red(color) / 255f;
        params.ink[1] = Color.green(color) / 255f;
        params.ink[2] = Color.blue(color) / 255f;
        publish();
    }

    public void setLineCount(int count) {
        setLineCounts(count, count, count);
    }

    public void setLineCounts(int bottom, int middle, int top) {
        params.lineCount[BOTTOM] = clamp(bottom, 1, MAX_LINES_PER_BAND);
        params.lineCount[MIDDLE] = clamp(middle, 1, MAX_LINES_PER_BAND);
        params.lineCount[TOP] = clamp(top, 1, MAX_LINES_PER_BAND);
        publish();
    }

    public void setLineDistance(float distance) {
        setLineDistances(distance, distance, distance);
    }

    public void setLineDistances(float bottom, float middle, float top) {
        params.lineDistance[BOTTOM] = bottom * 0.01f;
        params.lineDistance[MIDDLE] = middle * 0.01f;
        params.lineDistance[TOP] = top * 0.01f;
        publish();
    }

    public void setWavesEnabled(boolean bottom, boolean middle, boolean top) {
        params.enabled[BOTTOM] = bottom;
        params.enabled[MIDDLE] = middle;
        params.enabled[TOP] = top;
        publish();
    }

    public void setBottomWave(float x, float y, float twist) {
        setWave(BOTTOM, x, y, twist);
    }

    public void setMiddleWave(float x, float y, float twist) {
        setWave(MIDDLE, x, y, twist);
    }

    public void setTopWave(float x, float y, float twist) {
        setWave(TOP, x, y, twist);
    }

    private void setWave(int band, float x, float y, float twist) {
        params.waveX[band] = x;
        params.waveY[band] = y;
        params.waveTwist[band] = twist;
        publish();
    }

    public void setAnimationSpeed(float speed) {
        params.speed = speed;
        publish();
    }

    public void setHaze(float haze) {
        params.haze = Math.max(haze, 0f);
        publish();
    }

    @Override
    public void setEffectOpacity(float opacity) {
        params.opacity = clamp01(opacity);
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
        for (int b = 0; b < BANDS; b++) {
            params.lineCount[b] = clamp(params.lineCount[b], 1, MAX_LINES_PER_BAND);
        }
        params.stopCount = clamp(params.stopCount, 1, MAX_STOPS);
        params.maxScale = clamp(params.maxScale, SCALE_LADDER[SCALE_LADDER.length - 1], 1f);
        params.targetFps = clamp(params.targetFps, 15, 120);
        params.opacity = clamp01(params.opacity);
        params.haze = Math.max(params.haze, 0f);
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

        private int lineCeiling = MAX_LINES_TOTAL;
        private int lineCeilingOverride;

        private final int[] activeCounts = new int[BANDS];
        private final int[] builtCounts = {-1, -1, -1};
        private int builtStops = -1;

        private int uResolution, uLine, uInk, uOpacity, uHaze;
        private final int[] uStop = new int[MAX_STOPS];
        private final int[] uBand = new int[BANDS];
        private int aPosition, aUv;

        private final float[] lineTable = new float[MAX_LINES_TOTAL * 2];

        private int bufferWidth;
        private int bufferHeight;
        private int ladderIndex = LADDER_START;

        private float costEma;
        private int highStrikes;
        private int lowStrikes;
        private int cooldown;

        private float phase;
        private long lastTickNanos;

        RenderThread(SurfaceTexture surface, int width, int height) {
            super("FloatingLinesRender");
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
                advanceClock(frameStart, p.speed);

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

        private void advanceClock(long nowNanos, float speed) {
            if (lastTickNanos == 0L) {
                lastTickNanos = nowNanos;
                return;
            }
            long delta = nowNanos - lastTickNanos;
            lastTickNanos = nowNanos;
            if (delta > 100_000_000L) delta = 100_000_000L;
            phase += delta / 1_000_000_000f * speed;
            phase %= TIME_PERIOD;
            if (phase < 0f) phase += TIME_PERIOD;
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
                } else if (lineCeilingOverride == 0) {
                    lineCeilingOverride = Math.max(BANDS, totalLines(p) / 2);
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

        private int totalLines(Params p) {
            int total = 0;
            for (int b = 0; b < BANDS; b++) {
                if (p.enabled[b]) total += clamp(p.lineCount[b], 1, MAX_LINES_PER_BAND);
            }
            return total;
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

            int[] limit = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, limit, 0);
            lineCeiling = clamp(limit[0] - RESERVED_UNIFORM_VECTORS, BANDS, MAX_LINES_TOTAL);

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

        private void resolveCounts(Params p) {
            int budget = lineCeilingOverride != 0
                    ? Math.min(lineCeiling, lineCeilingOverride)
                    : lineCeiling;
            int total = 0;
            for (int b = 0; b < BANDS; b++) {
                activeCounts[b] = p.enabled[b] ? clamp(p.lineCount[b], 1, MAX_LINES_PER_BAND) : 0;
                total += activeCounts[b];
            }
            while (total > budget) {
                int fattest = 0;
                for (int b = 1; b < BANDS; b++) {
                    if (activeCounts[b] > activeCounts[fattest]) fattest = b;
                }
                if (activeCounts[fattest] <= 1) break;
                activeCounts[fattest]--;
                total--;
            }
        }

        private boolean ensureProgram(Params p) {
            resolveCounts(p);
            int stops = clamp(p.stopCount, 1, MAX_STOPS);
            if (program != 0 && stops == builtStops
                    && activeCounts[0] == builtCounts[0]
                    && activeCounts[1] == builtCounts[1]
                    && activeCounts[2] == builtCounts[2]) {
                return true;
            }

            int built = buildProgram(activeCounts, stops);
            if (built == 0) return false;
            if (program != 0) GLES20.glDeleteProgram(program);

            program = built;
            System.arraycopy(activeCounts, 0, builtCounts, 0, BANDS);
            builtStops = stops;
            bindLocations();
            return true;
        }

        private void bindLocations() {
            aPosition = GLES20.glGetAttribLocation(program, "position");
            aUv = GLES20.glGetAttribLocation(program, "uv");

            uResolution = GLES20.glGetUniformLocation(program, "iResolution");
            uLine = GLES20.glGetUniformLocation(program, "uLine");
            uInk = GLES20.glGetUniformLocation(program, "uInk");
            uOpacity = GLES20.glGetUniformLocation(program, "uOpacity");
            uHaze = GLES20.glGetUniformLocation(program, "uHaze");
            for (int i = 0; i < MAX_STOPS; i++) {
                uStop[i] = GLES20.glGetUniformLocation(program, "uStop" + i);
            }
            for (int b = 0; b < BANDS; b++) {
                uBand[b] = GLES20.glGetUniformLocation(program, "uBand" + b);
            }
        }

        private int updateLines(Params p) {
            float t = phase;
            int idx = 0;
            for (int b = 0; b < BANDS; b++) {
                for (int i = 0; i < activeCounts[b]; i++) {
                    float seed = PHASE_BASE[b] + PHASE_STEP[b] * i;
                    float amp = (float) Math.sin(seed + t * 0.2f) * 0.3f;
                    float phase = seed + t * 0.1f + p.lineDistance[b] * i + p.waveX[b];
                    lineTable[idx++] = amp * (float) Math.cos(phase);
                    lineTable[idx++] = amp * (float) Math.sin(phase);
                }
            }
            return idx / 2;
        }

        private void drawFrame(Params p) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glUniform3f(uResolution, bufferWidth, bufferHeight, 1f);

            int lines = updateLines(p);
            if (lines > 0 && uLine >= 0) GLES20.glUniform2fv(uLine, lines, lineTable, 0);

            for (int i = 0; i < MAX_STOPS; i++) {
                if (uStop[i] < 0) continue;
                GLES20.glUniform3f(uStop[i], p.stops[i * 3], p.stops[i * 3 + 1], p.stops[i * 3 + 2]);
            }
            for (int b = 0; b < BANDS; b++) {
                if (uBand[b] < 0) continue;
                GLES20.glUniform2f(uBand[b], p.waveY[b], p.waveTwist[b]);
            }
            GLES20.glUniform3f(uInk, p.ink[0], p.ink[1], p.ink[2]);
            GLES20.glUniform1f(uOpacity, p.opacity);
            GLES20.glUniform1f(uHaze, p.haze);

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

    private static String buildFragmentShader(int[] counts, int stops) {
        int total = counts[BOTTOM] + counts[MIDDLE] + counts[TOP];
        StringBuilder s = new StringBuilder(3072);

        s.append("#ifdef GL_FRAGMENT_PRECISION_HIGH\n")
                .append("precision highp float;\n")
                .append("#else\n")
                .append("precision mediump float;\n")
                .append("#endif\n");

        s.append("uniform vec3 iResolution;\n");
        s.append("uniform vec2 uLine[").append(Math.max(total, 1)).append("];\n");
        for (int i = 0; i < stops; i++) {
            s.append("uniform vec3 uStop").append(i).append(";\n");
        }
        for (int b = 0; b < BANDS; b++) {
            if (counts[b] > 0) s.append("uniform vec2 uBand").append(b).append(";\n");
        }
        s.append("uniform vec3 uInk;\n")
                .append("uniform float uOpacity;\n")
                .append("uniform float uHaze;\n")
                .append("varying vec2 vUv;\n");

        s.append("vec3 gradAt(float t) {\n");
        if (stops <= 1) {
            s.append("  return uStop0;\n");
        } else {
            s.append("  float g = t * ").append(f(stops - 1)).append(";\n")
                    .append("  vec3 c = uStop0;\n");
            for (int i = 1; i < stops; i++) {
                s.append("  c = mix(c, uStop").append(i)
                        .append(", clamp(g - ").append(f(i - 1)).append(", 0.0, 1.0));\n");
            }
            s.append("  return c;\n");
        }
        s.append("}\n");

        s.append("void main() {\n")
                .append("  vec2 res = iResolution.xy;\n")
                .append("  vec2 baseUv = (2.0 * (vUv * res) - res) / res.y;\n")
                .append("  baseUv.y = -baseUv.y;\n")
                .append("  float lg = log(length(baseUv) + 1.0);\n")
                .append("  vec3 col = vec3(0.0);\n");

        int base = 0;
        for (int b = 0; b < BANDS; b++) {
            int n = counts[b];
            if (n <= 0) continue;

            s.append("  {\n")
                    .append("    float a = uBand").append(b).append(".y * lg;\n")
                    .append("    float sa = sin(a);\n")
                    .append("    float ca = cos(a);\n")
                    .append("    vec2 rb = vec2(baseUv.x * ca + baseUv.y * sa,")
                    .append(" baseUv.y * ca - baseUv.x * sa);\n");
            if (b == TOP) s.append("    rb.x = -rb.x;\n");
            s.append("    vec2 sc = vec2(sin(rb.x), cos(rb.x));\n")
                    .append("    float my = rb.y + uBand").append(b).append(".x;\n")
                    .append("    for (int i = 0; i < ").append(n).append("; i++) {\n")
                    .append("      float w = 0.0175 / (abs(my - dot(sc, uLine[i + ")
                    .append(base).append("])) + 0.01) + uHaze;\n")
                    .append("      col += gradAt(float(i) * ").append(f(1f / Math.max(n - 1, 1)))
                    .append(") * ").append(f(BAND_GAIN[b])).append(" * w;\n")
                    .append("    }\n")
                    .append("  }\n");
            base += n;
        }

        s.append("  float lum = max(max(col.r, col.g), col.b);\n")
                .append("  vec3 hue = col / max(lum, 1e-4);\n")
                .append("  float al = clamp(lum * uOpacity, 0.0, 1.0);\n")
                .append("  gl_FragColor = vec4(uInk * hue * al, al);\n")
                .append("}\n");

        return s.toString();
    }

    private static String f(float value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static int buildProgram(int[] counts, int stops) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        if (vertex == 0) return 0;

        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, buildFragmentShader(counts, stops));
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
