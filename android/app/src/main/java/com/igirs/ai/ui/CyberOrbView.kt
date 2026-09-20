package com.igirs.ai.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    STANDBY,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * ChatGPT-Style Living Fluid Voice Orb
 *
 * Renders an ethereal, fluid celestial-blue and cloud-white morphing orb
 * matching the official ChatGPT Advanced Voice Mode interface.
 * Features organic breathing, fluid wave undulations, and dynamic speech reactivity.
 */
class CyberOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var orbState = OrbState.STANDBY

    // Palette matching ChatGPT Voice Orb
    private val colorPeriwinkleTop = Color.parseColor("#648AFF")  // Deep Sky Periwinkle
    private val colorPeriwinkleMid = Color.parseColor("#82A4FF")  // Soft Periwinkle Blue
    private val colorIceBlue = Color.parseColor("#B3C9FF")        // Ice Mist Blue
    private val colorCloudWhite = Color.parseColor("#FFFFFF")     // Luminous Cloud White
    private val colorAura = Color.parseColor("#2E6A9FFF")         // Subtle Outer Atmosphere

    // Paints
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baseOrbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fluidWavePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fluidWavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cloudCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Reusable Paths
    private val circleClipPath = Path()
    private val wavePath1 = Path()
    private val wavePath2 = Path()
    private val cloudCorePath = Path()

    // Animation drivers
    private var timePhase = 0f
    private var audioLevel = 0f
    private var speechEnvelope = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val speed = when (orbState) {
                OrbState.SPEAKING -> 2.4f
                OrbState.THINKING -> 3.2f
                OrbState.LISTENING -> 1.4f
                OrbState.STANDBY -> 1.0f
            }
            timePhase = (timePhase + 0.035f * speed) % (2 * PI.toFloat())
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setState(state: OrbState) {
        if (orbState == state) return
        orbState = state
        invalidate()
    }

    fun setAudioLevel(rmsDb: Float) {
        val normalized = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        audioLevel = audioLevel * 0.35f + normalized * 0.65f
        invalidate()
    }

    /**
     * Drives speech amplitude animation while AI speaks
     */
    fun setSpeechCadence(amplitude: Float) {
        speechEnvelope = speechEnvelope * 0.4f + amplitude.coerceIn(0f, 1f) * 0.6f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val minDim = minOf(width, height).toFloat()
        val baseRadius = minDim * 0.34f

        if (baseRadius <= 0) return

        // 1. Dynamic Breathing & Speech Modulation Scale
        val breathing = when (orbState) {
            OrbState.SPEAKING -> {
                val cadence = sin(timePhase * 2.2).toFloat() * 0.04f +
                        cos(timePhase * 4.5).toFloat() * 0.025f
                val speechSwelling = (speechEnvelope * 0.16f) + (audioLevel * 0.14f)
                (cadence + speechSwelling) * baseRadius
            }
            OrbState.THINKING -> {
                sin(timePhase * 3.0).toFloat() * (baseRadius * 0.035f)
            }
            OrbState.LISTENING -> {
                val userAudio = audioLevel * (baseRadius * 0.12f)
                sin(timePhase.toDouble()).toFloat() * (baseRadius * 0.025f) + userAudio
            }
            OrbState.STANDBY -> {
                sin(timePhase.toDouble()).toFloat() * (baseRadius * 0.02f)
            }
        }

        val currentRadius = baseRadius + breathing

        // 2. Soft Outer Ethereal Aura Glow
        val auraRadius = currentRadius * 1.32f
        auraPaint.shader = RadialGradient(
            cx, cy, auraRadius,
            intArrayOf(
                colorAura,
                Color.argb(35, 100, 138, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.65f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, auraRadius, auraPaint)

        // 3. Setup Perfect Circle Clipping Mask for the Fluid Core
        circleClipPath.reset()
        circleClipPath.addCircle(cx, cy, currentRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(circleClipPath)

        // 4. Base Spherical Gradient (Sky Blue at Top -> Mist in Middle -> Soft White at Bottom)
        baseOrbPaint.shader = LinearGradient(
            cx, cy - currentRadius,
            cx, cy + currentRadius,
            intArrayOf(
                colorPeriwinkleTop,
                colorPeriwinkleMid,
                colorIceBlue,
                colorCloudWhite
            ),
            floatArrayOf(0.0f, 0.32f, 0.65f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, currentRadius, baseOrbPaint)

        // 5. Undulating Fluid Clouds Layer 1 (Mid-Blue Wave Boundary)
        val waveAmplitude1 = currentRadius * (if (orbState == OrbState.SPEAKING) 0.12f else 0.07f)
        val waveBaseY1 = cy - currentRadius * 0.12f

        wavePath1.reset()
        wavePath1.moveTo(cx - currentRadius, cy + currentRadius)
        wavePath1.lineTo(cx - currentRadius, waveBaseY1)

        val steps = 36
        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val x = (cx - currentRadius) + progress * (2 * currentRadius)
            val angle1 = progress * (2.0 * PI).toFloat() + timePhase
            val angle2 = progress * (4.0 * PI).toFloat() - timePhase * 1.3f
            val y = waveBaseY1 +
                    sin(angle1.toDouble()).toFloat() * waveAmplitude1 +
                    cos(angle2.toDouble()).toFloat() * (waveAmplitude1 * 0.45f)
            wavePath1.lineTo(x, y)
        }

        wavePath1.lineTo(cx + currentRadius, cy + currentRadius)
        wavePath1.close()

        fluidWavePaint1.shader = LinearGradient(
            cx, waveBaseY1 - waveAmplitude1,
            cx, cy + currentRadius,
            intArrayOf(
                Color.argb(210, 120, 160, 255),
                Color.argb(240, 200, 220, 255),
                colorCloudWhite
            ),
            floatArrayOf(0.0f, 0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(wavePath1, fluidWavePaint1)

        // 6. Undulating Fluid Clouds Layer 2 (Luminous White Cloud Body)
        val waveAmplitude2 = currentRadius * (if (orbState == OrbState.SPEAKING) 0.16f else 0.09f)
        val waveBaseY2 = cy + currentRadius * 0.04f

        cloudCorePath.reset()
        cloudCorePath.moveTo(cx - currentRadius, cy + currentRadius + 10f)
        cloudCorePath.lineTo(cx - currentRadius, waveBaseY2)

        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val x = (cx - currentRadius) + progress * (2 * currentRadius)
            val angle1 = progress * (2.5 * PI).toFloat() - timePhase * 1.5f
            val angle2 = progress * (5.0 * PI).toFloat() + timePhase * 0.8f
            val y = waveBaseY2 +
                    sin(angle1.toDouble()).toFloat() * waveAmplitude2 +
                    cos(angle2.toDouble()).toFloat() * (waveAmplitude2 * 0.5f)
            cloudCorePath.lineTo(x, y)
        }

        cloudCorePath.lineTo(cx + currentRadius, cy + currentRadius + 10f)
        cloudCorePath.close()

        cloudCorePaint.shader = LinearGradient(
            cx, waveBaseY2 - waveAmplitude2,
            cx, cy + currentRadius,
            intArrayOf(
                Color.argb(180, 230, 240, 255),
                Color.argb(250, 245, 248, 255),
                colorCloudWhite
            ),
            floatArrayOf(0.0f, 0.35f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(cloudCorePath, cloudCorePaint)

        // 7. Organic Fluid Swirl Highlight (Upper Blue Cloud Drift)
        val mistY = cy - currentRadius * 0.42f + sin(timePhase * 1.2).toFloat() * (currentRadius * 0.05f)
        val mistX = cx + cos(timePhase * 0.8).toFloat() * (currentRadius * 0.08f)
        specularPaint.shader = RadialGradient(
            mistX, mistY, currentRadius * 0.65f,
            intArrayOf(
                Color.argb(80, 255, 255, 255),
                Color.argb(30, 140, 180, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(mistX, mistY, currentRadius * 0.65f, specularPaint)

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
