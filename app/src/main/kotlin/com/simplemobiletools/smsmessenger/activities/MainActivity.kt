package com.simplemobiletools.smsmessenger.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.Release
import com.simplemobiletools.smsmessenger.BuildConfig
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.XMTPListenService
import com.simplemobiletools.smsmessenger.adapters.ConversationsAdapter
import com.simplemobiletools.smsmessenger.dialogs.ExportMessagesDialog
import com.simplemobiletools.smsmessenger.dialogs.ImportMessagesDialog
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.helpers.*
import com.simplemobiletools.smsmessenger.models.Conversation
import com.simplemobiletools.smsmessenger.models.Events
import dev.pinkroom.walletconnectkit.WalletConnectKit
import dev.pinkroom.walletconnectkit.WalletConnectKitConfig
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.conversations_fastscroller
import kotlinx.android.synthetic.main.activity_main.conversations_list
import kotlinx.android.synthetic.main.activity_main.main_coordinator
import kotlinx.android.synthetic.main.activity_main.no_conversations_placeholder
import kotlinx.android.synthetic.main.activity_main.no_conversations_placeholder_2
import kotlinx.android.synthetic.main.activity_main.walletConnectButton
import org.ethereumphone.xmtp_android_sdk.XMTPApi
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

class MainActivity : SimpleActivity() {
    private val MAKE_DEFAULT_APP_REQUEST = 1
    private val PICK_IMPORT_SOURCE_INTENT = 11
    private val PICK_EXPORT_FILE_INTENT = 21


    private var storedTextColor = 0
    private var storedFontSize = 0
    private var bus: EventBus? = null
    private val smsExporter by lazy { MessagesExporter(this) }
    private val walletconnectconfig = WalletConnectKitConfig(
        context = this,
        bridgeUrl = "https://bridge.walletconnect.org",
        appUrl = "https://ethereumphone.org",
        appName = "ethOS SMS",
        appDescription = "Send SMS and messages over the XMTP App on ethOS"
    )
    private val walletConnectKit by lazy { WalletConnectKit.Builder(walletconnectconfig).build() }


    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()


        if (checkAppSideloading()) {
            return
        }

        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
                }
            } else {
                toast(R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }

        clearAllMessagesIfNeeded()
        loginView()
    }

    fun loginView(){
        if (this.getSystemService("wallet") != null) {
            walletConnectButton.visibility = View.INVISIBLE
            // Show Loading
            val constraintLayout = findViewById<ConstraintLayout>(R.id.backgroundDarkerLayout)
            val progressBar = findViewById<ProgressBar>(R.id.mainProgressBar)
            val textView = findViewById<TextView>(R.id.textView)
            if (!this.getPreferences(MODE_PRIVATE).getBoolean("isInitilized", false)){
                constraintLayout.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                textView.visibility = View.VISIBLE
            }
            val completableFuture = CompletableFuture<String>()
            val xmtpApi = XMTPApi(this, SignerImpl(context = this, isInit = true, initComplete = completableFuture), true)
            completableFuture.whenComplete { s, throwable ->
                // Hide Loading
                constraintLayout.visibility = View.INVISIBLE
                progressBar.visibility = View.INVISIBLE
                textView.visibility = View.INVISIBLE
            }

            val sharedPreferences = this.getPreferences(MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putBoolean("eth_connected", true)
            editor.putBoolean("isInitilized", true)
            editor.apply();
        } else if (!this.getPreferences(MODE_PRIVATE).getBoolean("eth_connected", false)) {
            walletConnectButton.start(walletConnectKit, ::onConnected, ::onDisconnected)
            ConfirmationDialog(
                activity = this,
                message = "For XMTP Messaging to work, please click the WalletConnect Button",
                negative = 0,
                positive = R.string.confirm
            ) {
                println("Confirmed")
            }
        }
    }

    fun onConnected(address:String){
        //go to main activity
        val view = findViewById<View>(R.id.walletConnectButton)
        view.visibility = View.INVISIBLE
        val sharedPreferences = this.getPreferences(MODE_PRIVATE)
        val xmtp_Key = getSharedPreferences("key", Context.MODE_PRIVATE).getString("key", "null")
        if (!sharedPreferences.getBoolean("isInitilized", false)) {
            val xmtpApi = XMTPApi(this, SignerImpl(context = this), true)
        }
        if (xmtp_Key == "null") {
            println("Trying to get XMTP peeraccounts")
            val xmtpApi = XMTPApi(this, SignerImpl(context = this), false)
            xmtpApi.peerAccounts.whenComplete { strings, throwable ->
                println(strings)
            }
        }
        val editor = sharedPreferences.edit()
        editor.putBoolean("eth_connected", true)
        editor.putBoolean("isInitilized", true)
        editor.apply();
    }
    fun onDisconnected(){
        //exit the app
        val view = findViewById<View>(R.id.walletConnectButton)
        view.visibility = View.VISIBLE
        val sharedPreferences = this.getPreferences(MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean("eth_connected", false)
        editor.apply();
    }
    override fun onResume() {
        super.onResume()
        setupToolbar(main_toolbar)
        if (storedTextColor != getProperTextColor()) {
            (conversations_list.adapter as? ConversationsAdapter)?.updateTextColor(getProperTextColor())
        }

        if (storedFontSize != config.fontSize) {
            (conversations_list.adapter as? ConversationsAdapter)?.updateFontSize()
        }

        (conversations_list.adapter as? ConversationsAdapter)?.updateDrafts()
        updateTextColors(main_coordinator)

        val properPrimaryColor = getProperPrimaryColor()
        no_conversations_placeholder_2.setTextColor(properPrimaryColor)
        no_conversations_placeholder_2.underlineText()
        conversations_fastscroller.updateColors(properPrimaryColor)
        checkShortcut()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
        val intent = Intent(this, XMTPListenService::class.java)
        //startService(intent)
    }

    override fun onStop() {
        super.onStop()
        val intent = Intent(this, XMTPListenService::class.java)
        //startService(intent)
    }


    private fun setupOptionsMenu() {
        main_toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> launchSearch()
                R.id.settings -> launchSettings()
                R.id.export_messages -> tryToExportMessages()
                R.id.import_messages -> tryImportMessages()
                R.id.about -> launchAbout()
                R.id.showXMTPConvs -> launchShowXMTPConvos()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun launchShowXMTPConvos() {
        val intent = Intent(this, ShowRequestConvosActivity::class.java)
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                askPermissions()
            } else {
                finish()
            }
        } else if (requestCode == PICK_IMPORT_SOURCE_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            tryImportMessagesFromFile(resultData.data!!)
        } else if (requestCode == PICK_EXPORT_FILE_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            val outputStream = contentResolver.openOutputStream(resultData.data!!)
            exportMessagesTo(outputStream)
        }
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedFontSize = config.fontSize
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional. If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (e: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun initMessenger() {
        checkWhatsNewDialog()
        storeStateVariables()
        getCachedConversations()

        no_conversations_placeholder_2.setOnClickListener {
            launchNewConversation()
        }

        conversations_fab.setOnClickListener {
            launchNewConversation()
        }
    }

    private fun removeDuplicates(input: ArrayList<Conversation>): ArrayList<Conversation> {
        if (input.size == 1) {
            return input
        }
        val output = input
        output.forEach {
            input.forEach { orIt ->
                if (it.title.contains("Ethereum") && it.title == orIt.title) {
                    output.remove(it)
                }
            }
        }
        return output
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            val sharedPreferences1 = this.getPreferences(MODE_PRIVATE)

            var conversations = try {
                conversationsDB.getAll().toMutableList() as ArrayList<Conversation>
            } catch (e: Exception) {
                ArrayList()
            }

            if (sharedPreferences1.getBoolean("eth_connected", false)) {
                val gson = Gson()
                val type = object : TypeToken<ArrayList<Long?>?>() {}.type
                val sharedPreferences = getSharedPreferences("shared pref", MODE_PRIVATE)
                val sharedPreferences2 = getSharedPreferences("ETHADDR", Context.MODE_PRIVATE)
                val json = sharedPreferences.getString("eth_threads", "")
                val outputList = ArrayList<Conversation>()
                try {
                    if (json != "") {
                        val ethThreadList = gson.fromJson(json, type) as ArrayList<Long>
                        ethThreadList.forEach {
                            val snippet = if (sharedPreferences2.getString(it.toString()+"_newestMessage", "")!!.contains("<tx_msg>")) {
                                val realContent = sharedPreferences2.getString(it.toString()+"_newestMessage", "")!!.substringAfter("<tx_msg>").substringBefore("</tx_msg>")
                                val jsonObject = JSONObject(realContent)
                                "${jsonObject.getString("value")} eth"
                            } else {
                                sharedPreferences2.getString(it.toString()+"_newestMessage", "")!!
                            }
                            outputList.add(
                                Conversation(
                                    threadId = it,
                                    snippet = snippet,
                                    date = Instant.now().epochSecond.toInt(),
                                    read = true,
                                    title = sharedPreferences2.getString(it.toString()+"_title", "")!!,
                                    photoUri = "",
                                    isGroupConversation = false,
                                    phoneNumber = ""
                                )
                            )
                        }
                        //val outstuff = removeDuplicates(outputList)
                        conversations.addAll(outputList)
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                }
                updateUnreadCountBadge(conversations)
                runOnUiThread {
                    setupConversations(conversations)
                    getNewConversations(conversations, outputList)
                }
            } else {
                updateUnreadCountBadge(conversations)
                runOnUiThread {
                    setupConversations(conversations)
                    getNewConversations(conversations, null)
                }
            }



        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>, ethConversations: ArrayList<Conversation>?) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)

            if(ethConversations != null) {
                conversations.addAll(ethConversations)
            }

            runOnUiThread {
                setupConversations(conversations)
            }

            conversations.forEach { clonedConversation ->
                if (!cachedConversations.map { it.threadId }.contains(clonedConversation.threadId)) {
                    conversationsDB.insertOrUpdate(clonedConversation)
                    cachedConversations.add(clonedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                if (!conversations.map { it.threadId }.contains(cachedConversation.threadId)) {
                    conversationsDB.deleteThreadId(cachedConversation.threadId)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val conv = conversations.firstOrNull { it.threadId == cachedConversation.threadId && it.toString() != cachedConversation.toString() }
                if (conv != null) {
                    conversationsDB.insertOrUpdate(conv)
                }
            }

            if (config.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = getMessages(threadId, false)
                    messages.chunked(30).forEach { currentMessages ->
                        messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun launchThreadActivity(name: String, ethAddress: String, threadId: Long) {
        hideKeyboard()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, text)
            putExtra(THREAD_NUMBER, "")
            putExtra("fromMain", true)
            putExtra("isEthereum", ethAddress != "0x0")
            putExtra("eth_address", ethAddress)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
        }
    }

    private fun setupConversations(conversations: ArrayList<Conversation>) {
        val hasConversations = conversations.isNotEmpty()
        val sortedConversations = conversations.sortedWith(
            compareByDescending<Conversation> { config.pinnedConversations.contains(it.threadId.toString()) }
                .thenByDescending { it.date }
        ).toMutableList() as ArrayList<Conversation>

        conversations_fastscroller.beVisibleIf(hasConversations)
        no_conversations_placeholder.beGoneIf(hasConversations)
        no_conversations_placeholder_2.beGoneIf(hasConversations)

        if (!hasConversations && config.appRunCount == 1) {
            no_conversations_placeholder.text = getString(R.string.loading_messages)
            no_conversations_placeholder_2.beGone()
        }

        val currAdapter = conversations_list.adapter
        if (currAdapter == null) {
            hideKeyboard()
            ConversationsAdapter(this, sortedConversations, conversations_list) {
                if (this.getPreferences(MODE_PRIVATE).getBoolean("eth_connected", false)) {
                    val sharedPreferences = getSharedPreferences("ETHADDR", Context.MODE_PRIVATE)
                    if (getSystemService("wallet") == null) {
                        launchThreadActivity(
                            name = (it as Conversation).title,
                            ethAddress = sharedPreferences.getString((it as Conversation).threadId.toString()+"_ethAddress", walletConnectKit.address!!)!!,
                            threadId = (it as Conversation).threadId
                        )
                    } else {
                        launchThreadActivity(
                            name = (it as Conversation).title,
                            ethAddress = sharedPreferences.getString((it as Conversation).threadId.toString()+"_ethAddress", "0x0")!!,
                            threadId = (it as Conversation).threadId
                        )
                    }

                    /**
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as Conversation).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra("fromMain", true)
                        startActivity(this)
                    }
                    */
                } else {
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as Conversation).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        startActivity(this)
                    }
                }
            }.apply {
                conversations_list.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                conversations_list.scheduleLayoutAnimation()
            }
        } else {
            try {
                (currAdapter as ConversationsAdapter).updateConversations(sortedConversations)
                if (currAdapter.conversations.isEmpty()) {
                    conversations_fastscroller.beGone()
                    no_conversations_placeholder.text = getString(R.string.no_conversations_found)
                    no_conversations_placeholder.beVisible()
                    no_conversations_placeholder_2.beVisible()
                }
            } catch (ignored: Exception) {
            }
        }
    }


    private fun launchNewConversation() {
        hideKeyboard()
        Intent(this, NewConversationActivity::class.java).apply {
            startActivity(this)
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcut() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val newConversation = getCreateNewContactShortcut(appIconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = Arrays.asList(newConversation)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }



    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable = resources.getDrawable(R.drawable.shortcut_plus)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_plus_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }


    private fun launchSearch() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SearchActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or LICENSE_SMS_MMS or LICENSE_INDICATOR_FAST_SCROLL

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_2_title, R.string.faq_2_text),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun tryToExportMessages() {
        if (isQPlus()) {
            ExportMessagesDialog(this, config.lastExportPath, true) { file ->
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = EXPORT_MIME_TYPE
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        startActivityForResult(this, PICK_EXPORT_FILE_INTENT)
                    } catch (e: ActivityNotFoundException) {
                        toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    ExportMessagesDialog(this, config.lastExportPath, false) { file ->
                        getFileOutputStream(file.toFileDirItem(this), true) { outStream ->
                            exportMessagesTo(outStream)
                        }
                    }
                }
            }
        }
    }

    private fun exportMessagesTo(outputStream: OutputStream?) {
        toast(R.string.exporting)
        ensureBackgroundThread {
            smsExporter.exportMessages(outputStream) {
                val toastId = when (it) {
                    MessagesExporter.ExportResult.EXPORT_OK -> R.string.exporting_successful
                    else -> R.string.exporting_failed
                }

                toast(toastId)
            }
        }
    }

    private fun tryImportMessages() {
        if (isQPlus()) {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = EXPORT_MIME_TYPE

                try {
                    startActivityForResult(this, PICK_IMPORT_SOURCE_INTENT)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        } else {
            handlePermission(PERMISSION_READ_STORAGE) {
                if (it) {
                    importEvents()
                }
            }
        }
    }

    private fun importEvents() {
        FilePickerDialog(this) {
            showImportEventsDialog(it)
        }
    }

    private fun showImportEventsDialog(path: String) {
        ImportMessagesDialog(this, path)
    }

    private fun tryImportMessagesFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> showImportEventsDialog(uri.path!!)
            "content" -> {
                val tempFile = getTempFile("messages", "backup.json")
                if (tempFile == null) {
                    toast(R.string.unknown_error_occurred)
                    return
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val out = FileOutputStream(tempFile)
                    inputStream!!.copyTo(out)
                    showImportEventsDialog(tempFile.absolutePath)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
            else -> toast(R.string.invalid_file_format)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshMessages(event: Events.RefreshMessages) {
        initMessenger()
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(48, R.string.release_48))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
