package com.simplemobiletools.smsmessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.smsmessenger.activities.SignerImpl
import dev.pinkroom.walletconnectkit.WalletConnectButton
import dev.pinkroom.walletconnectkit.WalletConnectKit
import dev.pinkroom.walletconnectkit.WalletConnectKitConfig
import org.ethereumphone.xmtp_android_sdk.MessageCallback
import org.ethereumphone.xmtp_android_sdk.XMTPApi
import org.json.JSONObject
import java.util.*

class XMTPListenService : Service() {

    private val walletconnectconfig = WalletConnectKitConfig(
        context = this,
        bridgeUrl = "https://bridge.walletconnect.org",
        appUrl = "https://ethereumphone.org",
        appName = "ethOS SMS",
        appDescription = "Send SMS and messages over the XMTP App on ethOS"
    )
    private val walletConnectKit by lazy { WalletConnectKit.Builder(walletconnectconfig).build() }
    val mainHandler = Handler(Looper.getMainLooper())


    private val CHANNEL_ID = "XMTPListener"
    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    var xmtpApi : XMTPApi? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("Listener has been started")
        val windowManager: WindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        params.width = 0
        params.height = 0
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL

        val walletConnectButton = WalletConnectButton(this)

        walletConnectButton.start(walletConnectKit) {
            println("Connected_background: $it")
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Listening to new messages")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            startForeground(0, builder.build())


            xmtpApi = XMTPApi(this, SignerImpl(walletConnectKit = walletConnectKit), false)
            val type = object : TypeToken<ArrayList<Long?>?>() {}.type
            val sharedPreferences = getSharedPreferences("shared pref", MODE_PRIVATE)
            val json = sharedPreferences.getString("eth_threads", "")
            if (json != "") {
                val gson = Gson()
                val ethThreadList = gson.fromJson(json, type) as ArrayList<Long>
                val sharedEthAddr = getSharedPreferences("ETHADDR", Context.MODE_PRIVATE)

                ethThreadList.forEach {
                    val targetAddr = sharedEthAddr.getString(it.toString()+"_ethAddress", "0x0")
                    println("Listener: Setting up $it thread and address is $targetAddr")
                    val webview = xmtpApi!!.listenMessages(targetAddr, MessageCallback { from, content ->
                        println("Listener new message: [$from]: $content")
                        createNotificationChannel()
                        showNotification(from, content)
                    })

                    mainHandler.post(object : Runnable {
                        override fun run() {
                            if (targetAddr != null) {
                                getMessagesCheck(targetAddr, it)
                            }
                            mainHandler.postDelayed(this, 1000 * 120)
                        }
                    })
                    //windowManager.addView(webview, params)
                }

                //Thread.sleep(1000000)


            }
        }

        return START_NOT_STICKY
    }

    private fun getMessagesCheck(target: String, threadId: Long) {
        println("Listener getMessages check")
        val sharedEthAddr = getSharedPreferences("ETHADDR", Context.MODE_PRIVATE)
        val newestMessage = sharedEthAddr.getString(threadId.toString()+"_newestMessage", "")
        xmtpApi!!.getMessages(target).whenComplete { arrayList, throwable ->
            println("Listener got messages: ${arrayList.size.toString()}")
            val jsonObject = JSONObject(arrayList.get(arrayList.size-1))
            val content = jsonObject.get("content") as String
            if (content != newestMessage) {
                // Newest message is not newest received
                sharedEthAddr.edit().putString(threadId.toString()+"_newestMessage", content).apply()
                showNotification(jsonObject.get("senderAddress") as String, content)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        mainHandler.removeCallbacksAndMessages(null)
        val intent = Intent(this, RestartServiceReceiver::class.java)
        println("Listener: Wants to stop")
        sendBroadcast(intent)
        stopSelf()
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "XMTP Listener"
            val descriptionText = "Listens to xmtp messages"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }


    private fun showNotification(from: String, content: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("XMTP from ${from.substring(0,5)}")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        val random = Random()
        with(NotificationManagerCompat.from(this)) {
            // notificationId is a unique int for each notification that you must define
            notify(random.nextInt(100000001), builder.build())
        }

    }
}
