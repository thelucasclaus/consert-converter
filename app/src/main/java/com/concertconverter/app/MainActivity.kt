package com.concertconverter.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    private val inputs = mutableListOf<Uri>()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var gain: EditText
    private lateinit var outputMode: Spinner
    private lateinit var analyzeButton: Button
    private lateinit var exportButton: Button
    private var measuredPeak = Double.NaN
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 20) }
        val title = TextView(this).apply { text = "Concert Converter"; textSize = 26f }
        root.addView(title)
        root.addView(TextView(this).apply { text = "Add WAV recordings in show order. Analyze peaks, apply gain, and join them into one 24-bit WAV. Original files remain unchanged." })
        val add = Button(this).apply { text = "Add WAV files"; setOnClickListener { if (!busy) selectFiles() } }
        root.addView(add)
        val clear = Button(this).apply { text = "Clear list"; setOnClickListener { if (!busy) { inputs.clear(); measuredPeak = Double.NaN; updateInfo() } } }
        root.addView(clear)
        info = TextView(this).apply { text = "No files selected"; textSize = 15f }
        root.addView(info)
        analyzeButton = Button(this).apply { text = "Analyze peak levels"; isEnabled = false; setOnClickListener { analyze() } }
        root.addView(analyzeButton)
        root.addView(TextView(this).apply { text = "Gain adjustment (dB). Negative reduces volume:" })
        gain = EditText(this).apply { setSingleLine(true); inputType =  android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; setText("0") }
        root.addView(gain)
        root.addView(TextView(this).apply { text = "Export format" })
        outputMode = Spinner(this)
        outputMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("24-bit PCM WAV (recommended)", "16-bit PCM WAV"))
        root.addView(outputMode)
        exportButton = Button(this).apply { text = "Join + convert"; isEnabled = false; setOnClickListener { chooseOutput() } }
        root.addView(exportButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress)
        root.addView(TextView(this).apply { text = "Note: This first build supports standard PCM/IEEE-float WAV input, not FLAC or MP3. Source clips must share channel count and sample rate. It produces a single joined WAV, not overlapping/mixing tracks. Keep the app open while processing." })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun selectFiles() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, 10) }
    private fun chooseOutput() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "audio/wav"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "Joined_Concert.wav") }, 11) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 10) {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) inputs.add(clip.getItemAt(i).uri) }
            if (data.clipData == null) data.data?.let { inputs.add(it) }
            measuredPeak = Double.NaN; updateInfo()
        } else if (requestCode == 11) data.data?.let { export(it) }
    }
    private fun updateInfo(extra: String = "") {
        info.text = if (inputs.isEmpty()) "No files selected" else inputs.mapIndexed { i, u -> "${i+1}. ${u.lastPathSegment?.substringAfterLast('/') ?: "Audio"}" }.joinToString("\n") + if (extra.isEmpty()) "" else "\n\n$extra"
        analyzeButton.isEnabled = inputs.isNotEmpty() && !busy
        exportButton.isEnabled = inputs.isNotEmpty() && !busy
    }
    private fun setBusy(value: Boolean) { busy = value; progress.visibility = if (value) View.VISIBLE else View.GONE; analyzeButton.isEnabled = !value && inputs.isNotEmpty(); exportButton.isEnabled = !value && inputs.isNotEmpty() }
    private fun analyze() {
        setBusy(true); progress.progress = 0
        executor.execute { try {
            var peak = 0.0; var processed = 0L
            val specs = inputs.map { WavReader(contentResolver.openInputStream(it) ?: error("Cannot open input")).use { reader -> reader.spec } }
            val total = specs.sumOf { it.frames * it.channels }
            for (uri in inputs) WavReader(contentResolver.openInputStream(uri) ?: error("Cannot open input")).use { reader ->
                while (true) { val samples = reader.readSamples(8192); if (samples.isEmpty()) break
                    for (v in samples) peak = max(peak, abs(v))
                    processed += samples.size
                    if (processed % 262144L < 8192) runOnUiThread { progress.progress = (processed * 100 / max(1L,total)).toInt().coerceIn(0,100) }
                }
            }
            measuredPeak = if (peak > 0) 20.0 * log10(peak) else Double.NEGATIVE_INFINITY
            val recommended = if (peak > 0) min(0.0, -3.0 - measuredPeak) else 0.0
            runOnUiThread { gain.setText("%.2f".format(java.util.Locale.US,recommended)); updateInfo("Highest sample peak: %.2f dBFS\nSuggested gain: %.2f dB (target ≤ −3 dBFS)".format(java.util.Locale.US,measuredPeak,recommended)); setBusy(false) }
        } catch (e: Exception) { fail(e) } }
    }
    private fun export(dest: Uri) {
        val db = gain.text.toString().toDoubleOrNull() ?: run { Toast.makeText(this,"Enter a valid gain",Toast.LENGTH_LONG).show(); return }
        val bits = if (outputMode.selectedItemPosition == 0) 24 else 16
        setBusy(true); progress.progress = 0
        executor.execute { try {
            val specs = inputs.map { WavReader(contentResolver.openInputStream(it) ?: error("Cannot open input")).use { r -> r.spec } }
            val first = specs.first()
            require(specs.all { it.channels == first.channels && it.sampleRate == first.sampleRate }) { "All clips must have the same sample rate and channel count." }
            val totalFrames = specs.sumOf { it.frames }
            val dataBytes = totalFrames * first.channels * (bits / 8)
            require(dataBytes <= 0xffffffffL - 36L) { "Output WAV exceeds 4 GB RIFF limit. Split the show or use RF64-capable software." }
            var done = 0L
            contentResolver.openOutputStream(dest, "w")!!.buffered().use { out ->
                writeHeader(out,first.channels,first.sampleRate,bits,dataBytes)
                val scale = 10.0.pow(db / 20.0)
                for (uri in inputs) WavReader(contentResolver.openInputStream(uri) ?: error("Cannot open input")).use { r ->
                    while (true) { val samples = r.readSamples(8192); if (samples.isEmpty()) break
                        val bytes = ByteArray(samples.size * bits / 8); var p = 0
                        for (s in samples) {
                            val v = (s * scale).coerceIn(-1.0, 1.0 - 1.0 / (1L shl (bits-1)))
                            val q = (v * (1L shl (bits-1))).roundToLong().toInt()
                            bytes[p++] = q.toByte(); bytes[p++] = (q shr 8).toByte()
                            if (bits == 24) bytes[p++] = (q shr 16).toByte()
                        }
                        out.write(bytes); done += samples.size / first.channels
                        if (done % 131072L < 8192) runOnUiThread { progress.progress = (done*100/max(1L,totalFrames)).toInt().coerceIn(0,100) }
                    }
                }
            }
            runOnUiThread { progress.progress = 100; setBusy(false); Toast.makeText(this,"Concert WAV exported",Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { fail(e) } }
    }
    private fun fail(e: Exception) { runOnUiThread { setBusy(false); Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() } }
    private fun writeHeader(out: OutputStream, channels: Int, rate: Int, bits: Int, bytes: Long) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(s:String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        ascii("RIFF"); b.putInt((36L+bytes).toInt()); ascii("WAVEfmt "); b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate*channels*bits/8); b.putShort((channels*bits/8).toShort()); b.putShort(bits.toShort()); ascii("data"); b.putInt(bytes.toInt()); out.write(b.array())
    }
}

private data class WavSpec(val channels:Int, val sampleRate:Int, val format:Int, val bits:Int, val frames:Long)
private class WavReader(private val stream: InputStream): Closeable {
    val spec: WavSpec
    private var remaining: Long
    init {
        fun readFully(b:ByteArray) { var p=0; while(p<b.size) { val n=stream.read(b,p,b.size-p); if(n<0) throw EOFException("Truncated WAV"); p+=n } }
        fun u32(b:ByteArray, at:Int)= (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xffffffffL)
        fun skip(n:Long) { var left=n; val scratch=ByteArray(8192); while(left>0) { val count=stream.read(scratch,0,min(left,8192).toInt()); if(count<0) throw EOFException("Truncated chunk"); left-=count } }
        val head=ByteArray(12); readFully(head)
        require(String(head,0,4)=="RIFF" && String(head,8,4)=="WAVE") { "Only standard RIFF WAV is supported" }
        var channels=0; var rate=0; var format=0; var bits=0; var align=0; var size=-1L
        while (true) {
            val h=ByteArray(8); readFully(h); val id=String(h,0,4); val len=u32(h,4)
            if(id=="fmt ") {
                require(len in 16..1024) { "Unsupported fmt chunk" }
                val f=ByteArray(len.toInt()); readFully(f); val bb=ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                format=bb.short.toInt() and 0xffff; channels=bb.short.toInt() and 0xffff; rate=bb.int; bb.int; align=bb.short.toInt() and 0xffff; bits=bb.short.toInt() and 0xffff
                if(format==65534 && f.size>=40) format=ByteBuffer.wrap(f,24,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
            } else if(id=="data") { size=len; break } else skip(len)
            if(len%2L==1L) skip(1)
        }
        require(channels in 1..8 && rate in 8000..384000) { "Unsupported channel count or sample rate" }
        require((format==3 && (bits==32 || bits==64)) || (format==1 && (bits==16 || bits==24 || bits==32))) { "Supported inputs: 16/24/32-bit PCM or 32/64-bit float WAV" }
        require(align==channels*bits/8) { "Unsupported packed or padded WAV layout" }
        remaining=size; spec=WavSpec(channels,rate,format,bits,size/align)
    }
    fun readSamples(maxSamples:Int):DoubleArray {
        val bytesPerSample=spec.bits/8; val bytesToRead=min(remaining,(maxSamples*bytesPerSample).toLong()).toInt()
        val actual=bytesToRead-(bytesToRead%bytesPerSample); if(actual<=0) return DoubleArray(0)
        val bytes=ByteArray(actual); var p=0; while(p<actual) { val n=stream.read(bytes,p,actual-p); if(n<0) throw EOFException("Truncated data"); p+=n }; remaining-=actual
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(actual/bytesPerSample) {
            if(spec.format==3) { if(spec.bits==32) b.float.toDouble() else b.double }
            else when(spec.bits) {
                16 -> b.short.toDouble()/32768.0
                24 -> { val a=b.get().toInt() and 255; val c=b.get().toInt() and 255; val d=b.get().toInt(); ((d shl 16) or (c shl 8) or a).toDouble()/8388608.0 }
                else -> b.int.toDouble()/2147483648.0
            }
        }
    }
    override fun close()=stream.close()
}
