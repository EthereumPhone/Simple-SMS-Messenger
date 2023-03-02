package com.simplemobiletools.smsmessenger2.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.smsmessenger2.R
import com.simplemobiletools.smsmessenger2.adapters.ContactsAdapter
import com.simplemobiletools.smsmessenger2.extensions.getSuggestedContacts
import com.simplemobiletools.smsmessenger2.extensions.getThreadId
import com.simplemobiletools.smsmessenger2.helpers.*
import kotlinx.android.synthetic.main.activity_new_conversation.*
import kotlinx.android.synthetic.main.item_suggested_contact.view.*
import org.web3j.crypto.Keys
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.net.URLDecoder
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import java.util.*

class NewConversationActivity : SimpleActivity() {
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private val REQUEST_CODE_CAMERA = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_conversation)
        title = getString(R.string.new_conversation)
        updateTextColors(new_conversation_holder)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        new_conversation_address.requestFocus()

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA)
        }

        imageButton.setOnClickListener {
            openQRScanner()
        }
    }

    private fun openQRScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan a QR code")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                // QR code scanned successfully
                new_conversation_address.setText(result.contents.replace("ethereum:", ""))
            } else {
                // QR code scanning cancelled
                Toast.makeText(this, "Cancelled", Toast.LENGTH_LONG).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(new_conversation_toolbar, NavigationIcon.Arrow)
        no_contacts_placeholder_2.setTextColor(getProperPrimaryColor())
        no_contacts_placeholder_2.underlineText()
    }

    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }

        fetchContacts()
        new_conversation_address.onTextChangeListener { searchString ->
            val filteredContacts = ArrayList<SimpleContact>()
            allContacts.forEach { contact ->
                Log.d("Simple-SMS-Messanger", contact.toString())
                if (contact.phoneNumbers.any { it.normalizedNumber.contains(searchString, true) } ||
                    contact.name.contains(searchString, true) ||
                    contact.name.contains(searchString.normalizeString(), true) ||
                    contact.name.normalizeString().contains(searchString, true) ||
                    contact.ethAddress.any()) {
                    filteredContacts.add(contact)
                }

            }



            filteredContacts.sortWith(compareBy { !it.name.startsWith(searchString, true) })
            setupAdapter(filteredContacts)

            new_conversation_confirm.beVisibleIf(searchString.length > 2)
        }

        new_conversation_confirm.applyColorFilter(getProperTextColor())
        new_conversation_confirm.setOnClickListener {
            val number = new_conversation_address.value
            if (number.endsWith(".eth")) {
                launchThreadActivity("+100000"+rand(0,1000000).toString(), number, checkENSDomain(number))
            } else if (number.startsWith("0x")) {
                launchThreadActivity("+100000"+rand(0,1000000).toString(), number, number)
            } else {
                launchThreadActivity(number, number, "0x0")
            }
        }

        no_contacts_placeholder_2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

        val properPrimaryColor = getProperPrimaryColor()
        contacts_letter_fastscroller.textColor = getProperTextColor().getColorStateList()
        contacts_letter_fastscroller.pressedTextColor = properPrimaryColor
        contacts_letter_fastscroller_thumb.setupWithFastScroller(contacts_letter_fastscroller)
        contacts_letter_fastscroller_thumb?.textColor = properPrimaryColor.getContrastColor()
        contacts_letter_fastscroller_thumb?.thumbColor = properPrimaryColor.getColorStateList()
    }

    private fun checkENSDomain(input: String): String {
        if (input.endsWith(".eth")) {
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)

            val web3j = Web3j.build(HttpService("https://cloudflare-eth.com/"))
            val ensResolver = ENSResolver(web3j)
            return try {
                val address = Keys.toChecksumAddress(ensResolver.resolve(input))
                address
            } catch (e: Exception) {
                e.printStackTrace()
                input
            }
        } else {
            return input
        }
    }

    private fun isThirdPartyIntent(): Boolean {
        if ((intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) && intent.dataString != null) {
            val number = intent.dataString!!.removePrefix("sms:").removePrefix("smsto:").removePrefix("mms").removePrefix("mmsto:").replace("+", "%2b").trim()
            launchThreadActivity(URLDecoder.decode(number), "", "0x0")
            finish()
            return true
        }
        return false
    }

    private fun fetchContacts() {
        fillSuggestedContacts {
            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }

                runOnUiThread {
                    setupAdapter(allContacts)
                }
            }
        }
    }

    val random = Random()
    fun rand(from: Int, to: Int) : Int {
        return random.nextInt(to - from) + from
    }


    private fun setupAdapter(contacts: ArrayList<SimpleContact>) {
        val hasContacts = contacts.isNotEmpty()
        contacts_list.beVisibleIf(hasContacts)
        no_contacts_placeholder.beVisibleIf(!hasContacts)
        no_contacts_placeholder_2.beVisibleIf(!hasContacts && !hasPermission(PERMISSION_READ_CONTACTS))

        if (!hasContacts) {
            val placeholderText = if (hasPermission(PERMISSION_READ_CONTACTS)) R.string.no_contacts_found else R.string.no_access_to_contacts
            no_contacts_placeholder.text = getString(placeholderText)
        }

        val currAdapter = contacts_list.adapter
        if (currAdapter == null) {
            ContactsAdapter(this, contacts, contacts_list) {
                hideKeyboard()
                val contact = it as SimpleContact
                val phoneNumbers = contact.phoneNumbers
                if (phoneNumbers.size > 1) {
                    val primaryNumber = contact.phoneNumbers.find { it.isPrimary }
                    if (primaryNumber != null) {
                        launchThreadActivity(primaryNumber.value, contact.name, contact.ethAddress)
                    } else {
                        val items = ArrayList<RadioItem>()
                        phoneNumbers.forEachIndexed { index, phoneNumber ->
                            val type = getPhoneNumberTypeText(phoneNumber.type, phoneNumber.label)
                            items.add(RadioItem(index, "${phoneNumber.normalizedNumber} ($type)", phoneNumber.normalizedNumber))
                        }

                        RadioGroupDialog(this, items) {
                            launchThreadActivity(it as String, contact.name, contact.ethAddress)
                        }
                    }
                } else {
                    launchThreadActivity(phoneNumbers.first().normalizedNumber, contact.name, contact.ethAddress)
                }
            }.apply {
                contacts_list.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                contacts_list.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contacts)
        }

        setupLetterFastscroller(contacts)
    }

    private fun fillSuggestedContacts(callback: () -> Unit) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val suggestions = getSuggestedContacts(privateContacts)
            runOnUiThread {
                suggestions_holder.removeAllViews()
                if (suggestions.isEmpty()) {
                    suggestions_label.beGone()
                    suggestions_scrollview.beGone()
                } else {
                    suggestions_label.beVisible()
                    suggestions_scrollview.beVisible()
                    suggestions.forEach {
                        val contact = it
                        layoutInflater.inflate(R.layout.item_suggested_contact, null).apply {
                            suggested_contact_name.text = contact.name
                            suggested_contact_name.setTextColor(getProperTextColor())

                            if (!isDestroyed) {
                                SimpleContactsHelper(this@NewConversationActivity).loadContactImage(contact.photoUri, suggested_contact_image, contact.name)
                                suggestions_holder.addView(this)
                                setOnClickListener {
                                    launchThreadActivity(contact.phoneNumbers.first().normalizedNumber, contact.name, contact.ethAddress)
                                }
                            }
                        }
                    }
                }
                callback()
            }
        }
    }

    private fun setupLetterFastscroller(contacts: ArrayList<SimpleContact>) {
        contacts_letter_fastscroller.setupWithRecyclerView(contacts_list, { position ->
            try {
                val name = contacts[position].name
                val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
                FastScrollItemIndicator.Text(character.toUpperCase(Locale.getDefault()).normalizeString())
            } catch (e: Exception) {
                FastScrollItemIndicator.Text("")
            }
        })
    }

    private fun launchThreadActivity(phoneNumber: String, name: String, ethAddress: String) {
        hideKeyboard()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        val threadId = getThreadId(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, text)
            putExtra(THREAD_NUMBER, number)
            putExtra("eth_address", ethAddress)
            if (ethAddress != "0x0") {
                putExtra("isEthereum", true)
            }

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
}
