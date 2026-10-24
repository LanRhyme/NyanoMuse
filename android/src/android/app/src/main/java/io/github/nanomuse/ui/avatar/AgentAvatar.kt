package io.github.nanomuse.ui.avatar

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.ToolOutcome
import io.github.nanomuse.avatar.AvatarStore
import io.github.nanomuse.companion.CompanionMoodStore.Mood
import kotlinx.coroutines.delay

/**
 * What the face is doing. The built-in character — a small pale-yellow dragon, drawn and posed
 * with qwen-image-3.0-pro and animated with MiniMax-H3 (the 0.1.21 defaults; a user's own face now
 * comes from qwen-image-3.0 and wan2.2-i2v-flash, the same way) —
 * has a still per mood and a 4-second looping clip for the four moods that last long enough
 * to move; a custom face has a picture per mood when the image model could pose it.
 */
enum class AgentMood(@DrawableRes val drawable: Int, @RawRes val motion: Int?) {
    IDLE(R.drawable.nm_avatar_idle, null),
    WORKING(R.drawable.nm_avatar_working, null),
    WAITING(R.drawable.nm_avatar_waiting, null),
    HAPPY(R.drawable.nm_avatar_happy, null),
    ERROR(R.drawable.nm_avatar_error, null),
}

private const val HAPPY_AFTERGLOW_MS = 3_000L
private const val ERROR_AFTERGLOW_MS = 4_000L

/**
 * How the body carries one companion mood, in units a person can actually see.
 *
 * The family alone is too coarse to show on a face: 平静, 专注, 疲惫, 消沉, 麻木 and 摆烂
 * all map to SLEEPY, and the family-level constants moved the picture by 1.5% and 1.5dp,
 * which reads as no movement at all. These per-mood numbers are several times larger, so
 * the six faces of that family stop looking identical.
 *
 * [breathMs] is the period of the slow in-and-out; longer reads as calmer or wearier.
 * [bobDp] is how far the body sinks (positive) or rises (negative) as it breathes.
 * [tiltDeg] is the resting lean, applied as a sway around that angle.
 * [scale] multiplies the whole figure, so a mood can sit taller or be drawn in.
 */
internal data class CompanionPosture(
    val breathMs: Int = 2600,
    val bobDp: Float = 0f,
    val tiltDeg: Float = 0f,
    val scale: Float = 1f,
    /** Extra settle on arrival, in dp, so a change of mood is visible even at rest. */
    val entryDropDp: Float = 0f,
)

/**
 * The posture for [mood], preferring the concrete mood over its family.
 *
 * Inside SLEEPY the members are told apart on purpose: 专注 sits up straight and breathes
 * evenly, 平静 is the neutral rest, 疲惫 sinks and slows, 消沉 tips forward and barely
 * lifts, 麻木 is flat and shallow, 摆烂 slumps the most and stays there.
 */
internal fun companionPosture(
    family: io.github.nanomuse.companion.CompanionEmotion.PrimaryMood?,
    detail: io.github.nanomuse.companion.CompanionMoodStore.Mood? = null,
    intensity: String = "normal",
): CompanionPosture {
    val base = detail?.let { m ->
        when (m) {
            Mood.FOCUSED -> CompanionPosture(breathMs = 2200, bobDp = -1.0f, scale = 1.035f)
            Mood.CALM -> CompanionPosture(breathMs = 3000, bobDp = 0.6f)
            Mood.TIRED -> CompanionPosture(
                breathMs = 3800, bobDp = 3.4f, tiltDeg = 2.5f, scale = 0.975f, entryDropDp = 3f
            )
            Mood.GLOOMY -> CompanionPosture(
                breathMs = 4200, bobDp = 3.0f, tiltDeg = 5.5f, scale = 0.965f, entryDropDp = 3f
            )
            Mood.NUMB -> CompanionPosture(breathMs = 3400, bobDp = 1.2f, scale = 0.985f)
            Mood.CHECKEDOUT -> CompanionPosture(
                breathMs = 4600, bobDp = 4.2f, tiltDeg = 4f, scale = 0.955f, entryDropDp = 4f
            )
            else -> null
        }
    } ?: when (family) {
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.HYPER ->
            CompanionPosture(breathMs = 900, bobDp = -3.5f, tiltDeg = 6f, scale = 1.06f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.EXCITED ->
            CompanionPosture(breathMs = 1200, bobDp = -2.5f, scale = 1.045f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.HAPPY ->
            CompanionPosture(breathMs = 2000, bobDp = -1.5f, scale = 1.03f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.TOUCHED ->
            CompanionPosture(breathMs = 2500, scale = 1.028f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SHY ->
            CompanionPosture(breathMs = 2400, tiltDeg = 3.5f, scale = 1.02f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CARING ->
            CompanionPosture(breathMs = 2800, tiltDeg = 2f, scale = 1.025f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CURIOUS ->
            CompanionPosture(breathMs = 2400, bobDp = -1.8f, tiltDeg = 5f, scale = 1.025f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.LONGING ->
            CompanionPosture(breathMs = 2900, scale = 1.02f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SULK_JEALOUS ->
            CompanionPosture(breathMs = 2200, tiltDeg = -3f, scale = 0.99f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CRACKED_HURT ->
            CompanionPosture(breathMs = 1800, bobDp = 2f, scale = 0.94f, entryDropDp = 3f)
        io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SLEEPY ->
            CompanionPosture(breathMs = 3600, bobDp = 1.5f)
        null -> CompanionPosture()
    }

    return when (intensity) {
        "vivid" -> CompanionPosture(
            breathMs = (base.breathMs * 0.85f).toInt(),
            bobDp = base.bobDp * 1.4f,
            tiltDeg = base.tiltDeg * 1.4f,
            scale = 1.0f + (base.scale - 1.0f) * 1.5f,
            entryDropDp = base.entryDropDp * 1.3f
        )
        "subtle" -> CompanionPosture(
            breathMs = (base.breathMs * 1.15f).toInt(),
            bobDp = base.bobDp * 0.5f,
            tiltDeg = base.tiltDeg * 0.5f,
            scale = 1.0f + (base.scale - 1.0f) * 0.5f,
            entryDropDp = base.entryDropDp * 0.6f
        )
        else -> base
    }
}

/**
 * The mood of the chat header, derived from the session's own streaming flag and the app-wide
 * "it needs you" gates. Nothing here is new state: waiting comes from the permission and config
 * confirmation queues, working from [isStreaming], and the short smile / wince after a turn ends
 * is the only thing remembered locally.
 */
@Composable
fun rememberAgentMood(isStreaming: Boolean, error: String?): AgentMood {
    val pendingPermission by OffloadPermissionManager.pendingRequest.collectAsState()
    val pendingAndroidPermission by OffloadPermissionManager.pendingAndroidPermission.collectAsState()
    val pendingSettingsGate by OffloadPermissionManager.pendingSettingsGate.collectAsState()
    val pendingConfig by ConfigConfirmationGate.pending.collectAsState()
    val pendingRisk by io.github.nanomuse.guard.RiskGate.pending.collectAsState()
    val waiting = pendingPermission != null || pendingAndroidPermission != null ||
        pendingSettingsGate != null || pendingConfig != null || pendingRisk != null

    // The tracker's last tool outcome is sticky across turns, so only an outcome that landed
    // during *this* run may colour the afterglow: a plain reply after an old failure is a
    // success, not a second wince.
    var runOutcome by remember { mutableStateOf<ToolOutcome?>(null) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            runOutcome = null
            SessionActivityTracker.lastToolOutcome.collect { o ->
                if (o != ToolOutcome.Unknown) runOutcome = o
            }
        }
    }

    var afterglow by remember { mutableStateOf<AgentMood?>(null) }
    var previousStreaming by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (previousStreaming && !isStreaming) {
            val outcome = runOutcome
            val failed = error != null || outcome == ToolOutcome.Error || outcome == ToolOutcome.Timeout
            // A run the user stopped ends quietly: no smile, no wince.
            if (outcome != ToolOutcome.Cancelled) {
                afterglow = if (failed) AgentMood.ERROR else AgentMood.HAPPY
                delay(if (failed) ERROR_AFTERGLOW_MS else HAPPY_AFTERGLOW_MS)
                afterglow = null
            }
        }
        previousStreaming = isStreaming
    }

    // Drawing a new face is work too (the chat's "change your avatar" flow).
    val avatarStage by io.github.nanomuse.avatar.AvatarFlow.stage.collectAsState()
    val avatarSlots by io.github.nanomuse.avatar.AvatarStudio.slots.collectAsState()
    val drawing = avatarStage is io.github.nanomuse.avatar.AvatarFlow.Stage.Finalizing ||
        (avatarStage is io.github.nanomuse.avatar.AvatarFlow.Stage.Choosing && avatarSlots.any { it is io.github.nanomuse.avatar.AvatarStudio.Slot.Loading })

    return when {
        waiting -> AgentMood.WAITING
        isStreaming || drawing -> AgentMood.WORKING
        else -> afterglow ?: AgentMood.IDLE
    }
}

/**
 * The face at [size]: the built-in dragon, or the user's own picture set from Settings →
 * Appearance. It cross-fades between moods and moves the way Muse's does — a slow breath at
 * rest, a busy bob while working, a curious tilt while waiting, a pop when it is pleased and a
 * quick shake when something failed. Tapping it opens the appearance page. A clip of the
 * person's own ([io.github.nanomuse.avatar.OwnClip]) plays over the still for every mood.
 */
@Composable
fun AgentAvatar(
    mood: AgentMood,
    size: Dp,
    modifier: Modifier = Modifier,
    companionMood: io.github.nanomuse.companion.CompanionEmotion.PrimaryMood? = null,
    companionDetail: io.github.nanomuse.companion.CompanionMoodStore.Mood? = null,
    companionIntensity: String = "normal",
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val custom by AvatarStore.current.collectAsState()
    val clips by io.github.nanomuse.avatar.AvatarMotion.clips.collectAsState()
    val ownClip by io.github.nanomuse.avatar.OwnClip.current.collectAsState()
    val ownPoster by io.github.nanomuse.avatar.OwnClip.poster.collectAsState()
    val ownMoods by io.github.nanomuse.avatar.OwnClip.moods.collectAsState()
    val ownMoodPosters by io.github.nanomuse.avatar.OwnClip.moodPosters.collectAsState()
    val density = LocalDensity.current
    val motion = rememberInfiniteTransition(label = "avatarMotion")

    // One posture drives breath, sink, lean and size together, so the six moods that share
    // the SLEEPY face still read as six different moods.
    val posture = remember(companionMood, companionDetail, companionIntensity) {
        companionPosture(companionMood, companionDetail, companionIntensity)
    }

    val breathDuration = if (mood == AgentMood.WORKING) 700 else posture.breathMs
    val breath by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = breathDuration, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // Waiting or ambient: a gentle side-to-side tilt
    val tilt by motion.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tilt",
    )
    // Happy: one springy pop on arrival. Error: one short shake.
    val pop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(mood) {
        when (mood) {
            AgentMood.HAPPY -> {
                pop.snapTo(1f)
                pop.animateTo(1.14f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
            }
            AgentMood.ERROR -> {
                shake.snapTo(0f)
                shake.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 420
                        -1f at 60 using LinearEasing
                        1f at 140 using LinearEasing
                        -0.6f at 220 using LinearEasing
                        0.6f at 300 using LinearEasing
                        0f at 420
                    },
                )
            }
            else -> { pop.snapTo(1f); shake.snapTo(0f) }
        }
    }

    // Dynamic micro-animation bursts upon companion mood changes
    val companionPop = remember { Animatable(1f) }
    val companionTilt = remember { Animatable(0f) }
    val companionBob = remember { Animatable(0f) }
    // Keyed on the concrete mood as well: 平静 -> 疲惫 happens inside SLEEPY, so a family-only
    // key would leave the face unchanged and skip the burst entirely.
    LaunchedEffect(companionMood, companionDetail) {
        if (companionMood == null) return@LaunchedEffect
        // A settle for every mood, including the ones inside a shared family. It is what makes
        // a change visible while the face is otherwise still.
        if (posture.entryDropDp > 0f) {
            companionBob.snapTo(with(density) { (-2f).dp.toPx() })
            companionBob.animateTo(with(density) { posture.entryDropDp.dp.toPx() }, tween(320, easing = FastOutSlowInEasing))
            companionBob.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
        }
        when (companionMood) {
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.HYPER -> {
                // High frequency hyper hops
                companionPop.snapTo(1f)
                companionBob.snapTo(0f)
                companionBob.animateTo(with(density) { (-6f).dp.toPx() }, spring(dampingRatio = Spring.DampingRatioHighBouncy))
                companionPop.animateTo(1.22f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
                companionBob.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionPop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.EXCITED -> {
                companionPop.snapTo(1f)
                companionBob.snapTo(0f)
                companionBob.animateTo(with(density) { (-4f).dp.toPx() }, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                companionPop.animateTo(1.18f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                companionBob.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                companionPop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.HAPPY -> {
                companionPop.snapTo(1f)
                companionBob.snapTo(0f)
                companionBob.animateTo(with(density) { (-2.5f).dp.toPx() }, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionPop.animateTo(1.12f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                companionBob.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                companionPop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.TOUCHED -> {
                // Warm heartfelt pulse
                companionPop.snapTo(1f)
                companionPop.animateTo(1.16f, tween(180, easing = FastOutSlowInEasing))
                companionPop.animateTo(1.04f, tween(120, easing = FastOutSlowInEasing))
                companionPop.animateTo(1.12f, tween(160, easing = FastOutSlowInEasing))
                companionPop.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SHY -> {
                // Shy blush bounce & side tilt
                companionTilt.snapTo(0f)
                companionPop.snapTo(1f)
                companionTilt.animateTo(-6f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionPop.animateTo(1.08f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionTilt.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
                companionPop.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CURIOUS -> {
                // Inquisitive perk up: head tilts, ears perk up
                companionBob.snapTo(0f)
                companionTilt.snapTo(0f)
                companionBob.animateTo(with(density) { (-4f).dp.toPx() }, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionTilt.animateTo(6f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionBob.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                companionTilt.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SULK_JEALOUS -> {
                // Sulking / jealous: turns head away with slight tremor
                companionTilt.snapTo(0f)
                companionTilt.animateTo(-10f, tween(260, easing = FastOutSlowInEasing))
                companionTilt.animateTo(-7f, tween(180, easing = LinearEasing))
                companionTilt.animateTo(-9f, tween(180, easing = LinearEasing))
                companionTilt.animateTo(-5f, tween(400, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CRACKED_HURT -> {
                // Cracked / hurt / aggrieved: shrinks inward, head droops
                companionPop.snapTo(1f)
                companionBob.snapTo(0f)
                companionPop.animateTo(0.91f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                companionBob.animateTo(with(density) { 4f.dp.toPx() }, tween(280, easing = FastOutSlowInEasing))
                companionPop.animateTo(0.96f, tween(350, easing = FastOutSlowInEasing))
                companionBob.animateTo(with(density) { 1.5f.dp.toPx() }, tween(400, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.SLEEPY -> {
                // Sleepy slow nod down
                companionBob.snapTo(0f)
                companionBob.animateTo(with(density) { 3.5f.dp.toPx() }, tween(700, easing = FastOutSlowInEasing))
                companionBob.animateTo(0f, tween(1000, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.CARING -> {
                // Gentle caring tilt & soft lean
                companionTilt.snapTo(0f)
                companionTilt.animateTo(4.5f, tween(350, easing = FastOutSlowInEasing))
                companionTilt.animateTo(0f, tween(550, easing = FastOutSlowInEasing))
            }
            io.github.nanomuse.companion.CompanionEmotion.PrimaryMood.LONGING -> {
                // Soft longing stretch
                companionPop.snapTo(1f)
                companionPop.animateTo(1.08f, tween(500, easing = FastOutSlowInEasing))
                companionPop.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            }
        }
    }

    // The breath rides on top of the mood's own size, so a tired face is smaller even at the
    // still point of the breath and the eye can tell it from a focused one.
    val continuousScale = posture.scale + 0.02f * breath
    val scale = when {
        mood == AgentMood.WORKING -> 1f + 0.045f * breath
        mood == AgentMood.HAPPY -> pop.value
        else -> companionPop.value * continuousScale
    }
    val bobPx = companionBob.value + with(density) { posture.bobDp.dp.toPx() } * breath
    val shakePx = with(density) { 3.dp.toPx() } * shake.value
    val rotation = companionTilt.value + when {
        mood == AgentMood.WAITING -> 4f * tilt
        // Sway around the mood's own resting angle rather than around zero.
        else -> posture.tiltDeg + posture.tiltDeg * 0.25f * tilt
    }

    val base = modifier
        .size(size)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = bobPx
            translationX = shakePx
            rotationZ = rotation
        }
        .clip(CircleShape)
    val own = ownClip
    androidx.compose.foundation.layout.Box(if (onClick != null) base.clickable(onClick = onClick) else base) {
        Crossfade(
            targetState = mood,
            animationSpec = tween(durationMillis = 260),
            label = "avatarMood",
            modifier = Modifier.size(size),
        ) { current ->
            val set = custom
            val moodOwn = ownMoods[current]
            val poster = ownMoodPosters[current] ?: ownPoster
            if (set != null) {
                Image(
                    bitmap = set.forMood(current),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
                // The clip for this mood, when the video model has made one: it plays over the
                // still and shows only once its first frame is on screen, so nothing flickers.
                // A clip of the person's own stands in for all of them (below, outside the fade).
                val clip = if (own == null && moodOwn == null) clips[current] else null
                if (clip != null) {
                    androidx.compose.runtime.key(clip.path) {
                        LoopingClip(key = clip.path, modifier = Modifier.size(size)) { it.setDataSource(clip.path) }
                    }
                }
            } else if ((moodOwn != null || own != null) && poster != null) {
                // No studio face: the clip's first frame is the still, for every mood.
                Image(
                    bitmap = poster,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            } else {
                Image(
                    painter = painterResource(current.drawable),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
                // The built-in character ships with its clips, so it moves without a video model.
                val res = if (own == null && moodOwn == null) current.motion else null
                if (res != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.runtime.key(res) {
                        LoopingClip(key = "raw:$res", modifier = Modifier.size(size)) { player ->
                            context.resources.openRawResourceFd(res).use { fd -> player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                        }
                    }
                }
            }
            if (moodOwn != null) {
                androidx.compose.runtime.key(moodOwn.key) {
                    LoopingClip(key = moodOwn.key, modifier = Modifier.size(size)) { it.setDataSource(moodOwn.file.path) }
                }
            }
        }
        // The person's own clip (#324): one clip for every mood, so it sits outside the mood
        // fade and keeps playing while the still under it changes.
        if (own != null && ownMoods[mood] == null) {
            androidx.compose.runtime.key(own.key) {
                LoopingClip(key = own.key, modifier = Modifier.size(size)) { it.setDataSource(own.file.path) }
            }
        }
    }
}

/**
 * The face on its pale disc, the way both headers show it. Every face — the built-in dragon
 * too — brings its own white background and fills the disc.
 */
@Composable
fun AgentAvatarDisc(
    mood: AgentMood,
    discSize: Dp,
    modifier: Modifier = Modifier,
    companionMood: io.github.nanomuse.companion.CompanionEmotion.PrimaryMood? = null,
    companionDetail: io.github.nanomuse.companion.CompanionMoodStore.Mood? = null,
    companionIntensity: String = "normal",
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(discSize)
            .clip(CircleShape)
            .background(io.github.nanomuse.ui.home.avatarDiscColor()),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        AgentAvatar(
            mood = mood,
            size = discSize,
            companionMood = companionMood,
            companionDetail = companionDetail,
            companionIntensity = companionIntensity,
            contentDescription = contentDescription,
            onClick = onClick,
        )
    }
}

/**
 * A muted, looping MP4 on a [android.view.TextureView] — the platform player, no extra
 * dependency; the clips are small H.264 files the video model made. Transparent until the first
 * frame renders, paused while the app is in the background, released with the surface.
 */
@Composable
internal fun LoopingClip(key: String, modifier: Modifier = Modifier, open: (android.media.MediaPlayer) -> Unit) {
    var ready by remember(key) { mutableStateOf(false) }
    val player = remember(key) { android.media.MediaPlayer() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(key) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> if (player.isPlaying) player.pause()
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (ready && !player.isPlaying) player.start()
                    else -> {}
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { player.release() }
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            player.reset()
                            open(player)
                            player.setSurface(android.view.Surface(surface))
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                            player.setOnPreparedListener { it.start() }
                            player.setOnInfoListener { _, what, _ ->
                                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) ready = true
                                false
                            }
                            player.setOnErrorListener { _, _, _ -> true }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        return true
                    }
                }
            }
        },
        modifier = modifier.graphicsLayer { alpha = if (ready) 1f else 0f },
    )
}
