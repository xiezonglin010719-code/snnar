package com.example.senar.utils

import android.app.Application
import com.example.senar.PythonBridge

class SenarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PythonBridge.init(this)
    }
}