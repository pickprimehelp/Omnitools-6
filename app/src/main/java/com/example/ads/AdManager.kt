package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object AdManager {
    private const val TAG = "OmniToolsAds"
    const val USE_TEST_ADS = false

    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

    const val REAL_APP_ID = "ca-app-pub-7584087725069099~5449667520"
    const val REAL_BANNER_ID = "ca-app-pub-7584087725069099/8487278849"
    const val REAL_INTERSTITIAL_ID = "ca-app-pub-7584087725069099/7969559042"
    const val REAL_REWARDED_ID = "ca-app-pub-7584087725069099/3640752672"

    val APP_ID: String get() = if (USE_TEST_ADS) TEST_APP_ID else REAL_APP_ID
    val BANNER_ID: String get() = if (USE_TEST_ADS) TEST_BANNER_ID else REAL_BANNER_ID
    val INTERSTITIAL_ID: String get() = if (USE_TEST_ADS) TEST_INTERSTITIAL_ID else REAL_INTERSTITIAL_ID
    val REWARDED_ID: String get() = if (USE_TEST_ADS) TEST_REWARDED_ID else REAL_REWARDED_ID

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val isSdkInitializedState = mutableStateOf(false)
    val isSdkInitialized: Boolean get() = isSdkInitializedState.value

    private var isInitializing = false
    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var isInterstitialLoading = false
    private var isRewardedLoading = false
    private var interstitialRetryAttempt = 0
    private var rewardedRetryAttempt = 0

    private var lastInterstitialShownTime: Long = 0L
    private var screenChangeCount: Int = 0

    // Balanced AdMob cadence: reliable fill & UX without starving impressions
    private const val INTERSTITIAL_COOLDOWN_MS = 45000L
    private const val FORCED_INTERSTITIAL_COOLDOWN_MS = 15000L
    private const val SCREENS_BETWEEN_ADS = 2

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun initialize(context: Context) {
        val safeCtx = context.applicationContext ?: context
        appContext = safeCtx
        if (isSdkInitializedState.value || isInitializing) return
        isInitializing = true

        // Safety fallback if Play Services delays or suppresses init callback
        mainHandler.postDelayed({
            if (!isSdkInitializedState.value) {
                isSdkInitializedState.value = true
                preloadInterstitial(safeCtx)
                preloadRewarded(safeCtx)
            }
        }, 2500L)

        try {
            MobileAds.initialize(safeCtx) {
                runOnMain {
                    isInitializing = false
                    isSdkInitializedState.value = true
                    Log.d(TAG, "MobileAds initialized successfully")
                    preloadInterstitial(safeCtx)
                    preloadRewarded(safeCtx)
                }
            }
        } catch (t: Throwable) {
            isInitializing = false
            isSdkInitializedState.value = true
            Log.e(TAG, "Error initializing MobileAds", t)
        }
    }

    fun preloadInterstitial(context: Context) {
        val safeCtx = context.applicationContext ?: context
        appContext = safeCtx
        runOnMain {
            if (interstitialAd != null || isInterstitialLoading) return@runOnMain
            isInterstitialLoading = true
            try {
                val adRequest = AdRequest.Builder().build()
                InterstitialAd.load(
                    safeCtx,
                    INTERSTITIAL_ID,
                    adRequest,
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            interstitialAd = ad
                            isInterstitialLoading = false
                            interstitialRetryAttempt = 0
                            Log.d(TAG, "Interstitial loaded")
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            interstitialAd = null
                            isInterstitialLoading = false
                            Log.w(TAG, "Interstitial failed (${loadAdError.code}): ${loadAdError.message}")
                            if (interstitialRetryAttempt < 3) {
                                val delayMs = when (interstitialRetryAttempt) {
                                    0 -> 6000L
                                    1 -> 15000L
                                    else -> 35000L
                                }
                                interstitialRetryAttempt++
                                val retryCtx = appContext ?: safeCtx
                                mainHandler.postDelayed({ preloadInterstitial(retryCtx) }, delayMs)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                isInterstitialLoading = false
                Log.e(TAG, "Interstitial load exception", t)
            }
        }
    }

    fun preloadRewarded(context: Context) {
        val safeCtx = context.applicationContext ?: context
        appContext = safeCtx
        runOnMain {
            if (rewardedAd != null || isRewardedLoading) return@runOnMain
            isRewardedLoading = true
            try {
                val adRequest = AdRequest.Builder().build()
                RewardedAd.load(
                    safeCtx,
                    REWARDED_ID,
                    adRequest,
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            rewardedAd = ad
                            isRewardedLoading = false
                            rewardedRetryAttempt = 0
                            Log.d(TAG, "Rewarded ad loaded")
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            rewardedAd = null
                            isRewardedLoading = false
                            Log.w(TAG, "Rewarded failed (${loadAdError.code}): ${loadAdError.message}")
                            if (rewardedRetryAttempt < 3) {
                                val delayMs = when (rewardedRetryAttempt) {
                                    0 -> 6000L
                                    1 -> 15000L
                                    else -> 35000L
                                }
                                rewardedRetryAttempt++
                                val retryCtx = appContext ?: safeCtx
                                mainHandler.postDelayed({ preloadRewarded(retryCtx) }, delayMs)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                isRewardedLoading = false
                Log.e(TAG, "Rewarded load exception", t)
            }
        }
    }

    @JvmOverloads
    fun showInterstitialIfReady(
        activity: Activity,
        forceShow: Boolean = false,
        onDismissed: () -> Unit
    ) {
        interstitialRetryAttempt = 0
        screenChangeCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastInterstitialShownTime
        val shouldShow = if (forceShow) {
            lastInterstitialShownTime == 0L || elapsed > FORCED_INTERSTITIAL_COOLDOWN_MS
        } else {
            (lastInterstitialShownTime == 0L || elapsed > INTERSTITIAL_COOLDOWN_MS) &&
                screenChangeCount >= SCREENS_BETWEEN_ADS
        }

        val ad = interstitialAd
        if (ad != null && shouldShow && !activity.isFinishing && !activity.isDestroyed) {
            try {
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        interstitialAd = null
                        lastInterstitialShownTime = System.currentTimeMillis()
                        screenChangeCount = 0
                        preloadInterstitial(activity)
                        onDismissed()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Log.w(TAG, "Interstitial failed to show: ${adError.message}")
                        interstitialAd = null
                        preloadInterstitial(activity)
                        onDismissed()
                    }
                }
                ad.show(activity)
            } catch (t: Throwable) {
                Log.e(TAG, "Error showing interstitial", t)
                interstitialAd = null
                preloadInterstitial(activity)
                onDismissed()
            }
        } else {
            preloadInterstitial(activity)
            onDismissed()
        }
    }

    fun showRewardedAd(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissed: () -> Unit
    ) {
        rewardedRetryAttempt = 0
        val ad = rewardedAd
        if (ad != null && !activity.isFinishing && !activity.isDestroyed) {
            try {
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        rewardedAd = null
                        preloadRewarded(activity)
                        onDismissed()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        Log.w(TAG, "Rewarded failed to show: ${adError.message}")
                        rewardedAd = null
                        preloadRewarded(activity)
                        onDismissed()
                    }
                }
                ad.show(activity) {
                    onRewardEarned()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error showing rewarded ad", t)
                rewardedAd = null
                preloadRewarded(activity)
                onRewardEarned()
                onDismissed()
            }
        } else {
            preloadRewarded(activity)
            onRewardEarned()
            onDismissed()
        }
    }
}

private class BannerAdHolder(context: Context) {
    val container = FrameLayout(context)
    val adView = AdView(context)
    val handler = Handler(Looper.getMainLooper())
    var hasLoaded = false
    var isLoading = false
    var isDisposed = false
    var retryCount = 0
    var lastSdkTrigger = false
    private var pendingRetry: Runnable? = null

    init {
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val dm = context.resources.displayMetrics
        val density = if (dm.density > 0f) dm.density else 1f
        val widthDp = (dm.widthPixels / density).toInt().coerceAtLeast(320)
        val bannerSize = try {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        } catch (_: Throwable) {
            AdSize.BANNER
        }
        adView.setAdSize(bannerSize)
        adView.adUnitId = AdManager.BANNER_ID
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                hasLoaded = true
                isLoading = false
                retryCount = 0
                pendingRetry?.let { handler.removeCallbacks(it) }
                pendingRetry = null
                container.visibility = View.VISIBLE
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                isLoading = false
                Log.w("TopBannerAd", "Banner failed (${error.code}): ${error.message}")
                if (isDisposed || hasLoaded) return
                if (retryCount < 4) {
                    val delayMs = when (retryCount) {
                        0 -> 3000L
                        1 -> 7000L
                        2 -> 15000L
                        else -> 30000L
                    }
                    retryCount++
                    pendingRetry?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        pendingRetry = null
                        loadIfNeeded(resetVisibility = false)
                    }
                    pendingRetry = r
                    handler.postDelayed(r, delayMs)
                } else {
                    container.visibility = View.GONE
                }
            }
        }
        val adParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL
        )
        container.addView(adView, adParams)
    }

    fun loadIfNeeded(resetVisibility: Boolean) {
        if (isDisposed || hasLoaded || isLoading) return
        if (resetVisibility) {
            container.visibility = View.VISIBLE
        }
        isLoading = true
        try {
            adView.loadAd(AdRequest.Builder().build())
        } catch (t: Throwable) {
            isLoading = false
            Log.e("TopBannerAd", "Error loading banner", t)
        }
    }

    fun resume() {
        try {
            adView.resume()
        } catch (_: Throwable) {
        }
    }

    fun dispose() {
        isDisposed = true
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        handler.removeCallbacksAndMessages(null)
        try {
            adView.pause()
            adView.destroy()
        } catch (_: Throwable) {
        }
    }
}

@Composable
fun TopBannerAd(modifier: Modifier = Modifier) {
    val sdkReady = AdManager.isSdkInitialized
    val holderRef = remember { arrayOfNulls<BannerAdHolder>(1) }

    DisposableEffect(Unit) {
        holderRef[0]?.resume()
        onDispose {
            holderRef[0]?.dispose()
            holderRef[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val holder = BannerAdHolder(ctx)
            holderRef[0] = holder
            holder.lastSdkTrigger = sdkReady
            holder.loadIfNeeded(resetVisibility = true)
            holder.container
        },
        update = {
            val holder = holderRef[0]
            if (holder != null && sdkReady && !holder.lastSdkTrigger) {
                holder.lastSdkTrigger = true
                if (!holder.hasLoaded && !holder.isLoading) {
                    holder.retryCount = 0
                    holder.loadIfNeeded(resetVisibility = true)
                }
            }
        }
    )
}
