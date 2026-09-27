package com.syncmusic.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.syncmusic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHost.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }

        binding.btnClient.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
    }
}
