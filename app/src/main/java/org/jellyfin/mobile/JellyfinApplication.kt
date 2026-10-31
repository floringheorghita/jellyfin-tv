package org.jellyfin.mobile

import android.app.Application
import android.webkit.WebView
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        org.jellyfin.mobile.crash.DiagnosticsLog.log("onTrimMemory level=$level · " + org.jellyfin.mobile.crash.DiagnosticsLog.memoryLine())
    }

    override fun onLowMemory() {
        super.onLowMemory()
        org.jellyfin.mobile.crash.DiagnosticsLog.log("onLowMemory · " + org.jellyfin.mobile.crash.DiagnosticsLog.memoryLine())
    }

    override fun onCreate() {
        super.onCreate()
        // TV build: record crashes so they can be shown on next launch
        org.jellyfin.mobile.crash.CrashReporter.install(this)

        // Setup logging
        Timber.plant(JellyTree())
        // TV build: diagnostics log (Settings > Send diagnostics)
        org.jellyfin.mobile.crash.DiagnosticsLog.init(this)
        Timber.plant(org.jellyfin.mobile.crash.DiagnosticsLog.Tree())

        if (BuildConfig.DEBUG) {
            // Enable WebView debugging
            if (isWebViewSupported()) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }

        startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }
    }
}
