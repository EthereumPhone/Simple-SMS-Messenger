package com.simplemobiletools.smsmessenger.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ProgressBar
import android.widget.RelativeLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.klinker.android.send_message.Transaction
import com.klinker.android.send_message.Utils.getNumPages
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.dialogs.SendEthDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.PhoneNumber
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.commons.views.MyButton
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.XMTPListenService
import com.simplemobiletools.smsmessenger.adapters.AutoCompleteTextViewAdapter
import com.simplemobiletools.smsmessenger.adapters.ThreadAdapter
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.helpers.*
import com.simplemobiletools.smsmessenger.models.*
import com.simplemobiletools.smsmessenger.receivers.SmsStatusDeliveredReceiver
import com.simplemobiletools.smsmessenger.receivers.SmsStatusSentReceiver
import dev.pinkroom.walletconnectkit.WalletConnectButton
import dev.pinkroom.walletconnectkit.WalletConnectKit
import dev.pinkroom.walletconnectkit.WalletConnectKitConfig
import kotlinx.android.synthetic.main.activity_thread.*
import kotlinx.android.synthetic.main.item_attachment.view.*
import kotlinx.android.synthetic.main.item_selected_contact.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.ethereumphone.xmtp_android_sdk.MessageCallback
import org.ethereumphone.xmtp_android_sdk.Signer
import org.ethereumphone.xmtp_android_sdk.XMTPApi
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import org.web3j.crypto.Keys.toChecksumAddress
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthGetTransactionCount
import org.web3j.protocol.http.HttpService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CompletableFuture


internal class SignerImpl(context: Context, isInit: Boolean = false, initComplete: CompletableFuture<String>? = null) : Signer {
    val cls = Class.forName("android.os.WalletProxy")
    val createSession = cls.declaredMethods[1]
    val getUserDecision = cls.declaredMethods[3]
    val hasBeenFulfilled = cls.declaredMethods[4]
    val sendTransaction =  cls.declaredMethods[5]
    val signMessageSys = cls.declaredMethods[6]
    val getAddress = cls.declaredMethods[2]
    val service = context.getSystemService("wallet")
    val session = createSession.invoke(service) as String
    val isInit = isInit
    val context: Context = context
    val initComplete: CompletableFuture<String>? = initComplete

    override fun signMessage(msg: String): String {
        val requestID = signMessageSys.invoke(service, session, msg) as String

        Thread.sleep(300)

        while(true) {
            val output = hasBeenFulfilled.invoke(service, requestID)
            if (output != null && output != "notfulfilled") {
                break;
            }
            Thread.sleep(1000)
        }

        if (msg.contains("Enable") && isInit && initComplete != null) {
            initComplete.complete("")
        }

        return hasBeenFulfilled.invoke(service, requestID) as String
    }

    override fun getAddress(): String {
        val requestID = getAddress.invoke(service, session) as String

        Thread.sleep(200)

        while(hasBeenFulfilled.invoke(service, requestID) == "notfulfilled") { }
        val address = hasBeenFulfilled.invoke(service, requestID) as String
        return address
    }

    fun sendTransaction(address: String, value: String, data: String, nonce: String, gasPrice: String, gasAmount: String): String {
        val requestID = sendTransaction.invoke(service, session, address, value, data, nonce, gasPrice, gasAmount, 1) as String


        while(true) {
            val result = hasBeenFulfilled.invoke(service, requestID)
            if (result != null && result != "notfulfilled") {
                break;
            }
        }

        return hasBeenFulfilled.invoke(service, requestID) as String
    }


}


class ThreadActivity : SimpleActivity() {
    private val MIN_DATE_TIME_DIFF_SECS = 300
    private val PICK_ATTACHMENT_INTENT = 1
    private val PICK_SAVE_FILE_INTENT = 11
    private val TAKE_PHOTO_INTENT = 42

    private val TYPE_TAKE_PHOTO = 12
    private val TYPE_CHOOSE_PHOTO = 13
    private val TYPE_SEND_ETH = 14

    private var threadId = 0L
    private var currentSIMCardIndex = 0
    private var isActivityVisible = false
    private var refreshedSinceSent = false
    private var threadItems = ArrayList<ThreadItem>()
    private var bus: EventBus? = null
    private var participants = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var messages = ArrayList<Message>()
    private val availableSIMCards = ArrayList<SIMCard>()
    private var attachmentSelections = mutableMapOf<String, AttachmentSelection>()
    private val imageCompressor by lazy { ImageCompressor(this) }
    private var lastAttachmentUri: String? = null
    private var capturedImageUri: Uri? = null
    private var loadingOlderMessages = false
    private var allMessagesFetched = false
    private var oldestMessageDate = -1
    private var isEthereum = false
    private val participantsXMTPAddr = HashMap<String, String>()
    private var targetEthAddress = ""

    private val allImageUris = ArrayList<Uri>()

    private val walletconnectconfig = WalletConnectKitConfig(
        context = this,
        bridgeUrl = "https://bridge.walletconnect.org",
        appUrl = "https://ethereumphone.org",
        appName = "ethOS SMS",
        appDescription = "Send SMS and messages over the XMTP App on ethOS"
    )
    private val walletConnectKit by lazy { WalletConnectKit.Builder(walletconnectconfig).build() }

    private var xmtpApi: XMTPApi? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thread)
        setupOptionsMenu()
        refreshMenuItems()

        val walletConnectButton = findViewById<WalletConnectButton>(R.id.walletConnectButtonThread)
        if (getSystemService("wallet") != null) {
            walletConnectButton.visibility = View.INVISIBLE
        }
        walletConnectButton.start(walletConnectKit) {
            walletConnectButton.visibility = View.INVISIBLE
        }

        val progressBar: ProgressBar = findViewById(R.id.progressBarLoadingXMTP)
        progressBar.visibility = View.INVISIBLE
        isEthereum = intent.getBooleanExtra("isEthereum", false)


        // Test: 0x2374eFc48c028C98e259a7bBcba336d6acFF103c
        //xmtpMessages = xmtpGetMessages("0x2374eFc48c028C98e259a7bBcba336d6acFF103c")

        if (!isEthereum && this.getSharedPreferences("PREF", MODE_PRIVATE).getBoolean(threadId.toString(), false)){
            xmtpApi = XMTPApi(this, SignerImpl(context = this), false)
            isEthereum = true
            val address = this.getSharedPreferences("PREF", MODE_PRIVATE).getString(threadId.toString()+"_address", "0x0")
            if (address != null) {
                participants.get(0).ethAddress = checkENSDomain(address)
            }
        }

        if(isEthereum) {
            targetEthAddress = checkENSDomain(intent.getStringExtra("eth_address").toString())
            if (intent.getBooleanExtra("fromNewConvo", false)) {
                val contact = SimpleContact(0, 0, targetEthAddress, "", ArrayList(), ArrayList(), ArrayList(), targetEthAddress)
                participants.add(contact)
            }

            val intentNumbers = getPhoneNumbersFromIntent()
            this.getSharedPreferences("PREF", MODE_PRIVATE).edit().putString(threadId.toString()+"_phonenum", intentNumbers.get(0)).apply()
            progressBar.visibility = View.VISIBLE
            xmtpApi!!.getMessages(targetEthAddress).whenComplete { p0, p1 ->
                runOnUiThread {
                    Log.d("Length of the array", p0.size.toString())
                    val output = ArrayList<ThreadItem>()
                    if(p0.size == 0) {
                        setupAdapter(xmtpMessages = output)
                        progressBar.visibility = View.INVISIBLE
                        saveThreadAsEth()
                        return@runOnUiThread
                    }
                    var numberOfChat: Long = 1
                    p0.forEach {
                        val jsonObject = JSONObject(it)
                        if (jsonObject.has("error")) {
                            return@forEach
                        }
                        val senderAddress = jsonObject.get("senderAddress")
                        val senderName = participants.get(0).name
                        val content = jsonObject.get("content") as String
                        if (content.contains("tx_msg")) {
                            val realContent = content.substringAfter("<tx_msg>").substringBefore("</tx_msg>")
                            val jsonObject = JSONObject(realContent)
                            val msg = createMsgImageFromText("${jsonObject.getString("value")} eth", this, numberOfChat, targetEthAddress != senderAddress)
                            output.add(msg)
                            numberOfChat += 1
                            return@forEach
                        }
                        val type = if (targetEthAddress == senderAddress) {
                            1
                        } else {
                            2
                        }
                        val message = Message(
                            attachment = null,
                            body = content,
                            date = Instant.now().epochSecond.toInt(),
                            id = numberOfChat,
                            isMMS = false,
                            participants = participants,
                            read = false,
                            senderName = senderName,
                            senderPhotoUri = "",
                            status = -1,
                            subscriptionId = -1,
                            threadId = threadId,
                            type = type
                        )
                        output.add(message)
                        numberOfChat += 1
                    }
                    setupAdapter(xmtpMessages = output)
                    saveThreadList()
                    setupSaves()
                    progressBar.visibility = View.INVISIBLE
                }
                if (targetEthAddress != null) {
                    setupListener(targetEthAddress, false)
                }
            }

        }


        val extras = intent.extras
        if (extras == null) {
            toast("Extra null")
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }


        clearAllMessagesIfNeeded()
        threadId = intent.getLongExtra(THREAD_ID, 0L)
        intent.getStringExtra(THREAD_TITLE)?.let {
            thread_toolbar.title = it
        }

        bus = EventBus.getDefault()
        bus!!.register(this)
        handlePermission(PERMISSION_READ_PHONE_STATE) {
            if (it) {
                setupButtons()
                setupCachedMessages {
                    val searchedMessageId = intent.getLongExtra(SEARCHED_MESSAGE_ID, -1L)
                    intent.removeExtra(SEARCHED_MESSAGE_ID)
                    if (searchedMessageId != -1L) {
                        val index = threadItems.indexOfFirst { (it as? Message)?.id == searchedMessageId }
                        if (index != -1) {
                            thread_messages_list.smoothScrollToPosition(index)
                        }
                    }

                    isEthereum = participants.size == 1 && checkENSDomain(participants.get(0).ethAddress) != "0x0"

                    if (!isEthereum && this.getSharedPreferences("PREF", MODE_PRIVATE).getBoolean(threadId.toString(), false)){
                        isEthereum = true
                        val address = this.getSharedPreferences("PREF", MODE_PRIVATE).getString(threadId.toString()+"_address", "0x0")
                        if (address != null) {
                            participants.get(0).ethAddress = address
                        }
                    }

                    setupThread()
                }
            } else {
                finish()
            }
        }
    }

    private fun checkENSDomain(input: String): String {
        if (input.endsWith(".eth")) {
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)

            val web3j = Web3j.build(HttpService("https://cloudflare-eth.com/"))
            val ensResolver = ENSResolver(web3j)
            return try {
                val address = toChecksumAddress(ensResolver.resolve(input))
                address
            } catch (e: Exception) {
                e.printStackTrace()
                input
            }
        } else {
            return input
        }
    }

    private fun saveThreadAsEth() {
        val gson = Gson()
        val sharedPreferences = getSharedPreferences("shared pref", MODE_PRIVATE)
        val json = sharedPreferences.getString("eth_threads", "")
        if (json != "") {
            val type = object : TypeToken<ArrayList<Long?>?>() {}.type
            try {
                val ethThreadList = gson.fromJson(json, type) as ArrayList<Long>
                if(!ethThreadList.contains(threadId)) {
                    ethThreadList.add(threadId)
                    val editor = sharedPreferences.edit()
                    editor.putString("eth_threads", gson.toJson(ethThreadList))
                    editor.apply()
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        } else {
            val ethThreadList = ArrayList<Long>()
            ethThreadList.add(threadId)
            val editor = sharedPreferences.edit()
            editor.putString("eth_threads", gson.toJson(ethThreadList))
            editor.apply()
        }
    }

    private fun setupSaves() {
        val ethAddress = checkENSDomain(intent.getStringExtra("eth_address").toString())
        val sharedPreferences = getSharedPreferences("ETHADDR", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(threadId.toString()+"_ethAddress", ethAddress)
        val titleString = thread_toolbar.title.toString()
        editor.putString(threadId.toString()+"_title", titleString)
        if (threadItems.size > 0) {
            editor.putString(threadId.toString()+"_newestMessage", (threadItems.get(threadItems.size-1) as Message).body)
        }
        editor.apply()
        val sendButton = findViewById<MyButton>(R.id.thread_send_message)
        sendButton.text = "XMTP"
    }

    private fun saveEthContact() {
        val gson = Gson()
        val sharedPreferences = getSharedPreferences("CONTACT", Context.MODE_PRIVATE)

        try {
            val editor = sharedPreferences.edit()
            val json = gson.toJson(participants)
            val key = threadId.toString()+"_ethContact"
            editor.putString(key, json)
            editor.apply()
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadEthContact() {
        val gson = Gson()
        val sharedPreferences = getSharedPreferences("CONTACT", Context.MODE_PRIVATE)
        val type = object : TypeToken<ArrayList<SimpleContact?>?>() {}.type
        try {
            val key = threadId.toString()+"_ethContact"
            val json = sharedPreferences.getString(key, "")
            participants = gson.fromJson(json, type)
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }


    override fun onResume() {
        super.onResume()
        setupToolbar(thread_toolbar, NavigationIcon.Arrow)

        val smsDraft = getSmsDraft(threadId)
        if (smsDraft != null) {
            thread_type_message.setText(smsDraft)
        }
        isActivityVisible = true
        if(isEthereum) {
           loadThreadList()
        }
    }

    fun setupListener(ethAddress: String, lookup: Boolean) {
        val xmtpAddr = if(lookup) {
            participantsXMTPAddr.get(ethAddress.lowercase())
        } else {
            ethAddress
        }
        runOnUiThread {
            xmtpApi!!.listenMessages(checkENSDomain(xmtpAddr!!), MessageCallback { from, content ->
                println("New message from $from: $content")
                if ((threadItems.get(threadItems.size.toInt()-1) as Message).body == content) {
                    return@MessageCallback
                }
                val type = if (ethAddress == from) {
                    1
                } else {
                    2
                }
                val message = Message(
                    attachment = null,
                    body = content,
                    date = Instant.now().epochSecond.toInt(),
                    id = threadItems.size.toLong(),
                    isMMS = false,
                    participants = participants,
                    read = false,
                    senderName = participants.get(0).name,
                    senderPhotoUri = "",
                    status = -1,
                    subscriptionId = -1,
                    threadId = threadId,
                    type = type
                )
                threadItems.add(message)
                setupAdapter(xmtpMessages = threadItems)
                saveThreadList()
                setupSaves()
            })
        }
    }

    override fun onPause() {
        super.onPause()

        if (thread_type_message.value != "" && attachmentSelections.isEmpty()) {
            saveSmsDraft(thread_type_message.value, threadId)
        } else {
            deleteSmsDraft(threadId)
        }
        if(!isEthereum) {
            bus?.post(Events.RefreshMessages())
        } else {
            saveThreadList()
        }


        isActivityVisible = false

    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isEthereum) {
            bus?.unregister(this)
        } else {
            saveThreadList()
            val intent = Intent(this, XMTPListenService::class.java)
            startService(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        if(isEthereum) {
            allImageUris.forEach {
                try {
                    getPathFromURI(it)?.let { it1 -> File(it1).delete() }
                } catch(e: android.database.CursorIndexOutOfBoundsException) {
                    e.printStackTrace()
                }
            }
            saveThreadList()
            val intent = Intent(this, XMTPListenService::class.java)
            startService(intent)
        }
    }

    private fun saveThreadList() {
        // Saving thread
        val sharedPreferences = getSharedPreferences("shared pref", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()
        val json = gson.toJson(threadItems)
        editor.putString(targetEthAddress, json)
        // Saving newest message
        if (threadItems.size > 0) {
            editor.putString(threadId.toString()+"_newestMessage", (threadItems.get(threadItems.size-1) as Message).body)
        }
        editor.apply()
    }

    private fun loadThreadList() {
        val sharedPreferences = getSharedPreferences("shared pref", MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPreferences.getString(targetEthAddress, "")
        if (json != "") {
            val type = object : TypeToken<ArrayList<Message?>?>() {}.type
            try {
                threadItems = gson.fromJson(json, type)
                setupAdapter(xmtpMessages = threadItems)
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }

    }

    private fun refreshMenuItems() {
        val firstPhoneNumber = participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.value
        thread_toolbar.menu.apply {
            findItem(R.id.delete).isVisible = threadItems.isNotEmpty()
            findItem(R.id.block_number).isVisible = isNougatPlus()
            findItem(R.id.dial_number).isVisible = participants.size == 1
            findItem(R.id.mark_as_unread).isVisible = threadItems.isNotEmpty()

            // allow saving number in cases when we dont have it stored yet and it is a casual readable number
            findItem(R.id.add_number_to_contact).isVisible = participants.size == 1 && participants.first().name == firstPhoneNumber && firstPhoneNumber.any {
                it.isDigit()
            }
        }
    }

    private fun setupOptionsMenu() {
        thread_toolbar.setOnMenuItemClickListener { menuItem ->
            if (participants.isEmpty()) {
                return@setOnMenuItemClickListener true
            }

            when (menuItem.itemId) {
                R.id.block_number -> blockNumber()
                R.id.delete -> askConfirmDelete()
                R.id.add_number_to_contact -> addNumberToContact()
                R.id.dial_number -> dialNumber()
                R.id.manage_people -> managePeople()
                R.id.mark_as_unread -> markAsUnread()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == TAKE_PHOTO_INTENT) {
            addAttachment(capturedImageUri!!)
        } else if (requestCode == PICK_ATTACHMENT_INTENT && resultData != null && resultData.data != null) {
            addAttachment(resultData.data!!)
        } else if (requestCode == PICK_SAVE_FILE_INTENT && resultData != null && resultData.data != null) {
            saveAttachment(resultData)
        }
    }

    private fun onHomePressed() {
        hideKeyboard()
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(this)
        }
        finish()
    }

    private fun setupCachedMessages(callback: () -> Unit) {
        ensureBackgroundThread {
            messages = try {
                messagesDB.getThreadMessages(threadId).toMutableList() as ArrayList<Message>
            } catch (e: Exception) {
                ArrayList()
            }

            messages.sortBy { it.date }
            if (messages.size > MESSAGES_LIMIT) {
                messages = ArrayList(messages.takeLast(MESSAGES_LIMIT))
            }

            setupParticipants()
            setupAdapter(ArrayList())

            runOnUiThread {
                if (messages.isEmpty()) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    thread_type_message.requestFocus()
                }

                setupThreadTitle()
                setupSIMSelector()
                updateMessageType()
                callback()
            }
        }
    }

    private fun setupThread() {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)

            val cachedMessagesCode = messages.clone().hashCode()
            messages = getMessages(threadId, true)

            val hasParticipantWithoutName = participants.any {
                it.phoneNumbers.map { it.normalizedNumber }.contains(it.name)
            }

            try {
                if (participants.isNotEmpty() && messages.hashCode() == cachedMessagesCode && !hasParticipantWithoutName) {
                    if (!isEthereum){
                        val tempCopy = ArrayList<ThreadItem>()
                        messages.forEach {
                            tempCopy.add(it)
                        }
                        setupAdapter(tempCopy)
                    }
                    return@ensureBackgroundThread
                }
            } catch (ignored: Exception) {
            }

            setupParticipants()

            // check if no participant came from a privately stored contact in Simple Contacts
            if (privateContacts.isNotEmpty()) {
                val senderNumbersToReplace = HashMap<String, String>()
                participants.filter { it.doesHavePhoneNumber(it.name) }.forEach { participant ->
                    privateContacts.firstOrNull { it.doesHavePhoneNumber(participant.phoneNumbers.first().normalizedNumber) }?.apply {
                        senderNumbersToReplace[participant.phoneNumbers.first().normalizedNumber] = name
                        participant.name = name
                        participant.photoUri = photoUri
                    }
                }

                messages.forEach { message ->
                    if (senderNumbersToReplace.keys.contains(message.senderName)) {
                        message.senderName = senderNumbersToReplace[message.senderName]!!
                    }
                }
            }

            if (participants.isEmpty()) {
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val number = intent.getStringExtra(THREAD_NUMBER)
                if (number == null) {
                    toast(R.string.unknown_error_occurred)
                    finish()
                    return@ensureBackgroundThread
                }

                val phoneNumber = PhoneNumber(number, 0, "", number)
                val contact = SimpleContact(0, 0, name, "", arrayListOf(phoneNumber), ArrayList(), ArrayList(), "0x0")
                participants.add(contact)
            }

            messages.chunked(30).forEach { currentMessages ->
                messagesDB.insertMessages(*currentMessages.toTypedArray())
            }

            setupAttachmentSizes()
            if (!isEthereum) {
                val tempCopy = ArrayList<ThreadItem>()
                messages.forEach {
                    tempCopy.add(it)
                }
                setupAdapter(tempCopy)
            }
            runOnUiThread {
                setupThreadTitle()
                setupSIMSelector()
                val sendButton = findViewById<MyButton>(R.id.thread_send_message)
                if (isEthereum) {
                    sendButton.text = "XMTP"
                } else {
                    sendButton.text = "SMS"
                }
            }
        }
    }

    private fun setupAdapter(xmtpMessages: ArrayList<ThreadItem>) {

        if (xmtpMessages.size > 0) {
            threadItems = xmtpMessages
        }

        runOnUiThread {
            refreshMenuItems()

            val currAdapter = thread_messages_list.adapter
            if (currAdapter == null) {
                ThreadAdapter(this, threadItems, thread_messages_list) {
                    (it as? ThreadError)?.apply {
                        thread_type_message.setText(it.messageText)
                    }
                }.apply {
                    thread_messages_list.adapter = this
                }

                thread_messages_list.endlessScrollListener = object : MyRecyclerView.EndlessScrollListener {
                    override fun updateBottom() {}

                    override fun updateTop() {
                        fetchNextMessages()
                    }
                }
            } else {
                (currAdapter as ThreadAdapter).updateMessages(threadItems)
            }
        }

        SimpleContactsHelper(this).getAvailableContacts(false) { contacts ->
            contacts.addAll(privateContacts)
            runOnUiThread {
                val adapter = AutoCompleteTextViewAdapter(this, contacts)
                add_contact_or_number.setAdapter(adapter)
                add_contact_or_number.imeOptions = EditorInfo.IME_ACTION_NEXT
                add_contact_or_number.setOnItemClickListener { _, _, position, _ ->
                    val currContacts = (add_contact_or_number.adapter as AutoCompleteTextViewAdapter).resultList
                    val selectedContact = currContacts[position]
                    addSelectedContact(selectedContact)
                }

                add_contact_or_number.onTextChangeListener {
                    confirm_inserted_number.beVisibleIf(it.length > 2)
                }
            }
        }

        confirm_inserted_number?.setOnClickListener {
            val number = add_contact_or_number.value
            val phoneNumber = PhoneNumber(number, 0, "", number)
            val contact = SimpleContact(number.hashCode(), number.hashCode(), number, "", arrayListOf(phoneNumber), ArrayList(), ArrayList(), "0x0")
            addSelectedContact(contact)
        }

    }

    private fun fetchNextMessages() {
        if (messages.isEmpty() || allMessagesFetched || loadingOlderMessages) {
            return
        }

        val dateOfFirstItem = messages.first().date
        if (oldestMessageDate == dateOfFirstItem) {
            allMessagesFetched = true
            return
        }

        oldestMessageDate = dateOfFirstItem
        loadingOlderMessages = true

        ensureBackgroundThread {
            val firstItem = messages.first()
            val olderMessages = getMessages(threadId, true, oldestMessageDate)

            messages.addAll(0, olderMessages)
            threadItems = getThreadItems()

            allMessagesFetched = olderMessages.size < MESSAGES_LIMIT || olderMessages.size == 0

            runOnUiThread {
                loadingOlderMessages = false
                val itemAtRefreshIndex = threadItems.indexOfFirst { it == firstItem }
                (thread_messages_list.adapter as ThreadAdapter).apply {
                    updateMessages(threadItems, itemAtRefreshIndex)
                }
            }
        }
    }

    private fun setupButtons() {
        updateTextColors(thread_holder)
        val textColor = getProperTextColor()
        thread_send_message.apply {
            setTextColor(textColor)
            compoundDrawables.forEach { it?.applyColorFilter(textColor) }
        }
        confirm_manage_contacts.applyColorFilter(textColor)
        thread_add_attachment.applyColorFilter(textColor)

        val properPrimaryColor = getProperPrimaryColor()
        thread_messages_fastscroller.updateColors(properPrimaryColor)

        thread_character_counter.beVisibleIf(config.showCharacterCounter)
        thread_character_counter.setTextSize(TypedValue.COMPLEX_UNIT_PX, getTextSize())

        thread_type_message.setTextSize(TypedValue.COMPLEX_UNIT_PX, getTextSize())
        thread_send_message.setOnClickListener {
            sendMessage()
        }

        thread_send_message.isClickable = false
        thread_type_message.onTextChangeListener {
            checkSendMessageAvailability()
            val messageString = if (config.useSimpleCharacters) it.normalizeString() else it
            val messageLength = SmsMessage.calculateLength(messageString, false)
            thread_character_counter.text = "${messageLength[2]}/${messageLength[0]}"
        }

        confirm_manage_contacts.setOnClickListener {
            hideKeyboard()
            thread_add_contacts.beGone()

            val numbers = HashSet<String>()
            participants.forEach { contact ->
                contact.phoneNumbers.forEach {
                    numbers.add(it.normalizedNumber)
                }
            }
            val newThreadId = getThreadId(numbers)
            if (threadId != newThreadId) {
                hideKeyboard()
                Intent(this, ThreadActivity::class.java).apply {
                    putExtra(THREAD_ID, newThreadId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(this)
                }
            }
        }

        thread_type_message.setText(intent.getStringExtra(THREAD_TEXT))
        thread_add_attachment.setOnClickListener {
            takeOrPickPhotoVideo()
        }

        if (intent.extras?.containsKey(THREAD_ATTACHMENT_URI) == true) {
            val uri = Uri.parse(intent.getStringExtra(THREAD_ATTACHMENT_URI))
            addAttachment(uri)
        } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
            (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
                addAttachment(it)
            }
        }
    }

    private fun setupAttachmentSizes() {
        messages.filter { it.attachment != null }.forEach { message ->
            message.attachment!!.attachments.forEach {
                try {
                    if (it.mimetype.startsWith("image/")) {
                        val fileOptions = BitmapFactory.Options()
                        fileOptions.inJustDecodeBounds = true
                        BitmapFactory.decodeStream(contentResolver.openInputStream(it.getUri()), null, fileOptions)
                        it.width = fileOptions.outWidth
                        it.height = fileOptions.outHeight
                    } else if (it.mimetype.startsWith("video/")) {
                        val metaRetriever = MediaMetadataRetriever()
                        metaRetriever.setDataSource(this, it.getUri())
                        it.width = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                        it.height = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    }

                    if (it.width < 0) {
                        it.width = 0
                    }

                    if (it.height < 0) {
                        it.height = 0
                    }
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun setupParticipants() {
        val gson = Gson()
        if (intent.getBooleanExtra("fromMain", false)) {
            // From MainActivity
            loadEthContact()
        }
        if (participants.isEmpty()) {
            participants = if (messages.isEmpty()) {
                val intentNumbers = getPhoneNumbersFromIntent()
                val ethAddress = intent.getStringExtra("eth_address") ?: "0x0"
                val participants = if (ethAddress != "0x0") {
                    getThreadParticipants(threadId, null, ethAddress, intent.getStringExtra(THREAD_TITLE) ?: "")
                } else {
                    getThreadParticipants(threadId, null)
                }
                fixParticipantNumbers(participants, intentNumbers)
            } else {
                messages.first().participants
            }
        }

        if (isEthereum && !intent.getBooleanExtra("fromMain", false)) {
            saveEthContact()
            saveThreadAsEth()
            setupSaves()
        }
    }

    private fun setupThreadTitle() {
        if (isEthereum) {
            print("The address in question: "+participants.get(0).ethAddress)
            thread_toolbar.title = participants.get(0).name + " - Ethereum"
            isEthereum = true
            //Test message - could be Welcoming Message in the future
            /*xmtpApi.sendMessage("Hey!", "0xefBABdeE59968641DC6E892e30C470c2b40157Cd").whenComplete { s, throwable ->
                Log.d("First message on TEST", s)
            }*/
            //setupEthereum()

        } else {
            val threadTitle = participants.getThreadTitle()
            if (threadTitle.isNotEmpty()) {
                thread_toolbar.title = participants.getThreadTitle()
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun setupSIMSelector() {
        val availableSIMs = SubscriptionManager.from(this).activeSubscriptionInfoList ?: return
        if (availableSIMs.size > 1) {
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label)
                availableSIMCards.add(SIMCard)
            }

            val numbers = ArrayList<String>()
            participants.forEach { contact ->
                contact.phoneNumbers.forEach {
                    numbers.add(it.normalizedNumber)
                }
            }

            if (numbers.isEmpty()) {
                return
            }

            currentSIMCardIndex = availableSIMs.indexOfFirstOrNull { it.subscriptionId == config.getUseSIMIdAtNumber(numbers.first()) } ?: 0

            thread_select_sim_icon.applyColorFilter(getProperTextColor())
            thread_select_sim_icon.beVisible()
            thread_select_sim_number.beVisible()

            if (availableSIMCards.isNotEmpty()) {
                thread_select_sim_icon.setOnClickListener {
                    currentSIMCardIndex = (currentSIMCardIndex + 1) % availableSIMCards.size
                    val currentSIMCard = availableSIMCards[currentSIMCardIndex]
                    thread_select_sim_number.text = currentSIMCard.id.toString()
                    toast(currentSIMCard.label)
                }
            }

            thread_select_sim_number.setTextColor(getProperTextColor().getContrastColor())
            thread_select_sim_number.text = (availableSIMCards[currentSIMCardIndex].id).toString()
        }
    }

    private fun blockNumber() {
        val numbers = ArrayList<String>()
        participants.forEach {
            it.phoneNumbers.forEach {
                numbers.add(it.normalizedNumber)
            }
        }

        val numbersString = TextUtils.join(", ", numbers)
        val question = String.format(resources.getString(R.string.block_confirmation), numbersString)

        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                numbers.forEach {
                    addBlockedNumber(it)
                }
                refreshMessages()
                finish()
            }
        }
    }

    private fun askConfirmDelete() {
        ConfirmationDialog(this, getString(R.string.delete_whole_conversation_confirmation)) {
            ensureBackgroundThread {
                deleteConversation(threadId)
                runOnUiThread {
                    refreshMessages()
                    finish()
                }
            }
        }
    }

    private fun dialNumber() {
        val phoneNumber = participants.first().phoneNumbers.first().normalizedNumber
        dialNumber(phoneNumber)
    }

    private fun managePeople() {
        if (thread_add_contacts.isVisible()) {
            hideKeyboard()
            thread_add_contacts.beGone()
        } else {
            showSelectedContacts()
            thread_add_contacts.beVisible()
            add_contact_or_number.requestFocus()
            showKeyboard(add_contact_or_number)
        }
    }

    private fun showSelectedContacts() {
        val properPrimaryColor = getProperPrimaryColor()

        val views = ArrayList<View>()
        participants.forEach { contact ->
            layoutInflater.inflate(R.layout.item_selected_contact, null).apply {
                val selectedContactBg = resources.getDrawable(R.drawable.item_selected_contact_background)
                (selectedContactBg as LayerDrawable).findDrawableByLayerId(R.id.selected_contact_bg).applyColorFilter(properPrimaryColor)
                selected_contact_holder.background = selectedContactBg

                selected_contact_name.text = contact.name
                selected_contact_name.setTextColor(properPrimaryColor.getContrastColor())
                selected_contact_remove.applyColorFilter(properPrimaryColor.getContrastColor())

                selected_contact_remove.setOnClickListener {
                    if (contact.rawId != participants.first().rawId) {
                        removeSelectedContact(contact.rawId)
                    }
                }
                views.add(this)
            }
        }
        showSelectedContact(views)
    }

    private fun addSelectedContact(contact: SimpleContact) {
        add_contact_or_number.setText("")
        if (participants.map { it.rawId }.contains(contact.rawId)) {
            return
        }

        participants.add(contact)
        showSelectedContacts()
        updateMessageType()
    }

    private fun markAsUnread() {
        ensureBackgroundThread {
            conversationsDB.markUnread(threadId)
            markThreadMessagesUnread(threadId)
            runOnUiThread {
                finish()
                bus?.post(Events.RefreshMessages())
            }
        }
    }

    private fun addNumberToContact() {
        val phoneNumber = participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: return
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, phoneNumber)
            launchActivityIntent(this)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getThreadItems(): ArrayList<ThreadItem> {
        val items = ArrayList<ThreadItem>()
        if (isFinishing) {
            return items
        }

        messages.sortBy { it.date }

        val subscriptionIdToSimId = HashMap<Int, String>()
        subscriptionIdToSimId[-1] = "?"
        SubscriptionManager.from(this).activeSubscriptionInfoList?.forEachIndexed { index, subscriptionInfo ->
            subscriptionIdToSimId[subscriptionInfo.subscriptionId] = "${index + 1}"
        }

        var prevDateTime = 0
        var prevSIMId = -2
        var hadUnreadItems = false
        val cnt = messages.size
        for (i in 0 until cnt) {
            val message = messages.getOrNull(i) ?: continue
            // do not show the date/time above every message, only if the difference between the 2 messages is at least MIN_DATE_TIME_DIFF_SECS,
            // or if the message is sent from a different SIM
            val isSentFromDifferentKnownSIM = prevSIMId != -1 && message.subscriptionId != -1 && prevSIMId != message.subscriptionId
            if (message.date - prevDateTime > MIN_DATE_TIME_DIFF_SECS || isSentFromDifferentKnownSIM) {
                val simCardID = subscriptionIdToSimId[message.subscriptionId] ?: "?"
                items.add(ThreadDateTime(message.date, simCardID))
                prevDateTime = message.date
            }
            items.add(message)

            if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                items.add(ThreadError(message.id, message.body))
            }

            if (message.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX) {
                items.add(ThreadSending(message.id))
            }

            if (!message.read) {
                hadUnreadItems = true
                markMessageRead(message.id, message.isMMS)
                conversationsDB.markRead(threadId)
            }

            if (i == cnt - 1 && (message.type == Telephony.Sms.MESSAGE_TYPE_SENT)) {
                items.add(ThreadSent(message.id, delivered = message.status == Telephony.Sms.STATUS_COMPLETE))
            }
            prevSIMId = message.subscriptionId
        }

        if (hadUnreadItems) {
            bus?.post(Events.RefreshMessages())
        }

        return items
    }

    private fun takeOrPickPhotoVideo() {
        val items = arrayListOf(
            RadioItem(TYPE_TAKE_PHOTO, getString(R.string.take_photo)),
            RadioItem(TYPE_CHOOSE_PHOTO, getString(R.string.choose_photo)),
            RadioItem(TYPE_SEND_ETH, "Send eth")
        )
        RadioGroupDialog(this, items = items) {
            val checkedId = it as Int
            if (checkedId == TYPE_TAKE_PHOTO) {
                launchTakePhotoIntent()
            } else if (checkedId == TYPE_CHOOSE_PHOTO) {
                launchPickPhotoVideoIntent()
            } else if (checkedId == TYPE_SEND_ETH) {
                SendEthDialog(
                    this
                ) {
                    val context: Context = this
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            var txHash = ""
                            if (getSystemService("wallet") != null) {
                                val web3j = Web3j.build(HttpService("https://cloudflare-eth.com"))
                                val signerImpl = SignerImpl(context = context)

                                val ethGetTransactionCount: EthGetTransactionCount = web3j.ethGetTransactionCount(
                                    signerImpl.address, DefaultBlockParameterName.LATEST
                                ).sendAsync().get()

                                val signedTx = signerImpl.sendTransaction(
                                    address = checkENSDomain(participants.get(0).ethAddress),
                                    value = BigDecimal(it).multiply(BigDecimal("1000000000000000000")).toString(),
                                    data = "",
                                    nonce = ethGetTransactionCount.transactionCount.toString(),
                                    gasPrice = web3j.ethGasPrice().sendAsync().get().gasPrice.toString(),
                                    gasAmount = "21000"
                                )

                                txHash = web3j.ethSendRawTransaction(signedTx).sendAsync().get().transactionHash
                            } else {

                                txHash = walletConnectKit.performTransaction(
                                    address = checkENSDomain(participants.get(0).ethAddress),
                                    value = it
                                ).result.toString()

                            }


                            val msg = createMsgImageFromText("$it eth", context, threadItems.size.toLong()+1, false)
                            val jsonObject = JSONObject()
                            jsonObject.put("txId", txHash)
                            jsonObject.put("value", it)


                            val xmtpmsg = "<tx_msg>$jsonObject</tx_msg>"

                            val target = checkENSDomain(participants.get(0).ethAddress) //"0xefBABdeE59968641DC6E892e30C470c2b40157Cd" //target addresss
                            runOnUiThread {
                                xmtpApi!!.sendMessage(xmtpmsg, target).whenComplete { s, throwable ->
                                    Log.d("Message", s)
                                }
                                threadItems.add(msg)
                                setupAdapter(xmtpMessages = threadItems)
                            }
                        } catch(t: Throwable) {
                            t.printStackTrace()
                            toast("Failed to send transaction.")
                        }

                    }
                }
            }
        }
    }

    private fun launchTakePhotoIntent() {
        val imageFile = createImageFile()
        capturedImageUri = getMyFileUri(imageFile)
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, capturedImageUri)
            }
            startActivityForResult(intent, TAKE_PHOTO_INTENT)
        } catch (e: ActivityNotFoundException) {
            showErrorToast(getString(R.string.no_app_found))
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun createMsgImageFromText(text: String, context: Context, messageId: Long, received: Boolean): Message {
        val bitmap: Bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val textPaint = Paint()
        textPaint.setTextAlign(Paint.Align.CENTER)
        textPaint.setColor(Color.WHITE)
        textPaint.textSize = 120.toFloat()

        val xPos = canvas.width / 2
        val yPos = (canvas.height / 2 - (textPaint.descent() + textPaint.ascent()) / 2)

        canvas.drawText(text, xPos.toFloat(), yPos, textPaint)
        val uri = getImageUri(context, bitmap)

        allImageUris.add(uri!!)

        val attachmentArray = ArrayList<Attachment>()
        attachmentArray.add(
            Attachment(
                id = null,
                messageId = messageId,
                uriString = uri.toString(),
                mimetype = "image/png",
                width = canvas.width,
                height = canvas.height,
                filename = "transaction"
            )
        )
        return Message(
            attachment = MessageAttachment(
                id = messageId,
                text = "",
                attachments = attachmentArray
            ),
            body = "",
            date = Instant.now().epochSecond.toInt(),
            id = messageId,
            isMMS = false,
            participants = participants,
            read = false,
            senderName = "Me",
            senderPhotoUri = "",
            status = -1,
            subscriptionId = -1,
            threadId = threadId,
            type = if (!received) {
                2
            } else {
                1
            }
        )
    }

    private fun launchPickPhotoVideoIntent() {
        hideKeyboard()
        val mimeTypes = arrayOf("image/*", "video/*")
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)

            try {
                startActivityForResult(this, PICK_ATTACHMENT_INTENT)
            } catch (e: ActivityNotFoundException) {
                showErrorToast(getString(R.string.no_app_found))
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun addAttachment(uri: Uri) {
        val originalUriString = uri.toString()
        if (attachmentSelections.containsKey(originalUriString)) {
            return
        }

        attachmentSelections[originalUriString] = AttachmentSelection(uri, false)
        val attachmentView = addAttachmentView(originalUriString, uri)
        val mimeType = contentResolver.getType(uri) ?: return

        if (mimeType.isImageMimeType() && config.mmsFileSizeLimit != FILE_SIZE_NONE) {
            val selection = attachmentSelections[originalUriString]
            attachmentSelections[originalUriString] = selection!!.copy(isPending = true)
            checkSendMessageAvailability()
            attachmentView.thread_attachment_progress.beVisible()
            imageCompressor.compressImage(uri, config.mmsFileSizeLimit) { compressedUri ->
                runOnUiThread {
                    if (compressedUri != null) {
                        attachmentSelections[originalUriString] = AttachmentSelection(compressedUri, false)
                        loadAttachmentPreview(attachmentView, compressedUri)
                    } else {
                        toast(R.string.compress_error)
                        removeAttachment(attachmentView, originalUriString)
                    }
                    checkSendMessageAvailability()
                    attachmentView.thread_attachment_progress.beGone()
                }
            }
        }
    }

    private fun addAttachmentView(originalUri: String, uri: Uri): View {
        thread_attachments_holder.beVisible()
        val attachmentView = layoutInflater.inflate(R.layout.item_attachment, null).apply {
            thread_attachments_wrapper.addView(this)
            thread_remove_attachment.setOnClickListener {
                removeAttachment(this, originalUri)
            }
        }

        loadAttachmentPreview(attachmentView, uri)
        return attachmentView
    }

    private fun loadAttachmentPreview(attachmentView: View, uri: Uri) {
        if (isDestroyed || isFinishing) {
            return
        }

        val roundedCornersRadius = resources.getDimension(R.dimen.medium_margin).toInt()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .transform(CenterCrop(), RoundedCorners(roundedCornersRadius))

        Glide.with(attachmentView.thread_attachment_preview)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .apply(options)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                    attachmentView.thread_attachment_preview.beGone()
                    attachmentView.thread_remove_attachment.beGone()
                    return false
                }

                override fun onResourceReady(dr: Drawable?, a: Any?, t: Target<Drawable>?, d: DataSource?, i: Boolean): Boolean {
                    attachmentView.thread_attachment_preview.beVisible()
                    attachmentView.thread_remove_attachment.beVisible()
                    checkSendMessageAvailability()
                    return false
                }
            })
            .into(attachmentView.thread_attachment_preview)
    }

    fun getImageUri(inContext: Context, inImage: Bitmap): Uri? {
        val bytes = ByteArrayOutputStream()
        inImage.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val path = MediaStore.Images.Media.insertImage(inContext.contentResolver, inImage, "Title", null)
        return Uri.parse(path)
    }

    private fun removeAttachment(attachmentView: View, originalUri: String) {
        thread_attachments_wrapper.removeView(attachmentView)
        attachmentSelections.remove(originalUri)
        if (attachmentSelections.isEmpty()) {
            thread_attachments_holder.beGone()
        }
        checkSendMessageAvailability()
    }

    fun getPathFromURI(uri: Uri?): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = contentResolver.query(uri!!, projection, null, null, null) ?: return null
        val column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        cursor.moveToFirst()
        val s = cursor.getString(column_index)
        cursor.close()
        return s
    }

    private fun saveAttachment(resultData: Intent) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        applicationContext.contentResolver.takePersistableUriPermission(resultData.data!!, takeFlags)
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            inputStream = contentResolver.openInputStream(Uri.parse(lastAttachmentUri))
            outputStream = contentResolver.openOutputStream(Uri.parse(resultData.dataString!!), "rwt")
            inputStream!!.copyTo(outputStream!!)
            outputStream.flush()
            toast(R.string.file_saved)
        } catch (e: Exception) {
            showErrorToast(e)
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
        lastAttachmentUri = null
    }

    private fun checkSendMessageAvailability() {
        if (thread_type_message.text!!.isNotEmpty() || (attachmentSelections.isNotEmpty() && !attachmentSelections.values.any { it.isPending })) {
            thread_send_message.isClickable = true
            thread_send_message.alpha = 0.9f
        } else {
            thread_send_message.isClickable = false
            thread_send_message.alpha = 0.4f
        }
        updateMessageType()
    }

    private fun sendMessage() {
        var msg = thread_type_message.value
        if (msg.isEmpty() && attachmentSelections.isEmpty()) {
            showErrorToast(getString(R.string.unknown_error_occurred))
            return
        }

        msg = removeDiacriticsIfNeeded(msg)

        val numbers = ArrayList<String>()
        participants.forEach { contact ->
            contact.phoneNumbers.forEach {
                numbers.add(it.normalizedNumber)
            }
        }

        val settings = getSendMessageSettings()
        val SIMId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
        if (SIMId != null) {
            settings.subscriptionId = SIMId
            numbers.forEach {
                config.saveUseSIMIdAtNumber(it, SIMId)
            }
        }

        val transaction = Transaction(this, settings)
        val message = com.klinker.android.send_message.Message(msg, numbers.toTypedArray())

        if (attachmentSelections.isNotEmpty()) {
            for (selection in attachmentSelections.values) {
                try {
                    val byteArray = contentResolver.openInputStream(selection.uri)?.readBytes() ?: continue
                    val mimeType = contentResolver.getType(selection.uri) ?: continue
                    message.addMedia(byteArray, mimeType)
                } catch (e: Exception) {
                    e.printStackTrace()
                    showErrorToast(e)
                } catch (e: Error) {
                    e.printStackTrace()
                    showErrorToast(e.localizedMessage ?: getString(R.string.unknown_error_occurred))
                }
            }
        }

        try {
            val smsSentIntent = Intent(this, SmsStatusSentReceiver::class.java)
            val deliveredIntent = Intent(this, SmsStatusDeliveredReceiver::class.java)

            if(isEthereum){
                //send Message via xmtp
                val xmtpmsg = message.text //text from variable message
                //participants. participants.get(0).ethAddress

                val target = checkENSDomain(participants.get(0).ethAddress) //"0xefBABdeE59968641DC6E892e30C470c2b40157Cd" //target addresss

                xmtpApi!!.sendMessage(xmtpmsg, target).whenComplete { s, throwable ->
                    Log.d("Message", s)
                }
                thread_type_message.setText("")
                val message = Message(
                    attachment = null,
                    body = xmtpmsg,
                    date = Instant.now().epochSecond.toInt(),
                    id = threadItems.size.toLong()+1,
                    isMMS = false,
                    participants = participants,
                    read = false,
                    senderName = "Me",
                    senderPhotoUri = "",
                    status = -1,
                    subscriptionId = -1,
                    threadId = threadId,
                    type = 2
                )
                threadItems.add(message)
                setupAdapter(xmtpMessages = threadItems)
                if(thread_messages_list.adapter != null) {
                    thread_messages_list.adapter!!.notifyDataSetChanged()
                }

                if (getSystemService("wallet") != null) {
                    runOnUiThread {
                        setupListener(targetEthAddress, false)
                    }
                } else if (walletConnectKit.address != null) {
                    runOnUiThread {
                        setupListener(targetEthAddress, false)
                    }
                }
            } else {
                transaction.setExplicitBroadcastForSentSms(smsSentIntent)
                transaction.setExplicitBroadcastForDeliveredSms(deliveredIntent)

                refreshedSinceSent = false
                transaction.sendNewMessage(message)
                thread_type_message.setText("")
                attachmentSelections.clear()
                thread_attachments_holder.beGone()
                thread_attachments_wrapper.removeAllViews()

                val message = Message(
                    attachment = null,
                    body = message.text,
                    date = Instant.now().epochSecond.toInt(),
                    id = threadItems.size.toLong()+1,
                    isMMS = false,
                    participants = participants,
                    read = false,
                    senderName = "Me",
                    senderPhotoUri = "",
                    status = -1,
                    subscriptionId = -1,
                    threadId = threadId,
                    type = 2
                )
                threadItems.add(message)
                setupAdapter(xmtpMessages = threadItems)
                if(thread_messages_list.adapter != null) {
                    thread_messages_list.adapter!!.notifyDataSetChanged()
                }

                if (!refreshedSinceSent) {
                    refreshMessages()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorToast(e)
        } catch (e: Error) {
            e.printStackTrace()
            showErrorToast(e.localizedMessage ?: getString(R.string.unknown_error_occurred))
        }

    }

    // show selected contacts, properly split to new lines when appropriate
    // based on https://stackoverflow.com/a/13505029/1967672
    private fun showSelectedContact(views: ArrayList<View>) {
        selected_contacts.removeAllViews()
        var newLinearLayout = LinearLayout(this)
        newLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        newLinearLayout.orientation = LinearLayout.HORIZONTAL

        val sideMargin = (selected_contacts.layoutParams as RelativeLayout.LayoutParams).leftMargin
        val mediumMargin = resources.getDimension(R.dimen.medium_margin).toInt()
        val parentWidth = realScreenSize.x - sideMargin * 2
        val firstRowWidth = parentWidth - resources.getDimension(R.dimen.normal_icon_size).toInt() + sideMargin / 2
        var widthSoFar = 0
        var isFirstRow = true

        for (i in views.indices) {
            val LL = LinearLayout(this)
            LL.orientation = LinearLayout.HORIZONTAL
            LL.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            LL.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            views[i].measure(0, 0)

            var params = LayoutParams(views[i].measuredWidth, LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, mediumMargin, 0)
            LL.addView(views[i], params)
            LL.measure(0, 0)
            widthSoFar += views[i].measuredWidth + mediumMargin

            val checkWidth = if (isFirstRow) firstRowWidth else parentWidth
            if (widthSoFar >= checkWidth) {
                isFirstRow = false
                selected_contacts.addView(newLinearLayout)
                newLinearLayout = LinearLayout(this)
                newLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                newLinearLayout.orientation = LinearLayout.HORIZONTAL
                params = LayoutParams(LL.measuredWidth, LL.measuredHeight)
                params.topMargin = mediumMargin
                newLinearLayout.addView(LL, params)
                widthSoFar = LL.measuredWidth
            } else {
                if (!isFirstRow) {
                    (LL.layoutParams as LayoutParams).topMargin = mediumMargin
                }
                newLinearLayout.addView(LL)
            }
        }
        selected_contacts.addView(newLinearLayout)
    }

    private fun removeSelectedContact(id: Int) {
        participants = participants.filter { it.rawId != id }.toMutableList() as ArrayList<SimpleContact>
        showSelectedContacts()
        updateMessageType()
    }

    private fun getPhoneNumbersFromIntent(): ArrayList<String> {
        val numberFromIntent = intent.getStringExtra(THREAD_NUMBER)
        val numbers = ArrayList<String>()

        if (numberFromIntent != null) {
            if (numberFromIntent.startsWith('[') && numberFromIntent.endsWith(']')) {
                val type = object : TypeToken<List<String>>() {}.type
                numbers.addAll(Gson().fromJson(numberFromIntent, type))
            } else {
                numbers.add(numberFromIntent)
            }
        }
        return numbers
    }

    private fun fixParticipantNumbers(participants: ArrayList<SimpleContact>, properNumbers: ArrayList<String>): ArrayList<SimpleContact> {
        for (number in properNumbers) {
            for (participant in participants) {
                participant.phoneNumbers = participant.phoneNumbers.map {
                    val numberWithoutPlus = number.replace("+", "")
                    if (numberWithoutPlus == it.normalizedNumber.trim()) {
                        if (participant.name == it.normalizedNumber) {
                            participant.name = number
                        }
                        PhoneNumber(number, 0, "", number)
                    } else {
                        PhoneNumber(it.normalizedNumber, 0, "", it.normalizedNumber)
                    }
                } as ArrayList<PhoneNumber>
            }
        }

        return participants
    }

    fun startContactDetailsIntent(contact: SimpleContact) {
        val simpleContacts = "com.simplemobiletools.contacts.pro"
        val simpleContactsDebug = "com.simplemobiletools.contacts.pro.debug"
        if (contact.rawId > 1000000 && contact.contactId > 1000000 && contact.rawId == contact.contactId &&
            (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
        ) {
            Intent().apply {
                action = Intent.ACTION_VIEW
                putExtra(CONTACT_ID, contact.rawId)
                putExtra(IS_PRIVATE, true)
                setPackage(if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug)
                setDataAndType(ContactsContract.Contacts.CONTENT_LOOKUP_URI, "vnd.android.cursor.dir/person")
                launchActivityIntent(this)
            }
        } else {
            ensureBackgroundThread {
                val lookupKey = SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
                val publicUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
                runOnUiThread {
                    launchViewContactIntent(publicUri)
                }
            }
        }
    }

    fun saveMMS(mimeType: String, path: String) {
        hideKeyboard()
        lastAttachmentUri = path
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = mimeType
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, path.split("/").last())

            try {
                startActivityForResult(this, PICK_SAVE_FILE_INTENT)
            } catch (e: ActivityNotFoundException) {
                showErrorToast(getString(R.string.system_service_disabled))
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun refreshMessages(event: Events.RefreshMessages) {
        refreshedSinceSent = true
        allMessagesFetched = false
        oldestMessageDate = -1

        if (isActivityVisible) {
            notificationManager.cancel(threadId.hashCode())
        }

        val lastMaxId = messages.maxByOrNull { it.id }?.id ?: 0L
        messages = getMessages(threadId, true)

        messages.filter { !it.isReceivedMessage() && it.id > lastMaxId }.forEach { latestMessage ->
            // subscriptionIds seem to be not filled out at sending with multiple SIM cards, so fill it manually
            if ((SubscriptionManager.from(this).activeSubscriptionInfoList?.size ?: 0) > 1) {
                val SIMId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
                if (SIMId != null) {
                    updateMessageSubscriptionId(latestMessage.id, SIMId)
                    latestMessage.subscriptionId = SIMId
                }
            }

            messagesDB.insertOrIgnore(latestMessage)
        }

        setupAdapter(ArrayList())
    }

    private fun updateMessageType() {
        val settings = getSendMessageSettings()
        val text = thread_type_message.text.toString()
        val isGroupMms = participants.size > 1 && config.sendGroupMessageMMS
        val isLongMmsMessage = getNumPages(settings, text) > settings.sendLongAsMmsAfter && config.sendLongMessageMMS
        var stringId = if (attachmentSelections.isNotEmpty() || isGroupMms || isLongMmsMessage) {
            R.string.mms
        } else {
            R.string.sms
        }
        if (isEthereum) {
            stringId = R.string.XMTP
        }
        thread_send_message.setText(stringId)
    }

    private fun createImageFile(): File {
        val outputDirectory = File(cacheDir, "captured").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        return File.createTempFile("IMG_", ".jpg", outputDirectory)
    }
}
