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
            "cloud_name" to "dkzfvsdfj",
            "api_key" to "493742256116112",
            "api_secret" to "aOwROuGvEMSuOfhBhGsdWz4iMjQ"
        )

        MediaManager.init(this, config)
    }
}