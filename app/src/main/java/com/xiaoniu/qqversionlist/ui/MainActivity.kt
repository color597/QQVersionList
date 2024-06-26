/*
    QQ Version Tool for Android™
    Copyright (C) 2023 klxiaoniu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.xiaoniu.qqversionlist.ui


import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import com.xiaoniu.qqversionlist.R
import com.xiaoniu.qqversionlist.data.QQVersionBean
import com.xiaoniu.qqversionlist.databinding.ActivityMainBinding
import com.xiaoniu.qqversionlist.databinding.DialogGuessBinding
import com.xiaoniu.qqversionlist.util.ClipboardUtil.copyText
import com.xiaoniu.qqversionlist.util.InfoUtil.dialogError
import com.xiaoniu.qqversionlist.util.InfoUtil.showToast
import com.xiaoniu.qqversionlist.util.LogUtil.log
import com.xiaoniu.qqversionlist.util.SpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.Thread.sleep
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var versionAdapter: VersionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)
        versionAdapter = VersionAdapter()
        binding.rvContent.apply {
            adapter = versionAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(VerticalSpaceItemDecoration(dpToPx(5)))
        }
        initButtons()

        WindowCompat.setDecorFitsSystemWindows(window, false)


    }

    private fun Context.dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

//    private fun Context.pxToDp(px: Int): Int {
//        return (px / resources.displayMetrics.density).toInt()
//    }


    class VerticalSpaceItemDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            with(outRect) {
                // 对于每一项都添加底部间距
                bottom = space
                // 如果不是第一行，则添加顶部间距
                if (parent.getChildAdapterPosition(view) != 0) {
                    top = space
                }
            }
        }
    }


    private fun UADialog(agreed: Boolean) {

        val screenHeight = Resources.getSystem().displayMetrics.heightPixels

        //用户协议，传参内容表示先前是否同意过协议
        val UAView: View = layoutInflater.inflate(R.layout.user_agreement, null)
        val constraintLayout = UAView.findViewById<ConstraintLayout>(R.id.user_agreement)
        val uaAgree = UAView.findViewById<Button>(R.id.ua_button_agree)
        val uaDisagree = UAView.findViewById<Button>(R.id.ua_button_disagree)

        if (UAView.parent != null) {
            (UAView.parent as ViewGroup).removeView(UAView)
        }


        val dialogUA =
            MaterialAlertDialogBuilder(this).setTitle("用户协议").setIcon(R.drawable.file_user_line)
                .setView(UAView).setCancelable(false).create()


        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        val currentConfig = resources.configuration
        if (currentConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            constraintSet.constrainHeight(R.id.UA_text, screenHeight / 6)
        } else if (currentConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            constraintSet.constrainHeight(R.id.UA_text, screenHeight / 2)
        }

        constraintSet.applyTo(constraintLayout)

        uaAgree.setOnClickListener {
            SpUtil.putInt(this, "userAgreement", 1)
            dialogUA.dismiss()
        }

        uaDisagree.setOnClickListener {
            SpUtil.putInt(this, "userAgreement", 0)
            //不同意直接退出程序
            finish()
        }
        if (agreed) {
            uaDisagree.text = "撤回同意并退出"
        }

        dialogUA.show()
    }


    private fun initButtons() {
        // 删除 version Shared Preferences
        SpUtil.deleteSp(this, "version")

        //这里的“getInt: userAgreement”的值代表着用户协议修订版本，后续更新协议版本后也需要在下面一行把“judgeUARead”+1，以此类推
        val judgeUARead = 1
        if (SpUtil.getInt(this, "userAgreement", 0) != judgeUARead) {
            UADialog(false)
        }

        // var currentQQVersion = ""

        // 进度条动画
        // https://github.com/material-components/material-components-android/blob/master/docs/components/ProgressIndicator.md

        binding.progressLine.apply {
            showAnimationBehavior = LinearProgressIndicator.SHOW_NONE
            hideAnimationBehavior = LinearProgressIndicator.HIDE_ESCAPE
            //setVisibilityAfterHide(View.GONE)
        }

        fun getData() {
            binding.progressLine.show()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val okHttpClient = OkHttpClient()
                    val request =
                        Request.Builder().url("https://im.qq.com/rainbow/androidQQVersionList")
                            .build()
                    val response = okHttpClient.newCall(request).execute()
                    val responseData = response.body?.string()
                    if (responseData != null) {
                        val start = (responseData.indexOf("versions64\":[")) + 12
                        val end = (responseData.indexOf(";\n" + "      typeof"))
                        "start: $start, end: $end".log()
                        val totalJson = responseData.substring(start, end)//.apply { log() }
                        val qqVersion = totalJson.split("},{").reversed().map {
                            val pstart = it.indexOf("{\"versions")
                            val pend = it.indexOf(",\"length")
                            val json = it.substring(pstart, pend)
                            Gson().fromJson(json, QQVersionBean::class.java).apply {
                                jsonString = json
                            }
                        }
                        withContext(Dispatchers.Main) {
                            versionAdapter.setData(this@MainActivity, qqVersion)
                            //currentQQVersion = qqVersion.first().versionNumber
                            //大版本号也放持久化存储了，否则猜版 Shortcut 因为加载过快而获取不到东西
                            SpUtil.putString(
                                this@MainActivity, "versionBig", qqVersion.first().versionNumber
                            )
                        }

                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    dialogError(e)
                } finally {
                    withContext(Dispatchers.Main) {
                        binding.progressLine.hide()
                    }
                }
            }
        }

        getData()

        binding.rvContent.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    binding.btnGuess.shrink()
                } else if (dy < 0) {
                    binding.btnGuess.extend()
                }
            }
        })


        binding.bottomAppBar.setOnMenuItemClickListener { menuItem ->
            //底部左下角按钮动作
            when (menuItem.itemId) {
                R.id.btn_get -> {
                    getData()
                    true
                }

                R.id.btn_about -> {
                    val message =
                        SpannableString("QQ 版本列表实用工具 for Android\n\n作者：快乐小牛、有鲫雪狐\n\n版本：" + packageManager.getPackageInfo(
                            packageName, 0
                        ).let {
                            @Suppress("DEPRECATION") it.versionName + "(" + (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode) + ")"
                        } + "\n\nSince 2023.8.9\n\nLicensed under AGPL v3\n\n" + "开源地址")
                    val urlSpan = URLSpan("https://github.com/klxiaoniu/QQVersionList")
                    message.setSpan(
                        urlSpan,
                        message.length - 4,
                        message.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    MaterialAlertDialogBuilder(this).setTitle("关于")
                        .setIcon(R.drawable.information_line).setMessage(message)
                        .setPositiveButton("确定", null)
                        .setNegativeButton("撤回同意用户协议") { _, _ ->
                            UADialog(true)
                        }.show().apply {
                            findViewById<TextView>(android.R.id.message)?.movementMethod =
                                LinkMovementMethodCompat.getInstance()
                        }
                    true
                }

                R.id.btn_setting -> {
                    val settingView: View = layoutInflater.inflate(R.layout.dialog_setting, null)
                    val displayFirstSwitch =
                        settingView.findViewById<MaterialSwitch>(R.id.switch_display_first)
                    val longPressCardSwitch =
                        settingView.findViewById<MaterialSwitch>(R.id.long_press_card)
                    val guessNot5Switch = settingView.findViewById<MaterialSwitch>(R.id.guess_not_5)
                    val progressSizeSwitch =
                        settingView.findViewById<MaterialSwitch>(R.id.progress_size)
                    val btnOk = settingView.findViewById<Button>(R.id.btn_setting_ok)

                    if (settingView.parent != null) {
                        (settingView.parent as ViewGroup).removeView(settingView)
                    }

                    displayFirstSwitch.isChecked = SpUtil.getBoolean(this, "displayFirst", true)
                    longPressCardSwitch.isChecked = SpUtil.getBoolean(this, "longPressCard", true)
                    guessNot5Switch.isChecked = SpUtil.getBoolean(this, "guessNot5", false)
                    progressSizeSwitch.isChecked = SpUtil.getBoolean(this, "progressSize", false)


                    val dialogSetting = MaterialAlertDialogBuilder(this).setTitle("设置")
                        .setIcon(R.drawable.settings_line).setView(settingView).setCancelable(true)
                        .create()
                    dialogSetting.show()

                    btnOk.setOnClickListener {
                        dialogSetting.dismiss()
                    }

                    displayFirstSwitch.setOnCheckedChangeListener { _, isChecked ->
                        SpUtil.putBoolean(this, "displayFirst", isChecked)
                        getData()
                    }
                    longPressCardSwitch.setOnCheckedChangeListener { _, isChecked ->
                        SpUtil.putBoolean(this, "longPressCard", isChecked)
                    }
                    guessNot5Switch.setOnCheckedChangeListener { _, isChecked ->
                        SpUtil.putBoolean(this, "guessNot5", isChecked)
                    }
                    progressSizeSwitch.setOnCheckedChangeListener { _, isChecked ->
                        SpUtil.putBoolean(this, "progressSize", isChecked)
                        getData()
                    }


                    true
                }

                else -> false
            }
        }


        fun guessVersionDialog() {

            val dialogGuessView: View = layoutInflater.inflate(R.layout.dialog_guess, null)

            val dialogGuessBinding = DialogGuessBinding.bind(dialogGuessView)
            val verBig = SpUtil.getString(this, "versionBig", "")
            dialogGuessBinding.etVersionBig.editText?.setText(
                verBig
            )

            val memVersion = SpUtil.getString(this@MainActivity, "versionSelect", "正式版")
            if (memVersion == "测试版" || memVersion == "空格版" || memVersion == "正式版") {
                dialogGuessBinding.spinnerVersion.setText(memVersion, false)
            }
            if (dialogGuessBinding.spinnerVersion.text.toString() == "测试版" || dialogGuessBinding.spinnerVersion.text.toString() == "空格版") {
                dialogGuessBinding.etVersionSmall.isEnabled = true
                dialogGuessBinding.guessDialogWarning.visibility = View.VISIBLE
            } else if (dialogGuessBinding.spinnerVersion.text.toString() == "正式版") {
                dialogGuessBinding.etVersionSmall.isEnabled = false
                dialogGuessBinding.guessDialogWarning.visibility = View.GONE
            }

//            dialogGuessBinding.spinnerVersion.setText(SpUtil.getString(this,"version","正式版"))
//            val verItems = arrayOf("正式版", "测试版", "空格版")
//            (dialogGuessBinding.spinnerVersion as? MaterialAutoCompleteTextView)?.setSimpleItems(verItems)

//            dialogGuessBinding.spinnerVersion.onItemSelectedListener =
//                object : android.widget.AdapterView.OnItemSelectedListener {
//                    override fun onItemSelected(
//                        parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
//                    ) {
//                        if (position == 0 || position == 2) {
//                            //dialogGuessBinding.etVersionSmall.visibility = View.VISIBLE
//                            dialogGuessBinding.etVersionSmall.isEnabled = true
//                            dialogGuessBinding.guessDialogWarning.visibility = View.VISIBLE
//                        } else if (position == 1) {
//                            //dialogGuessBinding.etVersionSmall.visibility = View.GONE
//                            dialogGuessBinding.etVersionSmall.isEnabled = false
//                            dialogGuessBinding.guessDialogWarning.visibility = View.GONE
//                        }
//                        SpUtil.putInt(this@MainActivity, "version", position)
//                    }
//
//                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
//                    }
//                }

            dialogGuessBinding.spinnerVersion.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(p0: Editable?) {
                    val judgeVerSelect = dialogGuessBinding.spinnerVersion.text.toString()
                    SpUtil.putString(this@MainActivity, "versionSelect", judgeVerSelect)
                    if (judgeVerSelect == "测试版" || judgeVerSelect == "空格版") {
                        dialogGuessBinding.etVersionSmall.isEnabled = true
                        dialogGuessBinding.guessDialogWarning.visibility = View.VISIBLE
                    } else if (judgeVerSelect == "正式版") {
                        dialogGuessBinding.etVersionSmall.isEnabled = false
                        dialogGuessBinding.guessDialogWarning.visibility = View.GONE
                    }
                }

                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }
            })

            dialogGuessBinding.spinnerVersion.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(dialogGuessBinding.spinnerVersion.windowToken, 0)
                }
            }


            val dialogGuess = MaterialAlertDialogBuilder(this).setTitle("猜版 for Android")
                .setIcon(R.drawable.search_line).setView(dialogGuessView).setCancelable(false)
                .create()
            dialogGuess.show()


            dialogGuessBinding.btnGuessStart.setOnClickListener {

                dialogGuessBinding.etVersionBig.clearFocus()
                dialogGuessBinding.spinnerVersion.clearFocus()
                dialogGuessBinding.etVersionSmall.clearFocus()

                try {
                    val versionBig = dialogGuessBinding.etVersionBig.editText?.text.toString()
                    val mode = dialogGuessBinding.spinnerVersion.text.toString()
                    var versionSmall = 0
                    if (mode == "测试版" || mode == "空格版") {
                        versionSmall =
                            dialogGuessBinding.etVersionSmall.editText?.text.toString().toInt()
                    }
                    if (versionSmall % 5 != 0 && !SpUtil.getBoolean(
                            this@MainActivity, "guessNot5", false
                        )
                    ) throw Exception("小版本号需填 5 的倍数。如有需求，请前往设置解除此限制。")
                    if (versionSmall != 0) {
                        SpUtil.putInt(this, "versionSmall", versionSmall)
                    }/*我偷懒了，因为我上面也有偷懒逻辑，
                    为了防止 null，我在正式版猜版时默认填入了 0，
                    但是我没处理下面涉及到持久化存储逻辑的语句，就把 0 存进去了，
                    覆盖了原来的 15xxx 的持久化存储*/

                    guessUrl(versionBig, versionSmall, mode)

                } catch (e: Exception) {
                    e.printStackTrace()
                    dialogError(e)
                }
            }



            dialogGuessBinding.btnGuessCancel.setOnClickListener {
                dialogGuess.dismiss()
            }


            val memVersionSmall = SpUtil.getInt(this, "versionSmall", -1)
            if (memVersionSmall != -1) {
                dialogGuessBinding.etVersionSmall.editText?.setText(memVersionSmall.toString())
            }

        }

        if (intent.action == "android.intent.action.VIEW" && SpUtil.getInt(
                this, "userAgreement", 0
            ) == judgeUARead
        ) {
            guessVersionDialog()
        }

        binding.btnGuess.setOnClickListener {
            guessVersionDialog()
        }

    }


    /**
     * 获取文件大小（以MB为单位）
     *
     * @param urlString 文件的URL字符串
     * @param callback 回调函数，接收文件大小（以MB为单位）作为参数
     */
    private fun getFileSizeInMB(urlString: String, callback: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"

                val fileSize = connection.contentLength.toDouble()
                val fileSizeInMB = fileSize / (1024 * 1024)

                withContext(Dispatchers.Main) {
                    callback("%.2f".format(fileSizeInMB))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback("Error")
                }
            }
        }
    }


    //https://downv6.qq.com/qqweb/QQ_1/android_apk/Android_8.9.75.XXXXX_64.apk
    private fun guessUrl(versionBig: String, versionSmall: Int, mode: String) {
        // 绑定 AlertDialog 加载对话框布局
        val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
        val progressSpinner =
            dialogView.findViewById<CircularProgressIndicator>(R.id.progress_spinner)
        val loadingMessage = dialogView.findViewById<TextView>(R.id.loading_message)

        val successButton = layoutInflater.inflate(R.layout.success_button, null)
        val shareButton = successButton.findViewById<Button>(R.id.dialog_share_line)
        val downloadButton = successButton.findViewById<Button>(R.id.dialog_download_line_2)
        val stopButton = successButton.findViewById<Button>(R.id.dialog_stop_line)
        val continueButton = successButton.findViewById<Button>(R.id.dialog_play_line)
        val copyAndStopButton = successButton.findViewById<Button>(R.id.dialog_copy)

        var status = STATUS_ONGOING

        val progressDialog =
            MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(false).create()

        fun updateProgressDialogMessage(newMessage: String) {
            loadingMessage.text = newMessage
            if (!progressDialog.isShowing) {
                progressDialog.show()//更新文本后才显示对话框
            }
        }

        var link = ""
        val thread = Thread {
            var vSmall = versionSmall
            try {
                while (true) {
                    when (status) {
                        STATUS_ONGOING -> {
                            if (mode == MODE_TEST) {
                                link =
                                    "https://downv6.qq.com/qqweb/QQ_1/android_apk/Android_$versionBig.${vSmall}_64.apk"
                            } else if (mode == MODE_UNOFFICIAL) {
                                link =
                                    "https://downv6.qq.com/qqweb/QQ_1/android_apk/Android%20$versionBig.${vSmall}%2064.apk"
                            } else if (mode == MODE_OFFICIAL) {
                                if (link == "") {
                                    link =
                                        "https://downv6.qq.com/qqweb/QQ_1/android_apk/Android_${versionBig}_64.apk"
                                } else if (link.endsWith("HB.apk")) {
                                    status = STATUS_END
                                    continue
                                } else {
                                    link =
                                        "https://downv6.qq.com/qqweb/QQ_1/android_apk/Android_${versionBig}_64_HB.apk"
                                }
                            }
                            runOnUiThread {
                                updateProgressDialogMessage("正在猜测下载地址：$link")
                            }
                            val okHttpClient = OkHttpClient()
                            val request = Request.Builder().url(link).build()
                            val response = okHttpClient.newCall(request).execute()
                            val success = response.isSuccessful
                            if (success) {
                                status = STATUS_PAUSE
                                runOnUiThread {
                                    if (successButton.parent != null) {
                                        (successButton.parent as ViewGroup).removeView(
                                            successButton
                                        )
                                    }

                                    val successMaterialDialog =
                                        MaterialAlertDialogBuilder(this).setTitle("猜测成功")
                                            .setMessage("下载地址：$link")
                                            .setIcon(R.drawable.check_circle).setView(successButton)
                                            .setCancelable(false).show()

                                    // 复制并停止按钮点击事件
                                    copyAndStopButton.setOnClickListener {
                                        copyText(link)
                                        successMaterialDialog.dismiss()
                                        status = STATUS_END
                                    }

                                    // 继续按钮点击事件
                                    continueButton.setOnClickListener {
                                        vSmall += if (!SpUtil.getBoolean(
                                                this@MainActivity, "guessNot5", false
                                            )
                                        ) {
                                            5
                                        } else {
                                            1
                                        }
                                        successMaterialDialog.dismiss()
                                        status = STATUS_ONGOING
                                    }

                                    // 停止按钮点击事件
                                    stopButton.setOnClickListener {
                                        successMaterialDialog.dismiss()
                                        status = STATUS_END
                                    }

                                    // 分享按钮点击事件
                                    shareButton.setOnClickListener {
                                        successMaterialDialog.dismiss()

                                        getFileSizeInMB(link) { appSize ->
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    if (mode == MODE_OFFICIAL) "Android QQ $versionBig 正式版（大小：$appSize MB）\n\n下载地址：$link"
                                                    else "Android QQ $versionBig.$vSmall 测试版（大小：$appSize MB）\n\n下载地址：$link"
                                                )
                                            }
                                            startActivity(
                                                Intent.createChooser(
                                                    shareIntent, "分享到"
                                                )
                                            )
                                            status = STATUS_END
                                        }
                                    }

                                    // 下载按钮点击事件
                                    downloadButton.setOnClickListener {
                                        val request1 = DownloadManager.Request(Uri.parse(link))
                                        val downloadManager =
                                            getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        downloadManager.enqueue(request1)
                                        successMaterialDialog.dismiss()
                                        status = STATUS_END
                                    }

                                }
                            } else {
                                vSmall += if (!SpUtil.getBoolean(
                                        this@MainActivity, "guessNot5", false
                                    )
                                ) {
                                    5
                                } else {
                                    1
                                }
                            }
                        }

                        STATUS_PAUSE -> {
                            sleep(500)
                        }

                        STATUS_END -> {
                            showToast("已停止猜测")
                            progressDialog.dismiss()
                            break
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                dialogError(e)
                showToast("已停止猜测")
                progressDialog.dismiss()
            }
        }


        // AlertDialog
        progressSpinner.visibility = View.VISIBLE
        val buttonCancel = dialogView.findViewById<Button>(R.id.dialog_button_cancel)
        //loadingMessage.text = "正在猜测下载地址"

        buttonCancel.setOnClickListener {
            status = STATUS_END
            progressDialog.dismiss()
        }

        thread.start()


    }


    companion object {
        const val STATUS_ONGOING = 0
        const val STATUS_PAUSE = 1
        const val STATUS_END = 2

        const val MODE_TEST = "测试版"
        const val MODE_OFFICIAL = "正式版"
        const val MODE_UNOFFICIAL = "空格版"  //空格猜版
    }

}
