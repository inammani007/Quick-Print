package com.example

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class PdfPrintAdapter(
    private val inputStreamProvider: () -> InputStream?,
    private val documentName: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(documentName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        var input: InputStream? = null
        var output: FileOutputStream? = null

        try {
            input = inputStreamProvider()
            if (input == null) {
                callback.onWriteFailed("Could not open input stream")
                return
            }
            output = FileOutputStream(destination.fileDescriptor)

            val buf = ByteArray(16384)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } >= 0) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                output.write(buf, 0, bytesRead)
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            Log.e("PdfPrintAdapter", "Error writing PDF: ", e)
            callback.onWriteFailed(e.message)
        } finally {
            try { input?.close() } catch (ignored: IOException) {}
            try { output?.close() } catch (ignored: IOException) {}
        }
    }
}
