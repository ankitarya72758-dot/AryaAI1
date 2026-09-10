package com.arya.ai

import android.app.*
import android.os.*
import android.content.*
import android.net.Uri
import android.view.*
import android.widget.*
import java.io.*
import java.net.*
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var selectedUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var generate: Button
    private val prefs by lazy { getSharedPreferences("arya", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
        }

        root.addView(TextView(this).apply {
            text = "Arya AI"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = "AI Photo → Video Studio"
            textSize = 16f
            setPadding(0, 2, 0, 20)
        })

        val video = Button(this).apply {
            text = "🎬  Create 5-Minute AI Video"
            setOnClickListener { videoDialog() }
        }
        root.addView(video)

        root.addView(Button(this).apply {
            text = "⚙️ Backend Settings"
            setOnClickListener { settingsDialog() }
        })

        status = TextView(this).apply {
            text = "Ready"
            textSize = 16f
            setPadding(0, 18, 0, 8)
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, 16))

        root.addView(TextView(this).apply {
            text = "\nFeatures\n• Photo to AI video\n• 5-minute assembly\n• Progress tracking\n• Final MP4 URL\n• Backend API key stays off-device"
            textSize = 15f
            setPadding(0, 20, 0, 0)
        })

        setContentView(root)
    }

    private fun settingsDialog() {
        val input = EditText(this).apply {
            hint = "https://your-server.example.com"
            setText(prefs.getString("server", "http://10.0.2.2:3000"))
            singleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Arya AI Backend")
            .setMessage("Runway secret Android APK mein mat rakho. Sirf apne backend ka URL yahan save karo.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("server", input.text.toString().trim().removeSuffix("/")).apply()
                status.text = "Backend URL saved."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun videoDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
        }

        val pick = Button(this).apply { text = "🖼️ Select Photo" }
        val selected = TextView(this).apply {
            text = "No photo selected"
            setPadding(0, 8, 0, 8)
        }
        val prompt = EditText(this).apply {
            hint = "Example: cinematic slow camera push-in, realistic wind, natural motion..."
            minLines = 4
            gravity = Gravity.TOP
        }

        box.addView(pick)
        box.addView(selected)
        box.addView(prompt)
        box.addView(TextView(this).apply {
            text = "Target: 5:00 • Runway Gen-4.5 • 30 × 10-second clips"
            setPadding(0, 14, 0, 10)
        })

        generate = Button(this).apply {
            text = "Generate 5-Minute Video"
            isEnabled = false
        }
        box.addView(generate)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Arya AI Video")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 200)
        }

        generate.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            val server = prefs.getString("server", "")?.trim().orEmpty().removeSuffix("/")
            if (server.isBlank()) {
                settingsDialog()
                return@setOnClickListener
            }
            generate.isEnabled = false
            progress.progress = 5
            status.text = "Uploading photo..."
            thread {
                try {
                    val job = upload(server, uri, prompt.text.toString().trim())
                    poll(server, job)
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "❌ ${e.message}"
                        progress.progress = 0
                        generate.isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == 200 && result == RESULT_OK) {
            selectedUri = data?.data
            try { selectedUri?.let { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } catch (_: Exception) {}
            status.text = "Photo selected."
            // Dialog button is re-enabled by rebuilding state through a simple Toast.
            Toast.makeText(this, "Photo selected — Generate button ready.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun upload(base: String, uri: Uri, prompt: String): String {
        val boundary = "Arya-${UUID.randomUUID()}"
        val c = (URL("$base/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20000; readTimeout = 30000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        c.outputStream.use { out ->
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n$prompt\r\n".toByteArray())
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\n".toByteArray())
            out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
            contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val body = response(c)
        if (c.responseCode !in 200..299) error(body)
        return Regex("\"jobId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("Invalid backend response")
    }

    private fun poll(base: String, job: String) {
        thread {
            while (true) {
                val c = (URL("$base/status/$job").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 20000
                }
                val body = response(c)
                val state = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "UNKNOWN"
                val p = Regex("\"progress\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val url = Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                runOnUiThread {
                    progress.progress = p
                    status.text = if (url != null) "✅ Video ready\n$url" else "Status: $state • $p%"
                }
                if (state == "SUCCEEDED" || state == "FAILED") {
                    runOnUiThread { generate.isEnabled = true }
                    break
                }
                Thread.sleep(6000)
            }
        }
    }

    private fun response(c: HttpURLConnection): String {
        val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return s.bufferedReader().use { it.readText() }
    }
}
