package com.simpletavern.app

import android.app.Application
import com.simpletavern.core.api.SimpleTavernCore

class StApplication : Application() {
    lateinit var core: SimpleTavernCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = SimpleTavernCore.get(this)
    }
}
