package com.example.stalker2ai

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val proceedRunnable = Runnable {
        if (!isFinishing) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        proceedToMain()
    }

    private fun proceedToMain() {
        handler.postDelayed(proceedRunnable, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacks(proceedRunnable)
        super.onDestroy()
    }
}
