package com.example.clubmate

import android.app.Application
import com.cloudinary.android.MediaManager
import com.example.clubmate.e2ee.E2eeManager
import com.google.firebase.FirebaseApp

class ClubMateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        E2eeManager.init(this)

        val config: HashMap<String, String> = hashMapOf(
            "cloud_name" to BuildConfig.CLOUDINARY_CLOUD_NAME,
            "api_key" to BuildConfig.CLOUDINARY_API_KEY,
            "api_secret" to BuildConfig.CLOUDINARY_API_SECRET
        )

        MediaManager.init(this, config)
    }
}