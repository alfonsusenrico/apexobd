package com.apexobd.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Application-scoped [ViewModelStoreOwner] so the live OBD-II polling engine,
 * Wi-Fi low-latency locks, and background listener stay alive smoothly across
 * Activity backgrounding, Back navigation, and configuration changes until
 * explicitly terminated via the background notification.
 */
class ApexObdApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore by lazy { ViewModelStore() }
}
