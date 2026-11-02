package com.example.devicemanager

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoHTTPD

class RemoteControlService : AccessibilityService() {

    private var server: WebServer? = null

    companion object {
        var instance: RemoteControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            server = WebServer(8080)
            server?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters

            if (uri == "/launch") {
                val pkg = params["pkg"]?.firstOrNull()
                if (!pkg.isNullOrEmpty()) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        return newFixedLengthResponse("Launched $pkg")
                    }
                    return newFixedLengthResponse("Failed: App launch intent not found")
                }
            }

            if (uri == "/key") {
                val action = params["action"]?.firstOrNull()
                when (action) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                return newFixedLengthResponse("Action $action performed")
            }

            // Default route: Return Web UI listing all installed apps
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(pm).toString() }

            val html = StringBuilder()
            html.append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
            html.append("<style>body{font-family:sans-serif;padding:16px;background:#121212;color:#fff;}")
            html.append(".btn{display:inline-block;padding:12px;margin:4px;background:#2563eb;color:#fff;border-radius:8px;text-decoration:none;font-weight:bold;}")
            html.append(".nav{margin-bottom:20px;padding:10px;background:#1e1e1e;border-radius:8px;}")
            html.append("ul{list-style:none;padding:0;}li{margin:8px 0;background:#1e1e1e;padding:10px;border-radius:8px;display:flex;justify-content:space-between;align-items:center;}")
            html.append("</style></head><body>")
            html.append("<h2>Remote App Controller</h2>")
            html.append("<div class='nav'>")
            html.append("<a class='btn' href='/key?action=back'>Back</a> ")
            html.append("<a class='btn' href='/key?action=home'>Home</a> ")
            html.append("<a class='btn' href='/key?action=recents'>Recents</a> ")
            html.append("<a class='btn' href='/key?action=lock' style='background:#dc2626;'>Lock</a>")
            html.append("</div><ul>")

            for (app in packages) {
                val name = app.loadLabel(pm).toString()
                val pkgName = app.packageName
                html.append("<li><span><b>").append(name).append("</b><br><small>").append(pkgName).append("</small></span>")
                html.append("<a class='btn' href='/launch?pkg=").append(pkgName).append("'>Open</a></li>")
            }

            html.append("</ul></body></html>")
            return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString())
        }
    }
}
