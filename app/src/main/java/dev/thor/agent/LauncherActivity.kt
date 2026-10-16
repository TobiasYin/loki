package dev.thor.agent

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import dev.thor.tools.diagnostic.DisplayRoles
import dev.thor.tools.diagnostic.displayFacts

/** The launcher selects a display before the WebView and storage owner exist. */
class LauncherActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val lower=DisplayRoles(this).resolve(displayFacts(this))?.second
        val options=ActivityOptions.makeBasic()
        if(lower!=null)options.setLaunchDisplayId(lower.id)
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),options.toBundle())
        finish()
    }
}
