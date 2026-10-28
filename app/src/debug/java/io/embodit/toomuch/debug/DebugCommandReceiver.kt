package io.embodit.toomuch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.toomuch.platform.TmLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.toomuch.DEBUG`);
 * see [DebugCommands] and AGENTS.md for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.toomuch.DEBUG -p io.embodit.toomuch --es cmd goto --es s S06"
 *   adb -s <serial> shell "am broadcast -a io.embodit.toomuch.DEBUG -p io.embodit.toomuch --es cmd pin --es id fridge --ef x 1.8 --ef y 1.5 --ef z -1.9"
 *
 * Every extra is passed on by name: strings (`--es`), floats (`--ef`), ints (`--ei`), booleans (`--ez`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val extras = HashMap<String, Any>()
        intent.extras?.let { b ->
            for (key in b.keySet()) {
                if (key == "cmd") continue
                @Suppress("DEPRECATION")
                b.get(key)?.let { extras[key] = it }
            }
        }
        TmLog.i("CMD recv '$cmd' extras=$extras")
        DebugCommands.run(cmd, extras)
    }
}
