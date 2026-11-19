package com.notdxbi.dxbi.tv

/**
 * (Lumen TV played torrents on the TV itself with libtorrent, ~30 MB of the app. Lumos plays them through TorBox, so
 * there's none here: this stands in for what the player still asks of it.)
 */
object TorrentEngine {
    fun status(): String? = null
    fun failed(): String? = null
    fun close() {}
}
