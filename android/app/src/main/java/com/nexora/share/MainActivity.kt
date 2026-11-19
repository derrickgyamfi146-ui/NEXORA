package com.nexora.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding

class MainActivity : Activity() {
    private var selectedFileName: String? = null
    private lateinit var fileStatus: TextView

    private val chooseFile = registerForActivityResultCompat { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedFileName = uri.lastPathSegment ?: "File selected"
            fileStatus.text = "Selected: $selectedFileName"
            Toast.makeText(this, "File selected. Transfer is the next build step.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        val brand = TextView(this).apply {
            text = "NEXORA"
            textSize = 32f
            setTextColor(0xFF172554.toInt())
            gravity = Gravity.CENTER
        }
        page.addView(brand, matchWrap())

        val tagline = TextView(this).apply {
            text = "SHARE WITHOUT LIMITS"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
        }
        page.addView(tagline, matchWrap())

        val intro = TextView(this).apply {
            text = "Your nearby file-sharing app"
            textSize = 20f
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 52, 0, 12)
        }
        page.addView(intro, matchWrap())

        val description = TextView(this).apply {
            text = "The native Android version is being built. It is designed for direct device-to-device sharing without needing an internet connection."
            textSize = 15f
            setTextColor(0xFF475569.toInt())
            gravity = Gravity.CENTER
        }
        page.addView(description, matchWrap())

        val select = Button(this).apply {
            text = "Choose files"
            setOnClickListener {
                chooseFile.launch(arrayOf("*/*"))
            }
        }
        page.addView(select, buttonParams())

        fileStatus = TextView(this).apply {
            text = "No file selected"
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        page.addView(fileStatus, matchWrap())

        val receive = Button(this).apply {
            text = "Receive files"
            isEnabled = false
        }
        page.addView(receive, buttonParams())

        val note = TextView(this).apply {
            text = "Next: connect nearby Android phones, transfer large files, and save received files directly to Downloads."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        page.addView(note, matchWrap())

        setContentView(page)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = 18
    }

    private fun registerForActivityResultCompat(onResult: (Uri?) -> Unit) =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), onResult)
}
