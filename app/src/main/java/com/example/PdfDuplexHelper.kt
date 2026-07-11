package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

object PdfDuplexHelper {

    fun getPdfPageCount(file: File): Int {
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            return renderer.pageCount
        } catch (e: Exception) {
            Log.e("PdfDuplexHelper", "Error getting page count", e)
            return 0
        } finally {
            try { renderer?.close() } catch (ignored: Exception) {}
            try { fileDescriptor?.close() } catch (ignored: Exception) {}
        }
    }

    fun rearrangePdf(sourceFile: File, pageIndices: List<Int>, destFile: File): Boolean {
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val pdfDocument = PdfDocument()
        try {
            fileDescriptor = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            
            for ((newPageIndex, index) in pageIndices.withIndex()) {
                if (index < 0 || index >= renderer.pageCount) continue
                val srcPage = renderer.openPage(index)
                
                // Get page dimensions in points (72 points per inch)
                val width = srcPage.width
                val height = srcPage.height
                
                // Create a bitmap with the page dimensions for rendering
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                
                // Render page content to the bitmap
                srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                srcPage.close()
                
                // Create a corresponding page in the destination document
                val pageInfo = PdfDocument.PageInfo.Builder(width, height, newPageIndex).create()
                val destPage = pdfDocument.startPage(pageInfo)
                
                // Draw the rendered bitmap to the destination page canvas
                val canvas = destPage.canvas
                val paint = Paint().apply { isFilterBitmap = true }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                
                pdfDocument.finishPage(destPage)
                bitmap.recycle()
            }
            
            destFile.outputStream().use { out ->
                pdfDocument.writeTo(out)
            }
            return true
        } catch (e: Exception) {
            Log.e("PdfDuplexHelper", "Error rearranging PDF", e)
            return false
        } finally {
            pdfDocument.close()
            try { renderer?.close() } catch (ignored: Exception) {}
            try { fileDescriptor?.close() } catch (ignored: Exception) {}
        }
    }
}
