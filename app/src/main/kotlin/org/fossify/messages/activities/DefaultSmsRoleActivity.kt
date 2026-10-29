package org.fossify.messages.activities

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony

/**
 * Explicit, user-consented default-SMS role request for MMS download.
 * No automatic role change and no attempt to circumvent Android's SMS role.
 */
class DefaultSmsRoleActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val alreadyDefault = if(Build.VERSION.SDK_INT>=29) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)
                ?: (Telephony.Sms.getDefaultSmsPackage(this)==packageName)
        } else Telephony.Sms.getDefaultSmsPackage(this)==packageName
        if(alreadyDefault) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Download MMS with Messages")
            .setMessage("Android only allows the default SMS app to retrieve and save MMS. Set Messages as your default SMS app to download this message. You can change your default later in Android Settings.")
            .setNegativeButton("Not now"){_,_->finish()}
            .setOnCancelListener{finish()}
            .setPositiveButton("Choose default SMS app"){_,_->
                try {
                    val intent = if(Build.VERSION.SDK_INT>=29) {
                        val role=getSystemService(RoleManager::class.java)
                        if(!role.isRoleAvailable(RoleManager.ROLE_SMS)) error("SMS role unavailable")
                        role.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    } else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,packageName)
                    }
                    startActivityForResult(intent,7001)
                } catch(e:Exception) {
                    android.widget.Toast.makeText(this,
                        "Open Android Settings and choose Messages as your default SMS app.",
                        android.widget.Toast.LENGTH_LONG).show()
                    finish()
                }
            }.show()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        finish()
    }
}
