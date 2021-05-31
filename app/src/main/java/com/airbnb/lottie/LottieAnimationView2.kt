package com.airbnb.lottie

import android.animation.Animator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.annotation.FloatRange
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import androidx.appcompat.widget.AppCompatImageView
import com.airbnb.lottie.LottieAnimationView2
import com.airbnb.lottie.LottieDrawable.RepeatMode
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.parser.moshi.JsonReader
import com.airbnb.lottie.value.LottieFrameInfo
import com.airbnb.lottie.value.LottieValueCallback
import com.airbnb.lottie.value.SimpleLottieValueCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okio.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 */
class LottieAnimationView2 : AppCompatImageView {
    interface OnResultListener {
        fun onResult(ret: Boolean, response: Any?)
    }

    private val loadedListener =
        LottieListener<LottieComposition> { composition -> setComposition(composition) }
    private val failureListener: LottieListener<Throwable> = LottieListener { }
    private val lottieDrawable = LottieDrawable()
    private var isInitialized = false
    private var animationName: String? = null

    @RawRes
    private var animationResId = 0
    private var wasAnimatingWhenNotShown = false
    private var wasAnimatingWhenDetached = false
    private var autoPlay = false
    private var renderMode = RenderMode.AUTOMATIC
    private val lottieOnCompositionLoadedListeners: MutableSet<LottieOnCompositionLoadedListener> =
        HashSet()
    private var compositionTask: LottieTask<LottieComposition>? = null

    /** Can be null because it is created async  */
    var composition: LottieComposition? = null
        private set

    constructor(context: Context?) : super(context!!) {
        init(null)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(
            context!!, attrs
    ) {
        init(attrs)
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
            context!!, attrs, defStyleAttr
    ) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LottieAnimationView)
        if (!isInEditMode) {
            val hasRawRes = ta.hasValue(R.styleable.LottieAnimationView_lottie_rawRes)
            val hasFileName = ta.hasValue(R.styleable.LottieAnimationView_lottie_fileName)
            val hasUrl = ta.hasValue(R.styleable.LottieAnimationView_lottie_url)
            require(!(hasRawRes && hasFileName)) {
                "lottie_rawRes and lottie_fileName cannot be used at " +
                        "the same time. Please use only one at once."
            }
            if (hasRawRes) {
                val rawResId = ta.getResourceId(R.styleable.LottieAnimationView_lottie_rawRes, 0)
                if (rawResId != 0) {
                    setAnimation(rawResId)
                }
            } else if (hasFileName) {
                val fileName = ta.getString(R.styleable.LottieAnimationView_lottie_fileName)
                fileName?.let { setAnimation(it) }
            } else if (hasUrl) {
                val url = ta.getString(R.styleable.LottieAnimationView_lottie_url)
                url?.let { setAnimationFromUrl(it) }
            }
        }
        if (ta.getBoolean(R.styleable.LottieAnimationView_lottie_autoPlay, false)) {
            wasAnimatingWhenDetached = true
            autoPlay = true
        }
        if (ta.getBoolean(R.styleable.LottieAnimationView_lottie_loop, false)) {
            lottieDrawable.repeatCount = LottieDrawable.INFINITE
        }
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_repeatMode)) {
            repeatMode = ta.getInt(
                    R.styleable.LottieAnimationView_lottie_repeatMode,
                    LottieDrawable.RESTART
            )
        }
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_repeatCount)) {
            repeatCount = ta.getInt(
                    R.styleable.LottieAnimationView_lottie_repeatCount,
                    LottieDrawable.INFINITE
            )
        }
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_speed)) {
            speed = ta.getFloat(R.styleable.LottieAnimationView_lottie_speed, 1f)
        }
        imageAssetsFolder = ta.getString(R.styleable.LottieAnimationView_lottie_imageAssetsFolder)
        progress = ta.getFloat(R.styleable.LottieAnimationView_lottie_progress, 0f)
        enableMergePathsForKitKatAndAbove(
                ta.getBoolean(
                        R.styleable.LottieAnimationView_lottie_enableMergePathsForKitKatAndAbove, false
                )
        )
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_colorFilter)) {
            val filter = SimpleColorFilter(
                    ta.getColor(R.styleable.LottieAnimationView_lottie_colorFilter, Color.TRANSPARENT)
            )
            val keyPath = KeyPath("**")
            val callback = LottieValueCallback<ColorFilter>(filter)
            addValueCallback(keyPath, LottieProperty.COLOR_FILTER, callback)
        }
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_scale)) {
            lottieDrawable.scale =
                ta.getFloat(R.styleable.LottieAnimationView_lottie_scale, 1f)
        }
        if (ta.hasValue(R.styleable.LottieAnimationView_lottie_renderMode)) {
            var renderModeOrdinal = ta.getInt(
                    R.styleable.LottieAnimationView_lottie_renderMode,
                    RenderMode.AUTOMATIC.ordinal
            )
            if (renderModeOrdinal >= RenderMode.values().size) {
                renderModeOrdinal = RenderMode.AUTOMATIC.ordinal
            }
            renderMode = RenderMode.values()[renderModeOrdinal]
        }
        ta.recycle()
        lottieDrawable.setSystemAnimationsAreEnabled(getAnimationScale(context) != 0f)
        enableOrDisableHardwareLayer()
        isInitialized = true
    }

    override fun setImageResource(resId: Int) {
        cancelLoaderTask()
        super.setImageResource(resId)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        cancelLoaderTask()
        super.setImageDrawable(drawable)
    }

    override fun setImageBitmap(bm: Bitmap) {
        cancelLoaderTask()
        super.setImageBitmap(bm)
    }

    override fun invalidateDrawable(dr: Drawable) {
        if (drawable === lottieDrawable) {
            // We always want to invalidate the root drawable so it redraws the whole drawable.
            // Eventually it would be great to be able to invalidate just the changed region.
            super.invalidateDrawable(lottieDrawable)
        } else {
            // Otherwise work as regular ImageView
            super.invalidateDrawable(dr)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val ss = SavedState(superState)
        ss.animationName = animationName
        ss.animationResId = animationResId
        ss.progress = lottieDrawable.progress
        ss.isAnimating = lottieDrawable.isAnimating
        ss.imageAssetsFolder = lottieDrawable.imageAssetsFolder
        ss.repeatMode = lottieDrawable.repeatMode
        ss.repeatCount = lottieDrawable.repeatCount
        return ss
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        val ss = state
        super.onRestoreInstanceState(ss.superState)
        animationName = ss.animationName
        if (!TextUtils.isEmpty(animationName)) {
            setAnimation(animationName)
        }
        animationResId = ss.animationResId
        if (animationResId != 0) {
            setAnimation(animationResId)
        }
        progress = ss.progress
        if (ss.isAnimating) {
            playAnimation()
        }
        lottieDrawable.setImagesAssetsFolder(ss.imageAssetsFolder)
        repeatMode = ss.repeatMode
        repeatCount = ss.repeatCount
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        // This can happen on older versions of Android because onVisibilityChanged gets called from the
        // constructor of View so this will get called before lottieDrawable gets initialized.
        // https://github.com/airbnb/lottie-android/issues/1143
        // A simple null check on lottieDrawable would not work because when using Proguard optimization, a
        // null check on a final field gets removed. As "usually" final fields cannot be null.
        // However because this is called by super (View) before the initializer of the LottieAnimationView
        // is called, it actually can be null here.
        // Working around this by using a non final boolean that is set to true after the class initializer
        // has run.
        if (!isInitialized) {
            return
        }
        if (isShown) {
            if (wasAnimatingWhenNotShown) {
                resumeAnimation()
                wasAnimatingWhenNotShown = false
            }
        } else {
            if (isAnimating) {
                pauseAnimation()
                wasAnimatingWhenNotShown = true
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (autoPlay || wasAnimatingWhenDetached) {
            playAnimation()
            // Autoplay from xml should only apply once.
            autoPlay = false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // This is needed to mimic newer platform behavior.
            // https://stackoverflow.com/a/53625860/715633
            onVisibilityChanged(this, visibility)
        }
    }

    override fun onDetachedFromWindow() {
        if (isAnimating) {
            cancelAnimation()
            wasAnimatingWhenDetached = true
        }
        super.onDetachedFromWindow()
    }

    /**
     * Enable this to get merge path support for devices running KitKat (19) and above.
     *
     * Merge paths currently don't work if the the operand shape is entirely contained within the
     * first shape. If you need to cut out one shape from another shape, use an even-odd fill type
     * instead of using merge paths.
     */
    fun enableMergePathsForKitKatAndAbove(enable: Boolean) {
        lottieDrawable.enableMergePathsForKitKatAndAbove(enable)
    }

    /**
     * Returns whether merge paths are enabled for KitKat and above.
     */
    val isMergePathsEnabledForKitKatAndAbove: Boolean
        get() = lottieDrawable.isMergePathsEnabledForKitKatAndAbove

    /**
     * Sets the animation from a file in the raw directory.
     * This will load and deserialize the file asynchronously.
     */
    fun setAnimation(@RawRes rawRes: Int) {
        animationResId = rawRes
        animationName = null
        setCompositionTask(LottieCompositionFactory.fromRawRes(context, rawRes))
    }

    fun setAnimation(assetName: String?) {
        animationName = assetName
        animationResId = 0
        setCompositionTask(LottieCompositionFactory.fromAsset(context, assetName))
    }

    fun setAnimationFromLocal(filePath: String, fileUrl: String, loadListener: OnResultListener) {
        val file = File(filePath)
        if (file.exists()) {
            loadFromLocal(file, loadListener)
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                if (downloadAsync(filePath, fileUrl)) {
                    loadFromLocal(file, loadListener)
                }
            }
        }
    }


    /**
     * @see .setAnimationFromJson
     */
    @Deprecated("")
    fun setAnimationFromJson(jsonString: String) {
        setAnimationFromJson(jsonString, null)
    }

    /**
     * Sets the animation from json string. This is the ideal API to use when loading an animation
     * over the network because you can use the raw response body here and a conversion to a
     * JSONObject never has to be done.
     */
    @SuppressLint("RestrictedApi")
    fun setAnimationFromJson(jsonString: String, cacheKey: String?) {
        setAnimation(
                JsonReader.of(
                        ByteArrayInputStream(jsonString.toByteArray()).source().buffer()
                ), cacheKey
        )
    }

    /**
     * Sets the animation from a JSONReader.
     * This will load and deserialize the file asynchronously.
     *
     *
     * This is particularly useful for animations loaded from the network. You can fetch the
     * bodymovin json from the network and pass it directly here.
     */
    fun setAnimation(reader: JsonReader?, cacheKey: String?) {
        setCompositionTask(LottieCompositionFactory.fromJsonReader(reader, cacheKey))
    }

    /**
     * Load a lottie animation from a url. The url can be a json file or a zip file. Use a zip file if you have images. Simply zip them together and lottie
     * will unzip and link the images automatically.
     *
     * Under the hood, Lottie uses Java HttpURLConnection because it doesn't require any transitive networking dependencies. It will download the file
     * to the application cache under a temporary name. If the file successfully parses to a composition, it will rename the temporary file to one that
     * can be accessed immediately for subsequent requests. If the file does not parse to a composition, the temporary file will be deleted.
     */
    fun setAnimationFromUrl(url: String?) {
        setCompositionTask(LottieCompositionFactory.fromUrl(context, url))
    }


    /**
     * Download resource from url and save to file
     */
    suspend fun downloadAsync(filePath: String, fileUrl: String): Boolean = coroutineScope {
        if (L.DBG) {
            Log.v(TAG, "downloadAsync : $filePath, $fileUrl")
        }
        var inputStream: BufferedInputStream? = null
        var fis: FileOutputStream? = null
        try {
            var connection = URL(fileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            if (L.DBG) {
                Log.v(TAG, "  - responseCode $responseCode")
            }
            if (responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = BufferedInputStream(connection.inputStream)
                fis = FileOutputStream(File(filePath))
                val BUFFER_SIZE = 4096
                var bytesRead: Int = -1
                var totalBytes = 0
                val buffer = ByteArray(BUFFER_SIZE)
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    fis.write(buffer, 0, bytesRead)
                }
                return@coroutineScope true
            }
        } catch (e: Exception) {
            if (L.DBG) {
                Log.v(TAG, "downloadAsync exception : " + e.message)
            }
            e.printStackTrace()
            return@coroutineScope false
        } finally {
            inputStream?.close()
            fis?.close()
        }
        return@coroutineScope true
    }

    private fun loadFromLocal(file: File, loadListener: OnResultListener) {
        animationName = file.name
        animationResId = 0
        try {
            setCompositionTask(
                LottieCompositionFactory.fromJsonInputStream(
                    FileInputStream(file),
                    file.name
                ), { composition ->
                    setComposition(composition)
                    loadListener.onResult(true, null)
                }) { result -> loadListener.onResult(false, result.toString()) }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun setCompositionTask(compositionTask: LottieTask<LottieComposition>) {
        clearComposition()
        cancelLoaderTask()
        this.compositionTask = compositionTask
            .addListener(loadedListener)
            .addFailureListener(failureListener)
    }

    private fun setCompositionTask(
            compositionTask: LottieTask<LottieComposition>,
            lottieLoadListener: LottieListener<LottieComposition>,
            lottieFailureListener: LottieListener<Throwable>
    ) {
        clearComposition()
        cancelLoaderTask()
        this.compositionTask = compositionTask
            .addListener(lottieLoadListener)
            .addFailureListener(lottieFailureListener)
    }

    private fun cancelLoaderTask() {
        if (compositionTask != null) {
            compositionTask!!.removeListener(loadedListener)
            compositionTask!!.removeFailureListener(failureListener)
        }
    }

    /**
     * Sets a composition.
     * You can set a default cache strategy if this view was inflated with xml by
     */
    fun setComposition(composition: LottieComposition) {
        if (L.DBG) {
            Log.v(TAG, "Set Composition \n$composition")
        }
        lottieDrawable.callback = this
        this.composition = composition
        val isNewComposition = lottieDrawable.setComposition(composition)
        enableOrDisableHardwareLayer()
        if (drawable === lottieDrawable && !isNewComposition) {
            // We can avoid re-setting the drawable, and invalidating the view, since the composition
            // hasn't changed.
            return
        }

        // If you set a different composition on the view, the bounds will not update unless
        // the drawable is different than the original.
        setImageDrawable(null)
        setImageDrawable(lottieDrawable)

        // This is needed to makes sure that the animation is properly played/paused for the current visibility state.
        // It is possible that the drawable had a lazy composition task to play the animation but this view subsequently
        // became invisible. Comment this out and run the espresso tests to see a failing test.
        onVisibilityChanged(this, visibility)
        requestLayout()
        for (lottieOnCompositionLoadedListener in lottieOnCompositionLoadedListeners) {
            lottieOnCompositionLoadedListener.onCompositionLoaded(composition)
        }
    }

    /**
     * Returns whether or not any layers in this composition has masks.
     */
    fun hasMasks(): Boolean {
        return lottieDrawable.hasMasks()
    }

    /**
     * Returns whether or not any layers in this composition has a matte layer.
     */
    fun hasMatte(): Boolean {
        return lottieDrawable.hasMatte()
    }

    /**
     * Plays the animation from the beginning. If speed is < 0, it will start at the end
     * and play towards the beginning
     */
    @MainThread
    fun playAnimation() {
        if (isShown) {
            lottieDrawable.playAnimation()
            enableOrDisableHardwareLayer()
        } else {
            wasAnimatingWhenNotShown = true
        }
    }

    /**
     * Continues playing the animation from its current position. If speed < 0, it will play backwards
     * from the current position.
     */
    @MainThread
    fun resumeAnimation() {
        if (isShown) {
            lottieDrawable.resumeAnimation()
            enableOrDisableHardwareLayer()
        } else {
            wasAnimatingWhenNotShown = true
        }
    }

    /**
     * Sets the minimum frame that the animation will start from when playing or looping.
     */
    fun setMinFrame(startFrame: Int) {
        lottieDrawable.setMinFrame(startFrame)
    }

    /**
     * Returns the minimum frame set by [.setMinFrame] or [.setMinProgress]
     */
    val minFrame: Float
        get() = lottieDrawable.minFrame

    /**
     * Sets the minimum progress that the animation will start from when playing or looping.
     */
    fun setMinProgress(startProgress: Float) {
        lottieDrawable.setMinProgress(startProgress)
    }

    /**
     * Sets the maximum frame that the animation will end at when playing or looping.
     */
    fun setMaxFrame(endFrame: Int) {
        lottieDrawable.setMaxFrame(endFrame)
    }

    /**
     * Returns the maximum frame set by [.setMaxFrame] or [.setMaxProgress]
     */
    val maxFrame: Float
        get() = lottieDrawable.maxFrame

    /**
     * Sets the maximum progress that the animation will end at when playing or looping.
     */
    fun setMaxProgress(@FloatRange(from = 0.0, to = 1.0) endProgress: Float) {
        lottieDrawable.setMaxProgress(endProgress)
    }

    /**
     * Sets the minimum frame to the start time of the specified marker.
     * @throws IllegalArgumentException if the marker is not found.
     */
    fun setMinFrame(markerName: String?) {
        lottieDrawable.setMinFrame(markerName)
    }

    /**
     * Sets the maximum frame to the start time + duration of the specified marker.
     * @throws IllegalArgumentException if the marker is not found.
     */
    fun setMaxFrame(markerName: String?) {
        lottieDrawable.setMaxFrame(markerName)
    }

    /**
     * Sets the minimum and maximum frame to the start time and start time + duration
     * of the specified marker.
     * @throws IllegalArgumentException if the marker is not found.
     */
    fun setMinAndMaxFrame(markerName: String?) {
        lottieDrawable.setMinAndMaxFrame(markerName)
    }

    /**
     * @see .setMinFrame
     * @see .setMaxFrame
     */
    fun setMinAndMaxFrame(minFrame: Int, maxFrame: Int) {
        lottieDrawable.setMinAndMaxFrame(minFrame, maxFrame)
    }

    /**
     * @see .setMinProgress
     * @see .setMaxProgress
     */
    fun setMinAndMaxProgress(
            @FloatRange(from = 0.0, to = 1.0) minProgress: Float,
            @FloatRange(from = 0.0, to = 1.0) maxProgress: Float
    ) {
        lottieDrawable.setMinAndMaxProgress(minProgress, maxProgress)
    }

    /**
     * Reverses the current animation speed. This does NOT play the animation.
     * @see .setSpeed
     * @see .playAnimation
     * @see .resumeAnimation
     */
    fun reverseAnimationSpeed() {
        lottieDrawable.reverseAnimationSpeed()
    }
    /**
     * Returns the current playback speed. This will be < 0 if the animation is playing backwards.
     */
    /**
     * Sets the playback speed. If speed < 0, the animation will play backwards.
     */
    var speed: Float
        get() = lottieDrawable.speed
        set(speed) {
            lottieDrawable.speed = speed
        }

    fun addAnimatorUpdateListener(updateListener: AnimatorUpdateListener?) {
        lottieDrawable.addAnimatorUpdateListener(updateListener)
    }

    fun removeUpdateListener(updateListener: AnimatorUpdateListener?) {
        lottieDrawable.removeAnimatorUpdateListener(updateListener)
    }

    fun removeAllUpdateListeners() {
        lottieDrawable.removeAllUpdateListeners()
    }

    fun addAnimatorListener(listener: Animator.AnimatorListener?) {
        lottieDrawable.addAnimatorListener(listener)
    }

    fun removeAnimatorListener(listener: Animator.AnimatorListener?) {
        lottieDrawable.removeAnimatorListener(listener)
    }

    fun removeAllAnimatorListeners() {
        lottieDrawable.removeAllAnimatorListeners()
    }

    /**
     * @see .setRepeatCount
     */
    @Deprecated("")
    fun loop(loop: Boolean) {
        lottieDrawable.repeatCount = if (loop) ValueAnimator.INFINITE else 0
    }
    /**
     * Defines what this animation should do when it reaches the end.
     *
     * @return either one of [LottieDrawable.REVERSE] or [LottieDrawable.RESTART]
     */
    /**
     * Defines what this animation should do when it reaches the end. This
     * setting is applied only when the repeat count is either greater than
     * 0 or [LottieDrawable.INFINITE]. Defaults to [LottieDrawable.RESTART].
     *
     * @param mode [LottieDrawable.RESTART] or [LottieDrawable.REVERSE]
     */
    @get:RepeatMode
    var repeatMode: Int
        get() = lottieDrawable.repeatMode
        set(mode) {
            lottieDrawable.repeatMode = mode
        }
    /**
     * Defines how many times the animation should repeat. The default value
     * is 0.
     *
     * @return the number of times the animation should repeat, or [LottieDrawable.INFINITE]
     */
    /**
     * Sets how many times the animation should be repeated. If the repeat
     * count is 0, the animation is never repeated. If the repeat count is
     * greater than 0 or [LottieDrawable.INFINITE], the repeat mode will be taken
     * into account. The repeat count is 0 by default.
     *
     * @param count the number of times the animation should be repeated
     */
    var repeatCount: Int
        get() = lottieDrawable.repeatCount
        set(count) {
            lottieDrawable.repeatCount = count
        }
    val isAnimating: Boolean
        get() = lottieDrawable.isAnimating

    /**
     * If you use image assets, you must explicitly specify the folder in assets/ in which they are
     * located because bodymovin uses the name filenames across all compositions (img_#).
     * Do NOT rename the images themselves.
     *
     * If your images are located in src/main/assets/airbnb_loader/ then call
     * `setImageAssetsFolder("airbnb_loader/");`.
     *
     * Be wary if you are using many images, however. Lottie is designed to work with vector shapes
     * from After Effects. If your images look like they could be represented with vector shapes,
     * see if it is possible to convert them to shape layers and re-export your animation. Check
     * the documentation at http://airbnb.io/lottie for more information about importing shapes from
     * Sketch or Illustrator to avoid this.
     */
    var imageAssetsFolder: String?
        get() = lottieDrawable.imageAssetsFolder
        set(imageAssetsFolder) {
            lottieDrawable.setImagesAssetsFolder(imageAssetsFolder)
        }

    /**
     * Allows you to modify or clear a bitmap that was loaded for an image either automatically
     * through [.setImageAssetsFolder] or with an [ImageAssetDelegate].
     *
     * @return the previous Bitmap or null.
     */
    fun updateBitmap(id: String?, bitmap: Bitmap?): Bitmap? {
        return lottieDrawable.updateBitmap(id, bitmap)
    }

    /**
     * Use this if you can't bundle images with your app. This may be useful if you download the
     * animations from the network or have the images saved to an SD Card. In that case, Lottie
     * will defer the loading of the bitmap to this delegate.
     *
     * Be wary if you are using many images, however. Lottie is designed to work with vector shapes
     * from After Effects. If your images look like they could be represented with vector shapes,
     * see if it is possible to convert them to shape layers and re-export your animation. Check
     * the documentation at http://airbnb.io/lottie for more information about importing shapes from
     * Sketch or Illustrator to avoid this.
     */
    fun setImageAssetDelegate(assetDelegate: ImageAssetDelegate?) {
        lottieDrawable.setImageAssetDelegate(assetDelegate)
    }

    /**
     * Use this to manually set fonts.
     */
    fun setFontAssetDelegate(
            assetDelegate: FontAssetDelegate?
    ) {
        lottieDrawable.setFontAssetDelegate(assetDelegate)
    }

    /**
     * Set this to replace animation text with custom text at runtime
     */
    fun setTextDelegate(textDelegate: TextDelegate?) {
        lottieDrawable.setTextDelegate(textDelegate)
    }

    /**
     * Takes a [KeyPath], potentially with wildcards or globstars and resolve it to a list of
     * zero or more actual [Keypaths][KeyPath] that exist in the current animation.
     *
     * If you want to set value callbacks for any of these values, it is recommended to use the
     * returned [KeyPath] objects because they will be internally resolved to their content
     * and won't trigger a tree walk of the animation contents when applied.
     */
    fun resolveKeyPath(keyPath: KeyPath?): List<KeyPath> {
        return lottieDrawable.resolveKeyPath(keyPath)
    }

    /**
     * Add a property callback for the specified [KeyPath]. This [KeyPath] can resolve
     * to multiple contents. In that case, the callback's value will apply to all of them.
     *
     * Internally, this will check if the [KeyPath] has already been resolved with
     * [.resolveKeyPath] and will resolve it if it hasn't.
     */
    fun <T> addValueCallback(keyPath: KeyPath?, property: T, callback: LottieValueCallback<T>?) {
        lottieDrawable.addValueCallback(keyPath, property, callback)
    }

    /**
     * Overload of [.addValueCallback] that takes an interface. This allows you to use a single abstract
     * method code block in Kotlin such as:
     * animationView.addValueCallback(yourKeyPath, LottieProperty.COLOR) { yourColor }
     */
    fun <T> addValueCallback(
            keyPath: KeyPath?, property: T,
            callback: SimpleLottieValueCallback<T>
    ) {
        lottieDrawable.addValueCallback(keyPath, property, object : LottieValueCallback<T>() {
            override fun getValue(frameInfo: LottieFrameInfo<T>): T {
                return callback.getValue(frameInfo)
            }
        })
    }

    /**
     * Set the scale on the current composition. The only cost of this function is re-rendering the
     * current frame so you may call it frequent to scale something up or down.
     *
     * The smaller the animation is, the better the performance will be. You may find that scaling an
     * animation down then rendering it in a larger ImageView and letting ImageView scale it back up
     * with a scaleType such as centerInside will yield better performance with little perceivable
     * quality loss.
     *
     * You can also use a fixed view width/height in conjunction with the normal ImageView
     * scaleTypes centerCrop and centerInside.
     */
    var scale: Float
        get() = lottieDrawable.scale
        set(scale) {
            lottieDrawable.scale = scale
            if (drawable === lottieDrawable) {
                setImageDrawable(null)
                setImageDrawable(lottieDrawable)
            }
        }

    @MainThread
    fun cancelAnimation() {
        wasAnimatingWhenNotShown = false
        lottieDrawable.cancelAnimation()
        enableOrDisableHardwareLayer()
    }

    @MainThread
    fun pauseAnimation() {
        autoPlay = false
        wasAnimatingWhenDetached = false
        wasAnimatingWhenNotShown = false
        lottieDrawable.pauseAnimation()
        enableOrDisableHardwareLayer()
    }
    /**
     * Get the currently rendered frame.
     */
    /**
     * Sets the progress to the specified frame.
     * If the composition isn't set yet, the progress will be set to the frame when
     * it is.
     */
    var frame: Int
        get() = lottieDrawable.frame
        set(frame) {
            lottieDrawable.frame = frame
        }

    @get:FloatRange(from = 0.0, to = 1.0)
    var progress: Float
        get() = lottieDrawable.progress
        set(progress) {
            lottieDrawable.progress = progress
        }
    val duration: Long
        get() = if (composition != null) composition!!.duration.toLong() else 0

    fun setPerformanceTrackingEnabled(enabled: Boolean) {
        lottieDrawable.setPerformanceTrackingEnabled(enabled)
    }

    val performanceTracker: PerformanceTracker?
        get() = lottieDrawable.performanceTracker

    private fun clearComposition() {
        composition = null
        lottieDrawable.clearComposition()
    }

    /**
     * If rendering via software, Android will fail to generate a bitmap if the view is too large. Rather than displaying
     * nothing, fallback on hardware acceleration which may incur a performance hit.
     *
     * @see .setRenderMode
     * @see LottieDrawable.draw
     */
    override fun buildDrawingCache(autoScale: Boolean) {
        super.buildDrawingCache(autoScale)
        if (layerType == LAYER_TYPE_SOFTWARE && getDrawingCache(autoScale) == null) {
            setRenderMode(RenderMode.HARDWARE)
        }
    }

    /**
     * Call this to set whether or not to render with hardware or software acceleration.
     * Lottie defaults to Automatic which will use hardware acceleration unless:
     * 1) There are dash paths and the device is pre-Pie.
     * 2) There are more than 4 masks and mattes and the device is pre-Pie.
     * Hardware acceleration is generally faster for those devices unless
     * there are many large mattes and masks in which case there is a ton
     * of GPU uploadTexture thrashing which makes it much slower.
     *
     * In most cases, hardware rendering will be faster, even if you have mattes and masks.
     * However, if you have multiple mattes and masks (especially large ones) then you
     * should test both render modes. You should also test on pre-Pie and Pie+ devices
     * because the underlying rendering enginge changed significantly.
     */
    fun setRenderMode(renderMode: RenderMode) {
        this.renderMode = renderMode
        enableOrDisableHardwareLayer()
    }

    private fun enableOrDisableHardwareLayer() {
        var layerType = LAYER_TYPE_SOFTWARE
        when (renderMode) {
            RenderMode.HARDWARE -> layerType = LAYER_TYPE_HARDWARE
            RenderMode.SOFTWARE -> layerType = LAYER_TYPE_SOFTWARE
            RenderMode.AUTOMATIC -> {
                var useHardwareLayer = true
                if (composition != null && composition!!.hasDashPattern() && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    useHardwareLayer = false
                } else if (composition != null && composition!!.maskAndMatteCount > 4) {
                    useHardwareLayer = false
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    useHardwareLayer = false
                }
                layerType = if (useHardwareLayer) LAYER_TYPE_HARDWARE else LAYER_TYPE_SOFTWARE
            }
        }
        if (layerType != getLayerType()) {
            setLayerType(layerType, null)
        }
    }

    fun addLottieOnCompositionLoadedListener(lottieOnCompositionLoadedListener: LottieOnCompositionLoadedListener): Boolean {
        val composition = composition
        if (composition != null) {
            lottieOnCompositionLoadedListener.onCompositionLoaded(composition)
        }
        return lottieOnCompositionLoadedListeners.add(lottieOnCompositionLoadedListener)
    }

    fun removeLottieOnCompositionLoadedListener(lottieOnCompositionLoadedListener: LottieOnCompositionLoadedListener): Boolean {
        return lottieOnCompositionLoadedListeners.remove(lottieOnCompositionLoadedListener)
    }

    fun removeAllLottieOnCompositionLoadedListener() {
        lottieOnCompositionLoadedListeners.clear()
    }

    private class SavedState : BaseSavedState {
        var animationName: String? = null
        var animationResId = 0
        var progress = 0f
        var isAnimating = false
        var imageAssetsFolder: String? = null
        var repeatMode = 0
        var repeatCount = 0

        internal constructor(superState: Parcelable?) : super(superState) {}
        private constructor(`in`: Parcel) : super(`in`) {
            animationName = `in`.readString()
            progress = `in`.readFloat()
            isAnimating = `in`.readInt() == 1
            imageAssetsFolder = `in`.readString()
            repeatMode = `in`.readInt()
            repeatCount = `in`.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(animationName)
            out.writeFloat(progress)
            out.writeInt(if (isAnimating) 1 else 0)
            out.writeString(imageAssetsFolder)
            out.writeInt(repeatMode)
            out.writeInt(repeatCount)
        }

        companion object {
            @JvmField val CREATOR: Parcelable.Creator<SavedState?> = object : Parcelable.Creator<SavedState?> {
                override fun createFromParcel(p: Parcel): SavedState? {
                    return SavedState(p)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    fun getAnimationScale(context: Context): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
            )
        } else {
            Settings.System.getFloat(
                    context.contentResolver,
                    Settings.System.ANIMATOR_DURATION_SCALE, 1.0f
            )
        }
    }

    companion object {
        private val TAG = LottieAnimationView2::class.java.simpleName
    }
}