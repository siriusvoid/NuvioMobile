package com.nuvio.app.core.ui

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

private const val swipeBackExclusionRectsKey = "NuvioSwipeBackExclusionRects"
private const val swipeBackExclusionDidChangeNotification = "NuvioSwipeBackExclusionDidChange"

internal actual fun publishSwipeBackExclusionRects(encoded: String) {
    if (encoded.isEmpty()) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(swipeBackExclusionRectsKey)
    } else {
        NSUserDefaults.standardUserDefaults.setObject(encoded, forKey = swipeBackExclusionRectsKey)
    }
    NSNotificationCenter.defaultCenter.postNotificationName(
        swipeBackExclusionDidChangeNotification,
        null,
    )
}
