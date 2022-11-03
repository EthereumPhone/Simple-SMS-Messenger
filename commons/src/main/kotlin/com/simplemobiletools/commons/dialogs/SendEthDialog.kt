package com.simplemobiletools.commons.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.simplemobiletools.commons.R
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import kotlinx.android.synthetic.main.dialog_message.view.*

/**
 * A simple dialog without any view, just a messageId, a positive button and optionally a negative button
 *
 * @param activity has to be activity context to avoid some Theme.AppCompat issues
 * @param message the dialogs message, can be any String. If empty, messageId is used
 * @param messageId the dialogs messageId ID. Used only if message is empty
 * @param positive positive buttons text ID
 * @param negative negative buttons text ID (optional)
 * @param callback an anonymous function
 */
class SendEthDialog(
    activity: Activity, message: String = "Send ETH", val cancelOnTouchOutside: Boolean = true, val callback: (value: String) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = activity.layoutInflater.inflate(R.layout.dialog_message, null)
        view.message.text = message

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton("Send") { dialogInterface: DialogInterface, i: Int ->
                dialogConfirmed(dialogInterface.inputText(R.id.dialog_text_input_layout).toString())
            }


        builder.apply {
            activity.setupDialogStuff(view, this, cancelOnTouchOutside = cancelOnTouchOutside) { alertDialog ->
                dialog = alertDialog
            }
        }
        builder.showInput(layout= R.layout.dialog_text_input,
            tilId = R.id.dialog_text_input_layout,
            hintRes= R.string.dialog_hint,
            counterMaxLength = 15,
            prefilled = "")
    }

    private fun dialogConfirmed(value: String) {
        dialog?.dismiss()
        callback(value)
    }
}

fun MaterialAlertDialogBuilder.showInput(
    layout: Int,
    tilId: Int,
    hintRes: Int,
    counterMaxLength: Int = 0,
    prefilled: String = ""
): Dialog {
    this.setView(layout)
    val dialog = this.show()
    val til = dialog.findViewById<TextInputLayout>(tilId)
    til?.let {
        til.hint = context.getString(hintRes)
        if (counterMaxLength > 0) {
            til.counterMaxLength = counterMaxLength
            til.isCounterEnabled = true
        }
        til.editText?.doOnTextChanged { text, start, before, count ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .isEnabled = !text.isNullOrBlank() && (counterMaxLength == 0 || text.length <= counterMaxLength)
        }
        til.editText?.append(prefilled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .isEnabled = !prefilled.isBlank()
    }
    return dialog
}

fun DialogInterface.inputText(tilId: Int): String {
    return (this as AlertDialog).findViewById<TextInputLayout>(tilId)?.editText?.text?.toString().orEmpty()
}
