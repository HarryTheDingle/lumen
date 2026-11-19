package com.notdxbi.dxbi.tv

/** Lumen's loading screen carried on over the player, until the video's first picture (dx.bi TV has none) */
interface LoadingCover {
    /** Still up (the player keeps the remote's keys from the controls under it) */
    val showing: Boolean
    /** More to say on the screen */
    fun detail(text: String)
    /** The first picture is on: the screen fades away */
    fun ready()
    /** The stream failed before then: the screen says so, and Back goes back */
    fun failed(message: String)
    /** The stream failed before then, and the player is closing for the next best: the screen stays up for it */
    fun retrying(message: String) {}
    /** On to the next episode ([episode]: "S2, E7 · Its Name", [videoId]: its id): the loading screen comes up for it, and the app starts it as Play does */
    fun next(episode: String, videoId: String) {}
    /** Another stream of the same video is starting (one that can't be swapped in place): the screen comes up for it */
    fun switching() {}
}
