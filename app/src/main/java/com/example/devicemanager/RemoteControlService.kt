package com.example.devicemanager

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

data class NotificationModel(
    val title: String,
    val app: String,
    val time: String,
    val text: String
)

class RemoteControlService : AccessibilityService() {

    private var server: WebServer? = null

    companion object {
        var instance: RemoteControlService? = null
        val capturedNotifications: MutableList<NotificationModel> =
            Collections.synchronizedList(mutableListOf())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            if (server == null) {
                server = WebServer(8080)
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun escapeHtml(input: String?): String {
        return TextUtils.htmlEncode(input ?: "")
    }

    private fun getSmsHtml(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC LIMIT 40"
            )
            cursor?.use {
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")

                while (it.moveToNext()) {
                    val rawAddress = if (addrIdx != -1) it.getString(addrIdx) else "Unknown"
                    val rawBody = if (bodyIdx != -1) it.getString(bodyIdx) else ""
                    val rawDate = if (dateIdx != -1) it.getLong(dateIdx) else 0L

                    val address = escapeHtml(rawAddress)
                    val body = escapeHtml(rawBody)
                    val date = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(rawDate))

                    sb.append("<div class='card'><b>From: ").append(address).append("</b>")
                        .append("<small style='float:right'>").append(date).append("</small>")
                        .append("<p>").append(body).append("</p></div>")
                }
            }
        } catch (e: Exception) {
            return "<div class='card'><p>Error reading SMS: ${escapeHtml(e.message)}</p></div>"
        }
        return if (sb.isEmpty()) "<div class='card'><p>No SMS found in inbox.</p></div>" else sb.toString()
    }

    private fun getCallLogsHtml(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 40"
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val rawNumber = if (numIdx != -1) it.getString(numIdx) ?: "Unknown" else "Unknown"
                    val rawName = if (nameIdx != -1) it.getString(nameIdx) ?: rawNumber else rawNumber
                    val rawType = if (typeIdx != -1) it.getInt(typeIdx) else -1
                    val rawDate = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                    val rawDuration = if (durIdx != -1) it.getString(durIdx) ?: "0" else "0"

                    val typeStr = when (rawType) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }

                    val date = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(rawDate))
                    val name = escapeHtml(rawName)
                    val number = escapeHtml(rawNumber)

                    sb.append("<div class='card'><b>").append(name).append(" (").append(typeStr).append(")</b>")
                        .append("<small style='float:right'>").append(date).append("</small><p>")
                        .append(number).append(" | Duration: ").append(rawDuration).append("s</p></div>")
                }
            }
        } catch (e: Exception) {
            return "<div class='card'><p>Error reading call logs: ${escapeHtml(e.message)}</p></div>"
        }
        return if (sb.isEmpty()) "<div class='card'><p>No call logs found.</p></div>" else sb.toString()
    }

    private fun getNotificationsHtml(): String {
        val sb = StringBuilder()
        synchronized(capturedNotifications) {
            for (item in capturedNotifications.reversed()) {
                sb.append("<div class='card'><b>").append(escapeHtml(item.title))
                    .append("</b> <small>(").append(escapeHtml(item.app)).append(" - ").append(escapeHtml(item.time))
                    .append(")</small><p>").append(escapeHtml(item.text)).append("</p></div>")
            }
        }
        return if (sb.isEmpty()) "<div class='card'><p>No notifications captured yet.</p></div>" else sb.toString()
    }

    inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters

            // 1. Navigation Action Handlers
            if (uri == "/key") {
                val action = params["action"]?.firstOrNull()
                when (action) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "lock" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                        }
                    }
                }
                return newFixedLengthResponse("OK")
            }

            // 2. Launch Application Handler
            if (uri == "/launch") {
                val pkg = params["pkg"]?.firstOrNull()
                if (!pkg.isNullOrEmpty()) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    return if (intent != null) {
                        startActivity(intent)
                        newFixedLengthResponse("Launched $pkg")
                    } else {
                        newFixedLengthResponse("Failed: Launch intent null")
                    }
                }
            }

            // 3. Dynamic AJAX Data Endpoints
            if (uri == "/api/sms") return newFixedLengthResponse(Response.Status.OK, "text/html", getSmsHtml())
            if (uri == "/api/calls") return newFixedLengthResponse(Response.Status.OK, "text/html", getCallLogsHtml())
            if (uri == "/api/notifs") return newFixedLengthResponse(Response.Status.OK, "text/html", getNotificationsHtml())

            // 4. Installed Apps List
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(pm).toString() }

            val appsHtml = StringBuilder()
            for (app in packages) {
                val name = escapeHtml(app.loadLabel(pm).toString())
                val pkg = escapeHtml(app.packageName)
                appsHtml.append("<li><span><b>").append(name).append("</b><br><small>").append(pkg)
                    .append("</small></span><button onclick=\"launchApp('").append(pkg)
                    .append("')\">Open</button></li>")
            }

            // 5. Dashboard HTML Page
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Device Hub</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #121212; color: #fff; margin: 0; padding: 16px; }
                        .nav-bar { display: flex; gap: 8px; margin-bottom: 16px; background: #1e1e1e; padding: 10px; border-radius: 8px; }
                        button { background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 10px 14px; font-weight: bold; cursor: pointer; }
                        .tab-btn { background: #2d2d2d; flex: 1; text-align: center; }
                        .active { background: #2563eb !important; }
                        .card { background: #1e1e1e; padding: 12px; margin: 8px 0; border-radius: 8px; border-left: 4px solid #2563eb; }
                        ul { list-style: none; padding: 0; margin: 0; }
                        li { background: #1e1e1e; padding: 12px; margin: 8px 0; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; }
                        .tab-pane { display: none; }
                        .show { display: block; }
                    </style>
                </head>
                <body>
                    <div class="nav-bar">
                        <button onclick="fetch('/key?action=back')">Back</button>
                        <button onclick="fetch('/key?action=home')">Home</button>
                        <button onclick="fetch('/key?action=recents')">Recents</button>
                        <button onclick="fetch('/key?action=lock')" style="background:#dc2626;">Lock</button>
                    </div>

                    <div style="display:flex; gap:8px; margin-bottom:16px;">
                        <button class="tab-btn active" onclick="switchTab('apps', this)">Apps</button>
                        <button class="tab-btn" onclick="loadTab('sms', this)">Messages</button>
                        <button class="tab-btn" onclick="loadTab('calls', this)">Calls</button>
                        <button class="tab-btn" onclick="loadTab('notifs', this)">Alerts</button>
                    </div>

                    <div id="apps" class="tab-pane show">
                        <ul>$appsHtml</ul>
                    </div>

                    <div id="sms" class="tab-pane">Loading messages...</div>
                    <div id="calls" class="tab-pane">Loading call logs...</div>
                    <div id="notifs" class="tab-pane">Loading notifications...</div>

                    <script>
                        function switchTab(id, elem) {
                            document.querySelectorAll('.tab-pane').forEach(el => el.classList.remove('show'));
                            document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
                            document.getElementById(id).classList.add('show');
                            elem.classList.add('active');
                        }

                        function loadTab(id, elem) {
                            switchTab(id, elem);
                            fetch('/api/' + id)
                                .then(r => r.text())
                                .then(html => {
                                    document.getElementById(id).innerHTML = html;
                                })
                                .catch(err => {
                                    document.getElementById(id).innerHTML = '<p>Failed to load data: ' + err + '</p>';
                                });
                        }

                        function launchApp(pkg) {
                            fetch('/launch?pkg=' + pkg)
                                .then(r => r.text())
                                .then(msg => alert(msg));
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
}
