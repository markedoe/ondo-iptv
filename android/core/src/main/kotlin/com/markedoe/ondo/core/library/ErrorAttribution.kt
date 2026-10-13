package com.markedoe.ondo.core.library

import com.markedoe.ondo.core.epg.XmltvParseException
import com.markedoe.ondo.core.net.GzipException
import com.markedoe.ondo.core.net.HttpException
import com.markedoe.ondo.core.xtream.XtreamException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException

/** Where a loading error comes from — shown to the user so they know whom to contact. */
enum class ErrorAttribution {
    PROVIDER,
    NETWORK,
    APP;

    companion object {
        fun classify(error: Throwable): ErrorAttribution = when (error) {
            is HttpException, is XtreamException, is PlaylistLoaderException -> PROVIDER
            // Data we could not decode is reported as an app problem, as on Apple platforms.
            is XmltvParseException, is GzipException -> APP
            // Host not found / refused / bad server answer / bad address / missing file.
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is ProtocolException,
            is MalformedURLException, is FileNotFoundException, is NoSuchFileException -> PROVIDER
            is IOException -> NETWORK
            else -> APP
        }
    }
}
