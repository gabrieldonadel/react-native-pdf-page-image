package com.pdfpageimage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.UUID

class PdfPageImageModule(reactContext: ReactApplicationContext) :
  NativePdfPageImageSpec(reactContext) {

  override fun getName(): String = NAME

  private val pdfCache = HashMap<String, PdfDoc>()

  override fun openPdf(uri: String, promise: Promise) {
    try {
      val doc = getOrOpen(uri)
      val result = WritableNativeMap().apply {
        putString("uri", uri)
        putInt("pageCount", doc.pageCount)
      }
      promise.resolve(result)
    } catch (e: Exception) {
      promise.reject("INTERNAL_ERROR", e.message, e)
    }
  }

  override fun generate(uri: String, page: Double, scale: Double, options: ReadableMap, promise: Promise) {
    try {
      val doc = getOrOpen(uri)
      val result = doc.renderPage(page.toInt(), scale.toFloat(), RenderOptions.from(options))
      promise.resolve(result)
    } catch (e: Exception) {
      promise.reject("INTERNAL_ERROR", e.message, e)
    }
  }

  override fun generateAllPages(uri: String, scale: Double, options: ReadableMap, promise: Promise) {
    try {
      val doc = getOrOpen(uri)
      val renderOptions = RenderOptions.from(options)
      val pages = WritableNativeArray()
      for (i in 0 until doc.pageCount) {
        pages.pushMap(doc.renderPage(i, scale.toFloat(), renderOptions))
      }
      promise.resolve(pages)
    } catch (e: Exception) {
      promise.reject("INTERNAL_ERROR", e.message, e)
    }
  }

  override fun closePdf(uri: String, promise: Promise) {
    pdfCache[uri]?.close()
    pdfCache.remove(uri)
    promise.resolve(null)
  }

  private fun getOrOpen(uri: String): PdfDoc {
    pdfCache[uri]?.let { return it }
    val doc = PdfDoc(reactApplicationContext, uri)
    pdfCache[uri] = doc
    return doc
  }

  companion object {
    const val NAME = "PdfPageImage"
  }
}

// -- RenderOptions: normalized output options --

/**
 * The JS wrapper always sends all three keys; the fallbacks here only guard
 * direct native callers.
 */
private data class RenderOptions(
  val format: String,
  val quality: Int,
  val maxDimension: Int,
) {
  val isPng: Boolean get() = format == "png"
  val fileExtension: String get() = if (isPng) "png" else "jpg"

  companion object {
    fun from(map: ReadableMap): RenderOptions {
      val rawFormat = if (map.hasKey("format")) map.getString("format") else null
      val rawQuality = if (map.hasKey("quality")) map.getInt("quality") else 80
      val rawMax = if (map.hasKey("maxDimension")) map.getInt("maxDimension") else 0
      return RenderOptions(
        format = if (rawFormat == "png") "png" else "jpeg",
        quality = rawQuality.coerceIn(1, 100),
        maxDimension = rawMax.coerceAtLeast(0),
      )
    }
  }
}

// -- PdfDoc: handles loading, caching, rendering --

private class PdfDoc(
  private val context: ReactApplicationContext,
  private val uriString: String,
) {
  private val fileDescriptor: ParcelFileDescriptor
  private val renderer: PdfRenderer
  private val pageCache = HashMap<String, WritableNativeMap>()
  private val tempFiles = mutableListOf<File>()

  init {
    fileDescriptor = openFileDescriptor(uriString)
    renderer = PdfRenderer(fileDescriptor)
  }

  val pageCount: Int get() = renderer.pageCount

  fun renderPage(index: Int, scale: Float, options: RenderOptions): WritableNativeMap {
    val cacheKey = "$index:$scale:${options.format}:${options.quality}:${options.maxDimension}"
    pageCache[cacheKey]?.let {
      // Return a copy since WritableNativeMap can only be consumed once
      val copy = WritableNativeMap()
      copy.putString("uri", it.getString("uri"))
      copy.putInt("width", it.getInt("width"))
      copy.putInt("height", it.getInt("height"))
      return copy
    }

    if (index < 0 || index >= renderer.pageCount) {
      throw RuntimeException("Page number $index is invalid, file has ${renderer.pageCount} pages")
    }

    val page = renderer.openPage(index)

    // maxDimension caps the long edge: shrink the effective scale when the
    // requested scale would exceed it.
    var effectiveScale = scale
    val longEdge = maxOf(page.width, page.height)
    if (options.maxDimension > 0 && longEdge * effectiveScale > options.maxDimension) {
      effectiveScale = options.maxDimension.toFloat() / longEdge
    }

    val width = (page.width * effectiveScale).toInt().coerceAtLeast(1)
    val height = (page.height * effectiveScale).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // White background — also guarantees JPEG (no alpha) loses nothing.
    canvas.drawColor(Color.WHITE)

    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()

    val outFile = File(context.cacheDir, "${UUID.randomUUID()}.${options.fileExtension}")
    FileOutputStream(outFile).use { out ->
      // JPEG (default) is ~10-20x smaller than PNG for scanned pages.
      if (options.isPng) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
      } else {
        bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, out)
      }
    }
    bitmap.recycle()
    tempFiles.add(outFile)

    val result = WritableNativeMap().apply {
      putString("uri", "file://${outFile.absolutePath}")
      putInt("width", width)
      putInt("height", height)
    }
    pageCache[cacheKey] = result

    val copy = WritableNativeMap().apply {
      putString("uri", "file://${outFile.absolutePath}")
      putInt("width", width)
      putInt("height", height)
    }
    return copy
  }

  fun close() {
    pageCache.clear()
    for (f in tempFiles) {
      f.delete()
    }
    tempFiles.clear()
    try { renderer.close() } catch (_: Exception) {}
    try { fileDescriptor.close() } catch (_: Exception) {}
  }

  private fun openFileDescriptor(uri: String): ParcelFileDescriptor {
    return when {
      uri.startsWith("content://") -> {
        context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")
          ?: throw IOException("Cannot open content URI: $uri")
      }
      uri.startsWith("file://") -> {
        val path = uri.removePrefix("file://")
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
      }
      uri.startsWith("http://") || uri.startsWith("https://") -> {
        val tempFile = File(context.cacheDir, "pdf_${UUID.randomUUID()}.pdf")
        URL(uri).openStream().use { input ->
          FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
          }
        }
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
      }
      uri.startsWith("data:") -> {
        val commaIdx = uri.indexOf(',')
        if (commaIdx == -1) throw IOException("Invalid base64 data URI")
        val base64 = uri.substring(commaIdx + 1)
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val tempFile = File(context.cacheDir, "pdf_${UUID.randomUUID()}.pdf")
        FileOutputStream(tempFile).use { it.write(bytes) }
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
      }
      else -> {
        // Treat as absolute file path
        ParcelFileDescriptor.open(File(uri), ParcelFileDescriptor.MODE_READ_ONLY)
      }
    }
  }
}
