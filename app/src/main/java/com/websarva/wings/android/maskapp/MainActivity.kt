package com.websarva.wings.android.maskapp

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun onJapanButtonClick(view: View){
        val language = "Japanese"
        val intent = Intent(this, MainActivity2::class.java)
        intent.putExtra("language", language)
        startActivity(intent)
    }

    fun onEnglishButtonClick(view: View){
        val language = "english"
        val intent = Intent(this, MainActivity2::class.java)
        intent.putExtra("language", language)
        startActivity(intent)
    }

}
