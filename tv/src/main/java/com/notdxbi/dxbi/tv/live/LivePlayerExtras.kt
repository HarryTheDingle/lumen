package com.notdxbi.dxbi.tv.live

import kotlinx.coroutines.flow.MutableStateFlow
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme

/**
 * What the live player's controls show besides the playing channel: the strip of channels along the bottom (each with what's
 * on now), a nudge to put the focus on it (Down on the remote), and the channel number being typed on the remote.
 */
object LivePlayerExtras {
    /** The channels around the one playing, with what's on now; empty while the controls are hidden */
    val stripChannels = MutableStateFlow<List<Pair<CanonicalChannel, EpgProgramme?>>>(emptyList())
    /** Changes when the controls open on the strip */
    val focusStripTick = MutableStateFlow(0L)
    /** The digits typed so far */
    val numberEntry = MutableStateFlow("")
    /** A short message about the number just typed ("No channel 77") */
    val numberMessage = MutableStateFlow("")
}
