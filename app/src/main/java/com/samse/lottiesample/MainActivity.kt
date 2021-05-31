package com.samse.lottiesample

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import com.airbnb.lottie.LottieAnimationView2
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val lv = findViewById<ListView>(R.id.listview);
        lv.adapter = ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                arrayOf("Lottie with Assets resource", "Lottie with url", "Lottie with local file from url")
        )
        lv.setOnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
            if (position == 0) {
                onLottieWithAsset()
            } else if (position == 1) {
                onLottieWithUrl()
            } else if (position == 2) {
                onLottieWithLocalFromUrl()
            }
        }
    }

    private fun onLottieWithAsset() {
        var contentView: View = (getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater).inflate(
            R.layout.popup_lottie_with_asset, null)
        val slideupPopup = SlideUpDialog.Builder(this)
            .setContentView(contentView)
            .create()
        slideupPopup.show()
        contentView.findViewById<Button>(R.id.close).setOnClickListener {
            slideupPopup.dismissAnim()
        }
    }

    private fun onLottieWithUrl() {
        var contentView: View = (getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater).inflate(
            R.layout.popup_lottie_with_url, null)
        val slideupPopup = SlideUpDialog.Builder(this)
            .setContentView(contentView)
            .create()
        slideupPopup.show()
        contentView.findViewById<Button>(R.id.close).setOnClickListener {
            slideupPopup.dismissAnim()
        }
    }

    private fun onLottieWithLocalFromUrl() {
        val fileDir: File? = getExternalFilesDir("lottieFiles")
        if (fileDir!=null) {
            if (!fileDir.exists()) {
                fileDir.mkdir()
            }
        } else return

        var contentView: View = (getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater).inflate(
            R.layout.popup_lottie_with_local_from_url,
            null
        )
        val slideupPopup = SlideUpDialog.Builder(this)
            .setContentView(contentView)
            .create()
        slideupPopup.show()
        val filePath = "${fileDir.absolutePath}/lottiesample.json"
        val fileUrl = "https://www.ntoworks.com/down/lottiesample.json"
        val lottieView = contentView.findViewById<LottieAnimationView2>(R.id.lottieView)
        lottieView.setAnimationFromLocal(
            filePath,
            fileUrl,
            object : LottieAnimationView2.OnResultListener {
                override fun onResult(ret: Boolean, response: Any?) {
                }
            })
        contentView.findViewById<Button>(R.id.close).setOnClickListener {
            slideupPopup.dismissAnim()
        }
    }
}