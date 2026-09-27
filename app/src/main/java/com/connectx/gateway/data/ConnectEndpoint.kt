package com.connectx.gateway.data

/**
 * Connect App endpoint compiled into the app backend.
 * The phone UI never shows or edits this address. Products such as EMS
 * never talk to this phone — only ConnectX does, through this endpoint.
 */
internal object ConnectEndpoint {
    const val ORIGIN = "https://connectxweb.pages.dev"
    fun url(): String = "$ORIGIN/connect"
}
