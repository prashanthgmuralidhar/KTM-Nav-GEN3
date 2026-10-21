package com.navigator.app.notifications

import android.content.Context
import android.graphics.Bitmap
import com.navigator.app.R
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device turn-icon recogniser, ported from the approach used by the KTM
 * Gen-3 companion app (com.bikeconnect.app): instead of a fragile pixel
 * heuristic, a small TensorFlow Lite CNN looks at the nav app's maneuver-icon
 * bitmap and outputs the dash turn-icon code directly - so Google Maps' icons
 * (including every roundabout exit, which the heuristic could never tell apart)
 * are classified robustly.
 *
 * Pipeline (matching the training script): take the icon's alpha channel as a
 * white glyph on black, scale to 96x96, feed RGB floats [0,1], run the model,
 * take the arg-max class; accept it only if its
 * confidence is >= [CONFIDENCE]. The 34 output classes are the dash turn-icon
 * binary codes (plus three named fall-backs), so a numeric label maps straight
 * to [BccuProtocol.TurnIcon.fromBinary].
 */
object ManeuverClassifier {

    private const val INPUT = 96
    private const val CONFIDENCE = 0.4f

    // Output class labels in model order. Loaded at runtime from
    // res/raw/maneuver_labels.txt so a retrained model + labels file drop in
    // with no code change; falls back to this embedded list (the order of the
    // currently-bundled model). A numeric entry is a dash TurnIcon binary code;
    // named entries are Google Maps icon-resource fall-backs.
    private val FALLBACK_LABELS = arrayOf(
        "1", "11", "12", "13", "16", "17", "2", "21", "27", "29", "3", "31",
        "32", "33", "35", "37", "39", "4", "41", "43", "45", "47", "48", "49",
        "51", "53", "55", "57", "6", "7", "8",
        "big_trans_directions_roundabout", "big_trans_directions_roundabout_lhs", "merge",
    )
    @Volatile private var labels: Array<String>? = null

    private fun labels(context: Context): Array<String> {
        labels?.let { return it }
        val loaded = try {
            val id = context.resources.getIdentifier("maneuver_labels", "raw", context.packageName)
            if (id != 0) {
                context.resources.openRawResource(id).bufferedReader().useLines { seq ->
                    seq.map { it.trim() }.filter { it.isNotEmpty() }.toList().toTypedArray()
                }.takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Throwable) {
            AppLogger.log("Maneuver", "!! labels load failed, using embedded: $e"); null
        }
        return (loaded ?: FALLBACK_LABELS).also { labels = it }
    }

    private val NAMED: Map<String, BccuProtocol.TurnIcon> = mapOf(
        "big_trans_directions_roundabout" to BccuProtocol.TurnIcon.RAB_SECT_8_RH,
        "big_trans_directions_roundabout_lhs" to BccuProtocol.TurnIcon.RAB_SECT_8_LH,
        "merge" to BccuProtocol.TurnIcon.ENTER_HIGHWAY_LEFT_LANE,
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var unavailable = false
    // Cache by bitmap-pixel hash: the same maneuver bitmap recurs many times per
    // ride, so we only run the model once per distinct icon.
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, BccuProtocol.TurnIcon>()

    private fun ensureModel(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (unavailable) return null
        synchronized(this) {
            interpreter?.let { return it }
            return try {
                val bytes = context.resources.openRawResource(R.raw.maneuver_model).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buf.put(bytes); buf.rewind()
                Interpreter(buf).also { interpreter = it }
            } catch (e: Throwable) {
                AppLogger.log("Maneuver", "!! Failed to load TFLite model: $e")
                unavailable = true
                null
            }
        }
    }

    /** Classify a nav maneuver bitmap into a dash TurnIcon, or null if the model isn't confident. */
    fun classify(context: Context, bitmap: Bitmap): BccuProtocol.TurnIcon? {
        val hash = hashOf(bitmap)
        cache[hash]?.let { return it }
        val model = ensureModel(context) ?: return null
        val lbls = labels(context)
        val result = try {
            val input = preprocess(bitmap)
            val output = Array(1) { FloatArray(lbls.size) }
            model.run(input, output)
            val scores = output[0]
            var best = 0
            for (i in scores.indices) if (scores[i] > scores[best]) best = i
            if (scores[best] < CONFIDENCE) {
                AppLogger.log("Maneuver", "No confident match (best=${lbls[best]} @ ${"%.2f".format(scores[best])})")
                null
            } else {
                labelToIcon(lbls[best])?.also {
                    AppLogger.log("Maneuver", "Model -> ${lbls[best]} = ${it.name} (${"%.2f".format(scores[best])})")
                }
            }
        } catch (e: Throwable) {
            AppLogger.log("Maneuver", "!! inference failed: $e")
            null
        }
        if (result != null) cache[hash] = result
        return result
    }

    private fun labelToIcon(label: String): BccuProtocol.TurnIcon? {
        val n = label.toIntOrNull()
        return if (n != null) BccuProtocol.TurnIcon.fromBinary(n) else NAMED[label]
    }

    /**
     * Scale to 96x96 and emit the alpha channel as white-on-black RGB floats
     * [0,1], exactly what ml/train_maneuver_model.py trains on. Using alpha
     * (not RGB) matters: Google Maps tints its maneuver icon black
     * (tint=0xff000000), which composited onto black is an all-black input.
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = if (bitmap.width == INPUT && bitmap.height == INPUT) bitmap
            else Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val buf = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                val a = ((scaled.getPixel(x, y) ushr 24) and 0xFF) / 255f
                buf.putFloat(a); buf.putFloat(a); buf.putFloat(a)
            }
        }
        buf.rewind()
        if (scaled != bitmap) scaled.recycle()
        return buf
    }

    private fun hashOf(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
}
