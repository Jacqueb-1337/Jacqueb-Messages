package org.fossify.messages

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.FossifyApp
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.activities.SimpleActivity
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.helpers.MessagingCache

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        // Commons persists the application ID independently. Older installs
        // may retain the upstream/fork ID, causing its icon-color handler to
        // enable launcher aliases in the wrong package and throw
        // IllegalArgumentException. Keep it aligned with our real package.
        if (baseConfig.appId != packageName) baseConfig.appId = packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Upstream Commons incorrectly warns independent forks that they
            // are "fake" on randomized activity launches when the launch
            // counter is divisible by 100. The app's own SimpleActivity
            // shields its superclass, but external Commons screens don't
            // inherit it. Shield those screens here, preserving the count.
            val shieldedActivities = java.util.IdentityHashMap<Activity, Int>()
            registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity !is BaseSimpleActivity || activity is SimpleActivity) return
                    val count = baseConfig.appRunCount
                    if (!packageName.startsWith("org.fossify.", ignoreCase = true) && count % 100 == 0) {
                        shieldedActivities[activity] = count
                        baseConfig.appRunCount = count + 1
                    }
                }
                override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                    val count = shieldedActivities.remove(activity) ?: return
                    if (baseConfig.appRunCount == count + 1) baseConfig.appRunCount = count
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) { shieldedActivities.remove(activity) }
            })
        }
        if (hasPermission(PERMISSION_READ_CONTACTS)) {
            listOf(
                ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.Data.CONTENT_URI,
                ContactsContract.DisplayPhoto.CONTENT_URI
            ).forEach {
                try {
                    contentResolver.registerContentObserver(it, true, contactsObserver)
                } catch (_: Exception) {
                }
            }
        }

        ensureBackgroundThread {
            rescheduleAllScheduledMessages()
        }
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
