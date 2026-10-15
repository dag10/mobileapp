package io.rebble.libpebblecommon.findphone

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.outputVolume
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UISlider
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The playback category ignores the Ring/Silent switch and Focus. Media volume has no public API,
 * so it's raised through MPVolumeView's slider and put back afterwards.
 */
class IosPhoneRinger : PhoneRinger {
    private val logger = Logger.withTag("IosPhoneRinger")
    private val wav: NSData by lazy {
        val bytes = FindPhoneTone.wav()
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
    private val volumeView by lazy { MPVolumeView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) }
    private var player: AVAudioPlayer? = null
    private var volumeToRestore: Float? = null
    private var generation = 0

    override fun ring() {
        dispatch_async(dispatch_get_main_queue()) { start() }
    }

    private fun start() {
        player?.stop()
        val session = AVAudioSession.sharedInstance()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            // Mixable, so it can be activated while the app is in the background.
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionDuckOthers,
                error = err.ptr,
            )
            if (!session.setActive(true, error = err.ptr)) {
                logger.e { "Couldn't activate the audio session: ${err.value?.localizedDescription}" }
                return
            }
            val newPlayer = AVAudioPlayer(data = wav, error = err.ptr)
            if (err.value != null) {
                logger.e { "Couldn't create the player: ${err.value?.localizedDescription}" }
                return
            }
            player = newPlayer
        }
        if (volumeToRestore == null) volumeToRestore = session.outputVolume
        // The slider only appears once the view has been laid out.
        after(50.milliseconds) { setSystemVolume(1f) }
        player?.play()

        val thisGeneration = ++generation
        after(FindPhoneTone.DURATION + 300.milliseconds) {
            if (thisGeneration == generation) finish()
        }
    }

    private fun finish() {
        player?.stop()
        player = null
        volumeToRestore?.let { setSystemVolume(it) }
        volumeToRestore = null
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            AVAudioSession.sharedInstance().setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = err.ptr,
            )
        }
    }

    private fun setSystemVolume(volume: Float) {
        val slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
        if (slider == null) {
            logger.w { "No volume slider; leaving the volume as it is" }
            return
        }
        slider.setValue(volume, animated = false)
    }

    private fun after(delay: Duration, block: () -> Unit) {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delay.inWholeNanoseconds),
            dispatch_get_main_queue(),
            block,
        )
    }
}
