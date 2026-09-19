package com.example.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * A custom video display view that uses SurfaceView and Android MediaPlayer
 * with full support for custom [MediaDataSource] (read-through seekable PTP streaming).
 * Preserves the exact video aspect ratio on TV and mobile displays.
 */
class DirectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var mediaPlayer: MediaPlayer? = null
    private var companionAudioPlayer: MediaPlayer? = null
    private var currentDataSource: MediaDataSource? = null
    private var isPrepared = false
    private var isSurfaceCreated = false

    private var videoWidth = 0
    private var videoHeight = 0

    private var onPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var onErrorListener: MediaPlayer.OnErrorListener? = null
    private var onCompletionListener: MediaPlayer.OnCompletionListener? = null
    private var onInfoListener: MediaPlayer.OnInfoListener? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        try {
            mediaPlayer?.setDisplay(holder)
        } catch (e: Exception) {
            Log.w("DirectVideoView", "Error setting display on surfaceCreated", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handled by video size change listener
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        try {
            mediaPlayer?.setDisplay(null)
        } catch (_: Exception) {}
    }

    fun setDataSource(dataSource: MediaDataSource) {
        stopPlayback()
        this.currentDataSource = dataSource

        try {
            val mp = MediaPlayer()
            this.mediaPlayer = mp
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setScreenOnWhilePlaying(true)

            if (isSurfaceCreated && holder.surface.isValid) {
                mp.setDisplay(holder)
            }

            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    holder.setFixedSize(width, height)
                    requestLayout()
                }
            }

            mp.setOnPreparedListener { player ->
                isPrepared = true
                val w = player.videoWidth
                val h = player.videoHeight
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    holder.setFixedSize(w, h)
                    requestLayout()
                }
                onPreparedListener?.onPrepared(player)
            }

            mp.setOnErrorListener { player, what, extra ->
                isPrepared = false
                onErrorListener?.onError(player, what, extra) ?: false
            }

            mp.setOnCompletionListener { player ->
                onCompletionListener?.onCompletion(player)
            }

            mp.setOnInfoListener { player, what, extra ->
                onInfoListener?.onInfo(player, what, extra) ?: false
            }

            mp.setDataSource(dataSource)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("DirectVideoView", "Failed to setDataSource", e)
            onErrorListener?.onError(mediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, -1)
        }
    }

    fun setDataSource(filePath: String) {
        stopPlayback()

        try {
            val mp = MediaPlayer()
            this.mediaPlayer = mp
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setScreenOnWhilePlaying(true)

            if (isSurfaceCreated && holder.surface.isValid) {
                mp.setDisplay(holder)
            }

            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    holder.setFixedSize(width, height)
                    requestLayout()
                }
            }

            mp.setOnPreparedListener { player ->
                isPrepared = true
                val w = player.videoWidth
                val h = player.videoHeight
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    holder.setFixedSize(w, h)
                    requestLayout()
                }
                onPreparedListener?.onPrepared(player)
            }

            mp.setOnErrorListener { player, what, extra ->
                isPrepared = false
                onErrorListener?.onError(player, what, extra) ?: false
            }

            mp.setOnCompletionListener { player ->
                onCompletionListener?.onCompletion(player)
            }

            mp.setOnInfoListener { player, what, extra ->
                onInfoListener?.onInfo(player, what, extra) ?: false
            }

            mp.setDataSource(filePath)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("DirectVideoView", "Failed to setDataSource(filePath)", e)
            onErrorListener?.onError(mediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, -1)
        }
    }

    fun setCompanionAudio(audioFile: java.io.File, onPrepared: (() -> Unit)? = null) {
        clearCompanionAudio()
        try {
            val ap = MediaPlayer()
            ap.setAudioStreamType(AudioManager.STREAM_MUSIC)
            ap.setDataSource(audioFile.absolutePath)
            ap.setOnPreparedListener { player ->
                // Mute primary video player audio since companion audio provides decoded track
                try {
                    mediaPlayer?.setVolume(0f, 0f)
                } catch (_: Exception) {}
                val currentPos = mediaPlayer?.currentPosition ?: 0
                if (currentPos > 0) {
                    player.seekTo(currentPos)
                }
                if (isPlaying) {
                    player.start()
                }
                onPrepared?.invoke()
            }
            ap.setOnErrorListener { _, what, extra ->
                Log.w("DirectVideoView", "Companion audio player error: what=$what extra=$extra")
                true
            }
            ap.prepareAsync()
            companionAudioPlayer = ap
        } catch (e: Exception) {
            Log.e("DirectVideoView", "Failed to setCompanionAudio", e)
        }
    }

    fun clearCompanionAudio() {
        try {
            companionAudioPlayer?.let { ap ->
                if (ap.isPlaying) ap.stop()
                ap.reset()
                ap.release()
            }
        } catch (_: Exception) {}
        companionAudioPlayer = null
        try {
            mediaPlayer?.setVolume(1f, 1f)
        } catch (_: Exception) {}
    }

    fun start() {
        try {
            if (isPrepared) {
                mediaPlayer?.start()
                companionAudioPlayer?.start()
            }
        } catch (e: Exception) {
            Log.w("DirectVideoView", "start() failed", e)
        }
    }

    fun pause() {
        try {
            if (isPrepared && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
            companionAudioPlayer?.let { ap ->
                if (ap.isPlaying) ap.pause()
            }
        } catch (e: Exception) {
            Log.w("DirectVideoView", "pause() failed", e)
        }
    }

    fun seekTo(msec: Int) {
        try {
            if (isPrepared) {
                mediaPlayer?.seekTo(msec)
            }
            companionAudioPlayer?.seekTo(msec)
        } catch (e: Exception) {
            Log.w("DirectVideoView", "seekTo() failed", e)
        }
    }

    val isPlaying: Boolean
        get() = try {
            isPrepared && mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    val duration: Int
        get() = try {
            if (isPrepared) mediaPlayer?.duration ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    val currentPosition: Int
        get() = try {
            if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) {
        onPreparedListener = l
    }

    fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) {
        onErrorListener = l
    }

    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) {
        onCompletionListener = l
    }

    fun setOnInfoListener(l: MediaPlayer.OnInfoListener?) {
        onInfoListener = l
    }

    fun stopPlayback() {
        clearCompanionAudio()
        try {
            mediaPlayer?.let { mp ->
                mp.setOnPreparedListener(null)
                mp.setOnErrorListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnInfoListener(null)
                mp.setOnVideoSizeChangedListener(null)
                try {
                    if (mp.isPlaying) {
                        mp.stop()
                    }
                } catch (_: Exception) {}
                try { mp.reset() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("DirectVideoView", "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
        isPrepared = false
        videoWidth = 0
        videoHeight = 0

        try {
            currentDataSource?.close()
        } catch (_: Exception) {}
        currentDataSource = null
    }

    fun setPlaybackSpeed(speed: Float) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                }
                companionAudioPlayer?.let { ap ->
                    val params = ap.playbackParams
                    params.speed = speed
                    ap.playbackParams = params
                }
            }
        } catch (e: Exception) {
            Log.w("DirectVideoView", "setPlaybackSpeed failed", e)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)
        if (videoWidth > 0 && videoHeight > 0) {
            val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                width = widthSpecSize
                height = heightSpecSize
                if (videoWidth * height < width * videoHeight) {
                    width = height * videoWidth / videoHeight
                } else if (videoWidth * height > width * videoHeight) {
                    height = width * videoHeight / videoWidth
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                width = widthSpecSize
                height = width * videoHeight / videoWidth
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                height = heightSpecSize
                width = height * videoWidth / videoHeight
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                }
            } else {
                width = videoWidth
                height = videoHeight
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                    width = height * videoWidth / videoHeight
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                    height = width * videoHeight / videoWidth
                }
            }
        }
        setMeasuredDimension(width, height)
    }
}
