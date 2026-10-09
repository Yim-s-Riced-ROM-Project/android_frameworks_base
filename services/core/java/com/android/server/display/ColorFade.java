/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display;

import static com.android.internal.policy.TransitionAnimation.hasProtectedContent;

import android.content.Context;
import android.graphics.BLASTBufferQueue;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.window.ScreenCaptureInternal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.utils.DebugUtils;
import com.android.server.policy.WindowManagerPolicy;

import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * <p>
 * Animates a screen transition from on to off or off to on by applying
 * some GL transformations to a screenshot.
 * </p><p>
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 * </p>
 */
final class ColorFade {
    private static final String TAG = "ColorFade";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.ColorFade DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    // The layer for the electron beam surface.
    // This is currently hardcoded to be one layer above the boot animation.
    private static final int COLOR_FADE_LAYER = WindowManagerPolicy.COLOR_FADE_LAYER;

    // The number of frames to draw when preparing the animation so that it will
    // be ready to run smoothly.  We use 3 frames because we are triple-buffered.
    // See code for details.
    private static final int DEJANK_FRAMES = 3;

    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490;
    private static final int EGL_PROTECTED_CONTENT_EXT = 0x32C0;

    private final int mDisplayId;

    // Set to true when the animation context has been fully prepared.
    private boolean mPrepared;
    private boolean mCreatedResources;
    private int mMode;

    private final DisplayManagerInternal mDisplayManagerInternal;
    private int mDisplayLayerStack; // layer stack associated with primary display
    private int mDisplayWidth;      // real width, not rotated
    private int mDisplayHeight;     // real height, not rotated
    private SurfaceControl mSurfaceControl;
    private Surface mSurface;
    private SurfaceControl mBLASTSurfaceControl;
    private BLASTBufferQueue mBLASTBufferQueue;
    private NaturalSurfaceLayout mSurfaceLayout;
    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mSurfaceVisible;
    private float mSurfaceAlpha;
    private boolean mLastWasWideColor;
    private boolean mLastWasProtectedContent;

    // Texture names.  We only use one texture, which contains the screenshot.
    private final int[] mTexNames = new int[1];
    private boolean mTexNamesGenerated;
    private final float mTexMatrix[] = new float[16];
    private final float mProjMatrix[] = new float[16];
    private final int[] mGLBuffers = new int[2];
    private int mTexCoordLoc, mVertexLoc, mTexUnitLoc, mProjMatrixLoc, mTexMatrixLoc;
    private int mOpacityLoc, mGammaLoc;
    private int mProgram;

    // Vertex and corresponding texture coordinates.
    // We have 4 2D vertices, so 8 elements.  The vertices form a quad.
    private final FloatBuffer mVertexBuffer = createNativeFloatBuffer(8);
    private final FloatBuffer mTexCoordBuffer = createNativeFloatBuffer(8);

    private final Transaction mTransaction = new Transaction();

    // MODE_CRT: one colour layer per CrtCollapseFrame layer, children of mSurfaceControl.
    private final CrtCollapseFrame mCrtFrame = new CrtCollapseFrame();
    private final SurfaceControl[] mCrtLayers = new SurfaceControl[CrtCollapseFrame.LAYER_COUNT];
    private float mCrtDensity;
    private boolean mSurfaceIsCrt;

    // Glitch modes: the effect whose shader is loaded, null for every other mode.
    private ScreenOffEffect mGlitchEffect;
    private final GlitchFrame mGlitchFrame = new GlitchFrame();
    private int mResolutionLoc, mLevelLoc, mTickLoc, mIntensityLoc, mSplitLoc, mShareLoc;
    private int mDropFrameLoc, mRollLoc, mStaticAmountLoc, mDarkLoc;

    /**
     * Animates an color fade warming up.
     */
    public static final int MODE_WARM_UP = 0;

    /**
     * Animates an color fade shutting off.
     */
    public static final int MODE_COOL_DOWN = 1;

    /**
     * Animates a simple dim layer to fade the contents of the screen in or out progressively.
     */
    public static final int MODE_FADE = 2;

    /**
     * Draws the CRT collapse as solid-colour layers over live content. Captures nothing.
     */
    public static final int MODE_CRT = 3;

    /** Draws the Tear glitch over a screenshot of the display. */
    public static final int MODE_GLITCH_TEAR = 4;

    /** Draws the Corrupt glitch over a screenshot of the display. */
    public static final int MODE_GLITCH_CORRUPT = 5;

    /** Draws the Signal loss glitch over a screenshot of the display. */
    public static final int MODE_GLITCH_SIGNAL_LOSS = 6;

    public ColorFade(int displayId) {
        this(displayId, LocalServices.getService(DisplayManagerInternal.class));
    }

    @VisibleForTesting
    ColorFade(int displayId, DisplayManagerInternal displayManagerInternal) {
        mDisplayId = displayId;
        mDisplayManagerInternal = displayManagerInternal;
    }

    /** Returns the mode that draws {@code effect}; Stock has none. */
    static int modeFor(ScreenOffEffect effect) {
        switch (effect) {
            case CRT:
                return MODE_CRT;
            case TEAR:
                return MODE_GLITCH_TEAR;
            case CORRUPT:
                return MODE_GLITCH_CORRUPT;
            case SIGNAL_LOSS:
                return MODE_GLITCH_SIGNAL_LOSS;
            default:
                throw new IllegalArgumentException("Stock has no ColorFade mode");
        }
    }

    /** Returns the glitch effect a mode draws, or null when the mode is not a glitch. */
    static ScreenOffEffect glitchEffectFor(int mode) {
        switch (mode) {
            case MODE_GLITCH_TEAR:
                return ScreenOffEffect.TEAR;
            case MODE_GLITCH_CORRUPT:
                return ScreenOffEffect.CORRUPT;
            case MODE_GLITCH_SIGNAL_LOSS:
                return ScreenOffEffect.SIGNAL_LOSS;
            default:
                return null;
        }
    }

    private static int glitchShaderFor(ScreenOffEffect effect) {
        switch (effect) {
            case TEAR:
                return com.android.internal.R.raw.color_fade_glitch_tear;
            case CORRUPT:
                return com.android.internal.R.raw.color_fade_glitch_corrupt;
            default:
                return com.android.internal.R.raw.color_fade_glitch_signal;
        }
    }

    /**
     * Warms up the color fade in preparation for turning on or off.
     * This method prepares a GL context, and captures a screen shot.
     *
     * @param mode The desired mode for the upcoming animation.
     * @return True if the color fade is ready, false if it is uncontrollable.
     */
    public boolean prepare(Context context, int mode) {
        if (DEBUG) {
            Slog.d(TAG, "prepare: mode=" + mode);
        }

        mMode = mode;
        mGlitchEffect = glitchEffectFor(mode);

        DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
        if (displayInfo == null) {
            // displayInfo can be null if the associated display has been removed. There
            // is a delay between the display being removed and ColorFade being dismissed.
            return false;
        }

        // Get the display size and layer stack.
        // This is not expected to change while the color fade surface is showing.
        mDisplayLayerStack = displayInfo.layerStack;
        mDisplayWidth = displayInfo.getNaturalWidth();
        mDisplayHeight = displayInfo.getNaturalHeight();

        final boolean isWideColor = displayInfo.colorMode == Display.COLOR_MODE_DISPLAY_P3;
        if (mSurfaceControl != null && mSurfaceIsCrt != (mode == MODE_CRT)) {
            // The CRT container and the screenshot surfaces are not interchangeable.
            dismissResources();
            destroySurface();
        }
        if (mode == MODE_CRT) {
            mCrtDensity = displayInfo.logicalDensityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
            mPrepared = true;
            if (!createCrtSurface()) {
                dismiss();
                return false;
            }
            return true;
        }
        // Set mPrepared here so if initialization fails, resources can be cleaned up.
        mPrepared = true;

        final ScreenCaptureInternal.ScreenshotHardwareBuffer hardwareBuffer = captureScreen();
        if (hardwareBuffer == null) {
            dismiss();
            return false;
        }

        final boolean isProtected = hasProtectedContent(hardwareBuffer.getHardwareBuffer());
        if (!createSurfaceControl(hardwareBuffer.containsSecureLayers())) {
            dismiss();
            return false;
        }

        // MODE_FADE use ColorLayer to implement.
        if (mMode == MODE_FADE) {
            return true;
        }

        if (!(createEglContext(isProtected) && createEglSurface(isProtected, isWideColor)
                && setScreenshotTextureAndSetViewport(hardwareBuffer, displayInfo.rotation))) {
            dismiss();
            return false;
        }

        // Init GL
        if (!attachEglContext()) {
            return false;
        }
        try {
            if (!initGLShaders(context) || !initGLBuffers() || checkGlErrors("prepare")) {
                detachEglContext();
                dismiss();
                return false;
            }
        } finally {
            detachEglContext();
        }

        // Done.
        mCreatedResources = true;
        mLastWasProtectedContent = isProtected;
        mLastWasWideColor = isWideColor;

        // Dejanking optimization.
        // Some GL drivers can introduce a lot of lag in the first few frames as they
        // initialize their state and allocate graphics buffers for rendering.
        // Work around this problem by rendering the first frame of the animation a few
        // times.  The rest of the animation should run smoothly thereafter.
        // The frames we draw here aren't visible because we are essentially just
        // painting the screenshot as-is.
        if (mode == MODE_COOL_DOWN || mGlitchEffect != null) {
            for (int i = 0; i < DEJANK_FRAMES; i++) {
                draw(1.0f);
            }
        }
        return true;
    }

    private String readFile(Context context, int resourceId) {
        try{
            InputStream stream = context.getResources().openRawResource(resourceId);
            return new String(Streams.readFully(new InputStreamReader(stream)));
        }
        catch (IOException e) {
            Slog.e(TAG, "Unrecognized shader " + Integer.toString(resourceId));
            throw new RuntimeException(e);
        }
    }

    private int loadShader(Context context, int resourceId, int type) {
        String source = readFile(context, resourceId);

        int shader = GLES20.glCreateShader(type);

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Slog.e(TAG, "Could not compile shader " + shader + ", " + type + ":");
            Slog.e(TAG, GLES20.glGetShaderSource(shader));
            Slog.e(TAG, GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        return shader;
    }

    private boolean initGLShaders(Context context) {
        final int fragment = mGlitchEffect == null
                ? com.android.internal.R.raw.color_fade_frag : glitchShaderFor(mGlitchEffect);
        int vshader = loadShader(context, com.android.internal.R.raw.color_fade_vert,
                GLES20.GL_VERTEX_SHADER);
        int fshader = loadShader(context, fragment, GLES20.GL_FRAGMENT_SHADER);
        GLES20.glReleaseShaderCompiler();
        if (vshader == 0 || fshader == 0) return false;

        mProgram = GLES20.glCreateProgram();

        GLES20.glAttachShader(mProgram, vshader);
        GLES20.glAttachShader(mProgram, fshader);
        GLES20.glDeleteShader(vshader);
        GLES20.glDeleteShader(fshader);

        GLES20.glLinkProgram(mProgram);
        final int[] linked = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Slog.e(TAG, "Could not link ColorFade program: "
                    + GLES20.glGetProgramInfoLog(mProgram));
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
            return false;
        }

        mVertexLoc = GLES20.glGetAttribLocation(mProgram, "position");
        mTexCoordLoc = GLES20.glGetAttribLocation(mProgram, "uv");

        mProjMatrixLoc = GLES20.glGetUniformLocation(mProgram, "proj_matrix");
        mTexMatrixLoc = GLES20.glGetUniformLocation(mProgram, "tex_matrix");

        mOpacityLoc = GLES20.glGetUniformLocation(mProgram, "opacity");
        mGammaLoc = GLES20.glGetUniformLocation(mProgram, "gamma");
        mTexUnitLoc = GLES20.glGetUniformLocation(mProgram, "texUnit");

        mResolutionLoc = GLES20.glGetUniformLocation(mProgram, "resolution");
        mLevelLoc = GLES20.glGetUniformLocation(mProgram, "level");
        mTickLoc = GLES20.glGetUniformLocation(mProgram, "tick");
        mIntensityLoc = GLES20.glGetUniformLocation(mProgram, "intensity");
        mSplitLoc = GLES20.glGetUniformLocation(mProgram, "split");
        mShareLoc = GLES20.glGetUniformLocation(mProgram, "share");
        mDropFrameLoc = GLES20.glGetUniformLocation(mProgram, "dropFrame");
        mRollLoc = GLES20.glGetUniformLocation(mProgram, "roll");
        mStaticAmountLoc = GLES20.glGetUniformLocation(mProgram, "staticAmount");
        mDarkLoc = GLES20.glGetUniformLocation(mProgram, "dark");

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mTexUnitLoc, 0);
        GLES20.glUseProgram(0);

        return true;
    }

    private void destroyGLShaders() {
        GLES20.glDeleteProgram(mProgram);
        checkGlErrors("glDeleteProgram");
    }

    private boolean initGLBuffers() {
        //Fill vertices
        setQuad(mVertexBuffer, 0, 0, mDisplayWidth, mDisplayHeight);

        // Setup GL Textures
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        // Setup GL Buffers
        GLES20.glGenBuffers(2, mGLBuffers, 0);

        // fill vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mVertexBuffer.capacity() * 4,
                            mVertexBuffer, GLES20.GL_STATIC_DRAW);

        // fill tex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[1]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mTexCoordBuffer.capacity() * 4,
                            mTexCoordBuffer, GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        return true;
    }

    private void destroyGLBuffers() {
        GLES20.glDeleteBuffers(2, mGLBuffers, 0);
        checkGlErrors("glDeleteBuffers");
    }

    private static void setQuad(FloatBuffer vtx, float x, float y, float w, float h) {
        if (DEBUG) {
            Slog.d(TAG, "setQuad: x=" + x + ", y=" + y + ", w=" + w + ", h=" + h);
        }
        vtx.put(0, x);
        vtx.put(1, y);
        vtx.put(2, x);
        vtx.put(3, y + h);
        vtx.put(4, x + w);
        vtx.put(5, y + h);
        vtx.put(6, x + w);
        vtx.put(7, y);
    }

    /**
     * Dismisses the color fade animation resources.
     *
     * This function destroys the resources that are created for the color fade
     * animation but does not clean up the surface.
     */
    public void dismissResources() {
        if (DEBUG) {
            Slog.d(TAG, "dismissResources");
        }

        if (mCreatedResources) {
            attachEglContext();
            try {
                destroyScreenshotTexture();
                destroyGLShaders();
                destroyGLBuffers();
                destroyEglSurface();
            } finally {
                detachEglContext();
            }
            // This is being called with no active context so shouldn't be
            // needed but is safer to not change for now.
            GLES20.glFlush();
            mCreatedResources = false;
        }
    }

    /**
     * Dismisses the color fade animation surface and cleans up.
     *
     * To prevent stray photons from leaking out after the color fade has been
     * turned off, it is a good idea to defer dismissing the animation until the
     * color fade has been turned back on fully.
     */
    public void dismiss() {
        if (DEBUG) {
            Slog.d(TAG, "dismiss");
        }

        if (mPrepared) {
            dismissResources();
            destroySurface();
            mPrepared = false;
        }
    }

    /**
     * Destroys ColorFade animation and its resources
     *
     * This method should be called when the ColorFade is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed.
     */
    public void destroy() {
        if (DEBUG) {
            Slog.d(TAG, "destroy");
        }
        if (mPrepared) {
            if (mCreatedResources) {
                attachEglContext();
                try {
                    destroyScreenshotTexture();
                    destroyGLShaders();
                    destroyGLBuffers();
                    destroyEglSurface();
                } finally {
                    detachEglContext();
                }
            }
            destroyEglContext();
            destroySurface();
        }
    }

    /**
     * Draws an animation frame showing the color fade activated at the
     * specified level.
     *
     * @param level The color fade level.
     * @return True if successful.
     */
    public boolean draw(float level) {
        if (DEBUG) {
            Slog.d(TAG, "drawFrame: level=" + level);
        }

        if (!mPrepared) {
            return false;
        }

        if (mMode == MODE_CRT) {
            return drawCrt(level);
        }

        if (mMode == MODE_FADE) {
            return showSurface(1.0f - level);
        }

        if (!attachEglContext()) {
            return false;
        }
        try {
            // Clear frame to solid black.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Draw the frame.
            if (mGlitchEffect != null) {
                GlitchSchedule.at(mGlitchEffect, level, mGlitchFrame);
                drawGlitch(level);
            } else {
                double one_minus_level = 1 - level;
                double cos = Math.cos(Math.PI * one_minus_level);
                double sign = cos < 0 ? -1 : 1;
                float opacity = (float) -Math.pow(one_minus_level, 2) + 1;
                float gamma = (float) ((0.5d * sign * Math.pow(cos, 2) + 0.5d) * 0.9d + 0.1d);
                drawFaded(opacity, 1.f / gamma);
            }
            if (checkGlErrors("drawFrame")) {
                return false;
            }

            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        } finally {
            detachEglContext();
        }
        return showSurface(1.0f);
    }

    private void drawFaded(float opacity, float gamma) {
        if (DEBUG) {
            Slog.d(TAG, "drawFaded: opacity=" + opacity + ", gamma=" + gamma);
        }
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1f(mOpacityLoc, opacity);
        GLES20.glUniform1f(mGammaLoc, gamma);
        drawTexturedQuad();
    }

    /** Sets the frame's glitch uniforms from {@link #mGlitchFrame}; draws the screenshot quad. */
    private void drawGlitch(float level) {
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform2f(mResolutionLoc, mDisplayWidth, mDisplayHeight);
        GLES20.glUniform1f(mLevelLoc, level);
        GLES20.glUniform1f(mTickLoc, mGlitchFrame.tick);
        GLES20.glUniform1f(mIntensityLoc, mGlitchFrame.intensity);
        GLES20.glUniform1f(mSplitLoc, mGlitchFrame.split);
        GLES20.glUniform1f(mShareLoc, mGlitchFrame.share);
        GLES20.glUniform1f(mDropFrameLoc, mGlitchFrame.dropFrame);
        GLES20.glUniform1f(mRollLoc, mGlitchFrame.roll);
        GLES20.glUniform1f(mStaticAmountLoc, mGlitchFrame.staticAmount);
        GLES20.glUniform1f(mDarkLoc, mGlitchFrame.dark);
        drawTexturedQuad();
    }

    /** Draws the screenshot quad with the bound program; the caller has set its uniforms. */
    private void drawTexturedQuad() {
        GLES20.glUniformMatrix4fv(mProjMatrixLoc, 1, false, mProjMatrix, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, mTexMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[0]);
        GLES20.glEnableVertexAttribArray(mVertexLoc);
        GLES20.glVertexAttribPointer(mVertexLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[1]);
        GLES20.glEnableVertexAttribArray(mTexCoordLoc);
        GLES20.glVertexAttribPointer(mTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void ortho(float left, float right, float bottom, float top, float znear, float zfar) {
        mProjMatrix[0] = 2f / (right - left);
        mProjMatrix[1] = 0;
        mProjMatrix[2] = 0;
        mProjMatrix[3] = 0;
        mProjMatrix[4] = 0;
        mProjMatrix[5] = 2f / (top - bottom);
        mProjMatrix[6] = 0;
        mProjMatrix[7] = 0;
        mProjMatrix[8] = 0;
        mProjMatrix[9] = 0;
        mProjMatrix[10] = -2f / (zfar - znear);
        mProjMatrix[11] = 0;
        mProjMatrix[12] = -(right + left) / (right - left);
        mProjMatrix[13] = -(top + bottom) / (top - bottom);
        mProjMatrix[14] = -(zfar + znear) / (zfar - znear);
        mProjMatrix[15] = 1f;
    }

    private boolean setScreenshotTextureAndSetViewport(
            ScreenCaptureInternal.ScreenshotHardwareBuffer screenshotBuffer,
            @Surface.Rotation int rotation) {
        if (!attachEglContext()) {
            return false;
        }
        try {
            if (!mTexNamesGenerated) {
                GLES20.glGenTextures(1, mTexNames, 0);
                if (checkGlErrors("glGenTextures")) {
                    return false;
                }
                mTexNamesGenerated = true;
            }

            final SurfaceTexture st = new SurfaceTexture(mTexNames[0]);
            final Surface s = new Surface(st);
            try {
                s.attachAndQueueBufferWithColorSpace(screenshotBuffer.getHardwareBuffer(),
                        screenshotBuffer.getColorSpace());

                st.updateTexImage();
                st.getTransformMatrix(mTexMatrix);
            } finally {
                s.release();
                st.release();
            }
            // if screen is rotated, map texture starting different corner
            int indexDelta = (rotation == Surface.ROTATION_90) ? 2
                            : (rotation == Surface.ROTATION_180) ? 4
                            : (rotation == Surface.ROTATION_270) ? 6 : 0;

            // Set up texture coordinates for a quad.
            // We might need to change this if the texture ends up being
            // a different size from the display for some reason.
            mTexCoordBuffer.put(indexDelta, 0f);
            mTexCoordBuffer.put(indexDelta + 1, 0f);
            mTexCoordBuffer.put((indexDelta + 2) % 8, 0f);
            mTexCoordBuffer.put((indexDelta + 3) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 4) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 5) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 6) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 7) % 8, 0f);

            // Set up our viewport.
            GLES20.glViewport(0, 0, mDisplayWidth, mDisplayHeight);
            ortho(0, mDisplayWidth, 0, mDisplayHeight, -1, 1);
        } finally {
            detachEglContext();
        }
        return true;
    }

    private void destroyScreenshotTexture() {
        if (mTexNamesGenerated) {
            mTexNamesGenerated = false;
            GLES20.glDeleteTextures(1, mTexNames, 0);
            checkGlErrors("glDeleteTextures");
        }
    }

    private ScreenCaptureInternal.ScreenshotHardwareBuffer captureScreen() {
        ScreenCaptureInternal.ScreenshotHardwareBuffer screenshotBuffer =
                mDisplayManagerInternal.systemScreenshot(mDisplayId);
        if (screenshotBuffer == null) {
            Slog.e(TAG, "Failed to take screenshot. Buffer is null");
            return null;
        }
        return screenshotBuffer;
    }

    private boolean createSurfaceControl(boolean isSecure) {
        if (mSurfaceControl != null) {
            mTransaction.setSecure(mSurfaceControl, isSecure).apply();
            return true;
        }

        try {
            final SurfaceControl.Builder builder = new SurfaceControl.Builder()
                    .setName("ColorFade")
                    .setSecure(isSecure)
                    .setCallsite("ColorFade.createSurface");
            if (mMode == MODE_FADE) {
                builder.setColorLayer();
            } else {
                builder.setContainerLayer();
            }
            mSurfaceControl = builder.build();
        } catch (OutOfResourcesException ex) {
            Slog.e(TAG, "Unable to create surface.", ex);
            return false;
        }

        mTransaction.setLayerStack(mSurfaceControl, mDisplayLayerStack);
        mTransaction.setWindowCrop(mSurfaceControl, mDisplayWidth, mDisplayHeight);
        mSurfaceLayout = new NaturalSurfaceLayout(mDisplayManagerInternal, mDisplayId,
                mSurfaceControl);
        mSurfaceLayout.onDisplayTransaction(mTransaction);
        mTransaction.apply();

        if (mMode != MODE_FADE) {
            final SurfaceControl.Builder b = new SurfaceControl.Builder()
                    .setName("ColorFade BLAST")
                    .setParent(mSurfaceControl)
                    .setHidden(false)
                    .setSecure(isSecure)
                    .setBLASTLayer();
            mBLASTSurfaceControl = b.build();
            mBLASTBufferQueue = new BLASTBufferQueue("ColorFade", /*updateDestinationFrame*/ true);
            mBLASTBufferQueue.update(mBLASTSurfaceControl, mDisplayWidth, mDisplayHeight,
                    PixelFormat.TRANSLUCENT);
            mSurface = mBLASTBufferQueue.createSurface();
        }
        return true;
    }

    private boolean createEglContext(boolean isProtected) {
        if (mEglDisplay == null) {
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                logEglError("eglGetDisplay");
                return false;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
                mEglDisplay = null;
                logEglError("eglInitialize");
                return false;
            }
        }

        if (mEglConfig == null) {
            int[] eglConfigAttribList = new int[] {
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            int[] numEglConfigs = new int[1];
            EGLConfig[] eglConfigs = new EGLConfig[1];
            if (!EGL14.eglChooseConfig(mEglDisplay, eglConfigAttribList, 0,
                    eglConfigs, 0, eglConfigs.length, numEglConfigs, 0)) {
                logEglError("eglChooseConfig");
                return false;
            }
            if (numEglConfigs[0] <= 0) {
                Slog.e(TAG, "no valid config found");
                return false;
            }

            mEglConfig = eglConfigs[0];
        }

        // The old context needs to be destroyed if the protected flag has changed. The context will
        // be recreated based on the protected flag
        if (mEglContext != null && isProtected != mLastWasProtectedContent) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = null;
        }

        if (mEglContext == null) {
            int[] eglContextAttribList = new int[] {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE, EGL14.EGL_NONE,
                    EGL14.EGL_NONE
            };
            if (isProtected) {
                eglContextAttribList[2] = EGL_PROTECTED_CONTENT_EXT;
                eglContextAttribList[3] = EGL14.EGL_TRUE;
            }
            mEglContext = EGL14.eglCreateContext(mEglDisplay, mEglConfig, EGL14.EGL_NO_CONTEXT,
                    eglContextAttribList, 0);
            if (mEglContext == null) {
                logEglError("eglCreateContext");
                return false;
            }
        }
        return true;
    }

    private boolean createEglSurface(boolean isProtected, boolean isWideColor) {
        // The old surface needs to be destroyed if either the protected flag or wide color flag has
        // changed. The surface will be recreated based on the new flags.
        boolean didContentAttributesChange =
                isProtected != mLastWasProtectedContent || isWideColor != mLastWasWideColor;
        if (mEglSurface != null && didContentAttributesChange) {
            EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
            mEglSurface = null;
        }

        if (mEglSurface == null) {
            int[] eglSurfaceAttribList = new int[] {
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE
            };

            int index = 0;
            // If the current display is in wide color, then so is the screenshot.
            if (isWideColor) {
                eglSurfaceAttribList[index++] = EGL_GL_COLORSPACE_KHR;
                eglSurfaceAttribList[index++] = EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT;
            }
            if (isProtected) {
                eglSurfaceAttribList[index++] = EGL_PROTECTED_CONTENT_EXT;
                eglSurfaceAttribList[index] = EGL14.EGL_TRUE;
            }
            // turn our SurfaceControl into a Surface
            mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface,
                    eglSurfaceAttribList, 0);
            if (mEglSurface == null) {
                logEglError("eglCreateWindowSurface");
                return false;
            }
        }
        return true;
    }

    private void destroyEglSurface() {
        if (mEglSurface != null) {
            if (!EGL14.eglDestroySurface(mEglDisplay, mEglSurface)) {
                logEglError("eglDestroySurface");
            }
            mEglSurface = null;
        }
    }

    private void destroySurface() {
        if (mSurfaceControl != null) {
            if (mSurfaceLayout != null) {
                mSurfaceLayout.dispose();
            }
            mSurfaceLayout = null;
            mTransaction.remove(mSurfaceControl).apply();
            for (int i = 0; i < mCrtLayers.length; i++) {
                if (mCrtLayers[i] != null) {
                    mCrtLayers[i].release();
                    mCrtLayers[i] = null;
                }
            }
            mSurfaceIsCrt = false;
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }

            if (mBLASTSurfaceControl != null) {
                mBLASTSurfaceControl.release();
                mBLASTSurfaceControl = null;
                mBLASTBufferQueue.destroy();
                mBLASTBufferQueue = null;
            }

            mSurfaceControl = null;
            mSurfaceVisible = false;
            mSurfaceAlpha = 0f;
        }
    }

    private boolean createCrtSurface() {
        if (mSurfaceControl != null) {
            return true; // Reuse the container from an unfinished CRT transition.
        }
        try {
            mSurfaceControl = new SurfaceControl.Builder()
                    .setName("ColorFade CRT")
                    .setContainerLayer()
                    .setCallsite("ColorFade.createCrtSurface")
                    .build();
            // Set once the container exists, so destroySurface() releases any partial layers.
            mSurfaceIsCrt = true;
            for (int i = 0; i < CrtCollapseFrame.LAYER_COUNT; i++) {
                mCrtLayers[i] = new SurfaceControl.Builder()
                        .setName("ColorFade CRT layer")
                        .setColorLayer()
                        .setParent(mSurfaceControl)
                        .setCallsite("ColorFade.createCrtSurface")
                        .build();
                mTransaction.setColor(mCrtLayers[i], CrtCollapseFrame.LAYER_RGB[i])
                        .setLayer(mCrtLayers[i], i)
                        .setAlpha(mCrtLayers[i], 0f)
                        .show(mCrtLayers[i]);
            }
        } catch (OutOfResourcesException ex) {
            Slog.e(TAG, "Unable to create CRT surface.", ex);
            return false;
        }
        mTransaction.setLayerStack(mSurfaceControl, mDisplayLayerStack);
        mTransaction.setWindowCrop(mSurfaceControl, mDisplayWidth, mDisplayHeight);
        mSurfaceLayout = new NaturalSurfaceLayout(mDisplayManagerInternal, mDisplayId,
                mSurfaceControl);
        mSurfaceLayout.onDisplayTransaction(mTransaction);
        mTransaction.apply();
        return true;
    }

    /**
     * Applies one CRT frame in a single transaction. Allocates nothing. A layer without area gets
     * alpha 0, because SurfaceFlinger treats an empty crop as no crop and would fill the display.
     */
    private boolean drawCrt(float level) {
        mCrtFrame.update(level, mDisplayWidth, mDisplayHeight, mCrtDensity);
        for (int i = 0; i < CrtCollapseFrame.LAYER_COUNT; i++) {
            final float l = mCrtFrame.left[i];
            final float t = mCrtFrame.top[i];
            final float r = mCrtFrame.right[i];
            final float b = mCrtFrame.bottom[i];
            final boolean hasArea = r > l && b > t;
            mTransaction.setAlpha(mCrtLayers[i], hasArea ? mCrtFrame.alpha[i] : 0f);
            if (hasArea) {
                mTransaction.setCrop(mCrtLayers[i], l, t, r, b);
            }
        }
        mTransaction.setLayer(mSurfaceControl, COLOR_FADE_LAYER)
                .setAlpha(mSurfaceControl, 1f)
                .show(mSurfaceControl)
                .apply();
        mSurfaceVisible = true;
        mSurfaceAlpha = 1f;
        return true;
    }

    private boolean showSurface(float alpha) {
        if (!mSurfaceVisible || mSurfaceAlpha != alpha) {
            mTransaction.setLayer(mSurfaceControl, COLOR_FADE_LAYER)
                    .setAlpha(mSurfaceControl, alpha)
                    .show(mSurfaceControl)
                    .apply();
            mSurfaceVisible = true;
            mSurfaceAlpha = alpha;
        }
        return true;
    }

    private boolean attachEglContext() {
        if (mEglSurface == null) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            logEglError("eglMakeCurrent");
            return false;
        }
        return true;
    }

    private void detachEglContext() {
        if (mEglDisplay != null) {
            EGL14.eglMakeCurrent(mEglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    private void destroyEglContext() {
        if (mEglDisplay != null && mEglContext != null) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
        }
    }

    private static FloatBuffer createNativeFloatBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }

    private static void logEglError(String func) {
        Slog.e(TAG, func + " failed: error " + EGL14.eglGetError(), new Throwable());
    }

    private static boolean checkGlErrors(String func) {
        return checkGlErrors(func, true);
    }

    private static boolean checkGlErrors(String func, boolean log) {
        boolean hadError = false;
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            if (log) {
                Slog.e(TAG, func + " failed: error " + error, new Throwable());
            }
            hadError = true;
        }
        return hadError;
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Color Fade State:");
        pw.println("  mPrepared=" + mPrepared);
        pw.println("  mMode=" + mMode);
        pw.println("  mSurfaceIsCrt=" + mSurfaceIsCrt);
        pw.println("  mGlitchEffect=" + mGlitchEffect);
        pw.println("  mDisplayLayerStack=" + mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + mDisplayWidth);
        pw.println("  mDisplayHeight=" + mDisplayHeight);
        pw.println("  mSurfaceVisible=" + mSurfaceVisible);
        pw.println("  mSurfaceAlpha=" + mSurfaceAlpha);
    }

    /**
     * Keeps a surface aligned with the natural orientation of the device.
     * Updates the position and transformation of the matrix whenever the display
     * is rotated.  This is a little tricky because the display transaction
     * callback can be invoked on any thread, not necessarily the thread that
     * owns the color fade.
     */
    private static final class NaturalSurfaceLayout implements DisplayTransactionListener {
        private final DisplayManagerInternal mDisplayManagerInternal;
        private final int mDisplayId;
        private SurfaceControl mSurfaceControl;

        public NaturalSurfaceLayout(DisplayManagerInternal displayManagerInternal,
                int displayId, SurfaceControl surfaceControl) {
            mDisplayManagerInternal = displayManagerInternal;
            mDisplayId = displayId;
            mSurfaceControl = surfaceControl;
            mDisplayManagerInternal.registerDisplayTransactionListener(this);
        }

        public void dispose() {
            synchronized (this) {
                mSurfaceControl = null;
            }
            mDisplayManagerInternal.unregisterDisplayTransactionListener(this);
        }

        @Override
        public void onDisplayTransaction(Transaction t) {
            synchronized (this) {
                if (mSurfaceControl == null) {
                    return;
                }

                DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
                if (displayInfo == null) {
                    // displayInfo can be null if the associated display has been removed. There
                    // is a delay between the display being removed and ColorFade being dismissed.
                    return;
                }

                switch (displayInfo.rotation) {
                    case Surface.ROTATION_0:
                        t.setPosition(mSurfaceControl, 0, 0);
                        t.setMatrix(mSurfaceControl, 1, 0, 0, 1);
                        break;
                    case Surface.ROTATION_90:
                        t.setPosition(mSurfaceControl, 0, displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, 0, -1, 1, 0);
                        break;
                    case Surface.ROTATION_180:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth,
                                displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, -1, 0, 0, -1);
                        break;
                    case Surface.ROTATION_270:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth, 0);
                        t.setMatrix(mSurfaceControl, 0, 1, -1, 0);
                        break;
                }
            }
        }
    }
}
