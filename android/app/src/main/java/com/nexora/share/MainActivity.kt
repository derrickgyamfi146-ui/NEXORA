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

class MainActivity : Activity() {
    private lateinit var fileStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }

        page.addView(TextView(this).apply {
            text = "NEXORA"
            textSize = 32f
            setTextColor(0xFF172554.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        page.addView(TextView(this).apply {
            text = "SHARE WITHOUT LIMITS"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        page.addView(TextView(this).apply {
            text = "Your nearby file-sharing app"
            textSize = 20f
            setTextColor(0xFF0F172A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 52, 0, 12)
        }, matchWrap())

        page.addView(TextView(this).apply {
            text = "The native Android version is being built for direct device-to-device sharing, including large files."
            textSize = 15f
            setTextColor(0xFF475569.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        page.addView(Button(this).apply {
            text = "Choose files"
            setOnClickListener { openFilePicker() }
        }, buttonParams())

        fileStatus = TextView(this).apply {
            text = "No file selected"
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        page.addView(fileStatus, matchWrap())

        page.addView(Button(this).apply {
            text = "Receive files"
            isEnabled = false
        }, buttonParams())

        page.addView(TextView(this).apply {
            text = "Next: connect nearby phones, send and receive files, keep transfers running, and save received files directly to Downloads."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }, matchWrap())

        setContentView(page)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileStatus.text = "Selected: ${uri.lastPathSegment ?: "File"}"
        }
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

    companion object {
        private const val REQUEST_PICK_FILE = 1001
    }
}
