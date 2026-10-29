package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.messages.R

open class SimpleActivity : BaseSimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The upstream Fossify Commons library mistakenly calls an independent
        // fork "fake" on its 0th / 100th / 200th launch, with a random chance.
        // It does not check signatures. Avoid that unsupported branding warning
        // during the library's onCreate only, without losing the real counter,
        // resetting onboarding or altering Android's own security checks.
        val launchCount = baseConfig.appRunCount
        val needsShield = !packageName.startsWith("org.fossify.", ignoreCase = true) &&
            launchCount % 100 == 0
        if (needsShield) baseConfig.appRunCount = launchCount + 1
        try {
            super.onCreate(savedInstanceState)
        } finally {
            if (needsShield && baseConfig.appRunCount == launchCount + 1)
                baseConfig.appRunCount = launchCount
        }
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Messages"
}
