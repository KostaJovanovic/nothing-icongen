package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Icon

/** Keep the state visible in the shade even when SystemUI uses an app avatar for the small icon. */
internal object NotificationArtwork {
    fun state(context: Context, drawable: Int): Icon {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = context.getColor(if (night) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
        return Icon.createWithResource(context, drawable).setTint(ink)
    }
}
