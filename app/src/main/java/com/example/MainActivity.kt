package com.example

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.print.PrintHelper
import com.example.data.PrintJob
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var currentDuplexPref by mutableStateOf(DuplexMode.ONE_SIDED)
    private var showAskDialog by mutableStateOf(false)
    private var pendingPrintFile by mutableStateOf<File?>(null)
    private var pendingMimeType by mutableStateOf("")
    private var pendingDisplayName by mutableStateOf("")

    private var showDuplexProgressDialog by mutableStateOf(false)
    private var duplexSourceFile by mutableStateOf<File?>(null)
    private var duplexDisplayName by mutableStateOf("")

    private val PREFS_NAME = "quick_print_prefs"
    private val KEY_DUPLEX_MODE = "preferred_duplex_mode"

    private fun getSavedDuplexMode(): DuplexMode {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_DUPLEX_MODE, DuplexMode.ONE_SIDED.name) ?: DuplexMode.ONE_SIDED.name
        return try {
            DuplexMode.valueOf(name)
        } catch (e: Exception) {
            DuplexMode.ONE_SIDED
        }
    }

    private fun saveDuplexMode(mode: DuplexMode) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DUPLEX_MODE, mode.name).apply()
    }

    private fun getReadableFileSize(file: File?): String {
        if (file == null || !file.exists()) return "0 B"
        val bytes = file.length()
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        currentDuplexPref = getSavedDuplexMode()

        setContent {
            MyApplicationTheme {
                val lastPrintJob by viewModel.lastPrintJob.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(
                        paddingValues = innerPadding,
                        lastPrintJob = lastPrintJob,
                        preferredDuplexMode = currentDuplexPref,
                        onDuplexModeChanged = { mode ->
                            currentDuplexPref = mode
                            saveDuplexMode(mode)
                        },
                        onPrintAgain = { job ->
                            handlePrintFlow(File(job.localFilePath), job.mimeType, job.displayName)
                        },
                        onClearHistory = {
                            clearCachedFiles()
                            viewModel.clearPrintJob()
                        }
                    )
                }

                if (showAskDialog) {
                    val file = pendingPrintFile
                    AskDuplexDialog(
                        fileName = pendingDisplayName,
                        fileSizeString = getReadableFileSize(file),
                        onDismiss = {
                            showAskDialog = false
                            pendingPrintFile = null
                        },
                        onSelectMode = { selectedMode ->
                            showAskDialog = false
                            val fileToPrint = pendingPrintFile
                            val mime = pendingMimeType
                            val name = pendingDisplayName
                            pendingPrintFile = null
                            if (fileToPrint != null) {
                                if (selectedMode == DuplexMode.DOUBLE_SIDED_MANUAL) {
                                    startDuplexPrintFlow(fileToPrint, mime, name)
                                } else {
                                    triggerPrint(fileToPrint, mime, name)
                                }
                            }
                        }
                    )
                }

                if (showDuplexProgressDialog) {
                    DuplexProgressDialog(
                        onContinue = {
                            printOddPagesAndFinish()
                        },
                        onCancel = {
                            showDuplexProgressDialog = false
                            duplexSourceFile = null
                        }
                    )
                }
            }
        }

        // Handle the intent that launched this activity
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                if (type != null) {
                    val uri = getStreamUri(intent)
                    if (uri != null) {
                        processIncomingUri(uri, type)
                    } else {
                        showToast("No printable stream found in share.")
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    val resolvedMimeType = type
                        ?: contentResolver.getType(uri)
                        ?: if (uri.path?.endsWith(".pdf", ignoreCase = true) == true) "application/pdf" else "image/jpeg"
                    processIncomingUri(uri, resolvedMimeType)
                } else {
                    showToast("No document data found to print.")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun processIncomingUri(uri: Uri, mimeType: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val displayName = getDisplayName(uri) ?: "SharedDocument"
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        showToast("Unable to access the shared file.")
                    }
                    return@launch
                }

                // Prepare a clean local files/printed_files directory
                val printDir = File(filesDir, "printed_files").apply { mkdirs() }
                // Clear any old printed files to keep storage minimal
                printDir.listFiles()?.forEach { it.delete() }

                // Create local file keeping matching extension
                val extension = getExtensionForMimeType(mimeType)
                val localFile = File(printDir, "print_target$extension")

                inputStream.use { input ->
                    localFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Save to database
                viewModel.savePrintJob(localFile.absolutePath, mimeType, displayName)

                // Instantly launch printing dialog
                withContext(Dispatchers.Main) {
                    handlePrintFlow(localFile, mimeType, displayName)
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing URI", e)
                withContext(Dispatchers.Main) {
                    showToast("Error loading document: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to query display name", e)
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun getExtensionForMimeType(mimeType: String): String {
        return when {
            mimeType.contains("pdf", ignoreCase = true) -> ".pdf"
            mimeType.contains("png", ignoreCase = true) -> ".png"
            mimeType.contains("jpg", ignoreCase = true) || mimeType.contains("jpeg", ignoreCase = true) -> ".jpg"
            mimeType.contains("gif", ignoreCase = true) -> ".gif"
            mimeType.contains("webp", ignoreCase = true) -> ".webp"
            mimeType.startsWith("image/") -> ".jpg"
            else -> ""
        }
    }

    private fun handlePrintFlow(file: File, mimeType: String, displayName: String) {
        val duplexMode = getSavedDuplexMode()
        when (duplexMode) {
            DuplexMode.ONE_SIDED -> {
                triggerPrint(file, mimeType, displayName)
            }
            DuplexMode.DOUBLE_SIDED_MANUAL -> {
                startDuplexPrintFlow(file, mimeType, displayName)
            }
            DuplexMode.ASK_EVERY_TIME -> {
                pendingPrintFile = file
                pendingMimeType = mimeType
                pendingDisplayName = displayName
                showAskDialog = true
            }
        }
    }

    private fun startDuplexPrintFlow(file: File, mimeType: String, displayName: String) {
        val isPdf = mimeType.contains("pdf", ignoreCase = true)
        if (!isPdf) {
            showToast("Photos are printed single-sided. Printing normally.")
            triggerPrint(file, mimeType, displayName)
            return
        }

        val pageCount = PdfDuplexHelper.getPdfPageCount(file)
        if (pageCount <= 1) {
            showToast("Document has only $pageCount page. Printing normally.")
            triggerPrint(file, mimeType, displayName)
            return
        }

        val evenIndices = (1 until pageCount step 2).reversed().toList()
        if (evenIndices.isEmpty()) {
            triggerPrint(file, mimeType, displayName)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val printDir = File(filesDir, "printed_files")
            val evenFile = File(printDir, "even_pages.pdf")
            val success = PdfDuplexHelper.rearrangePdf(file, evenIndices, evenFile)
            
            withContext(Dispatchers.Main) {
                if (success && evenFile.exists()) {
                    duplexSourceFile = file
                    duplexDisplayName = displayName
                    showDuplexProgressDialog = true
                    
                    triggerPrint(evenFile, "application/pdf", "$displayName (Even Pages)")
                } else {
                    showToast("Failed to prepare even pages for duplex printing.")
                }
            }
        }
    }

    private fun printOddPagesAndFinish() {
        val file = duplexSourceFile
        val displayName = duplexDisplayName
        if (file == null || !file.exists()) {
            showToast("Source file is no longer available.")
            showDuplexProgressDialog = false
            return
        }

        val pageCount = PdfDuplexHelper.getPdfPageCount(file)
        val oddIndices = (0 until pageCount step 2).toList()

        lifecycleScope.launch(Dispatchers.IO) {
            val printDir = File(filesDir, "printed_files")
            val oddFile = File(printDir, "odd_pages.pdf")
            val success = PdfDuplexHelper.rearrangePdf(file, oddIndices, oddFile)

            withContext(Dispatchers.Main) {
                showDuplexProgressDialog = false
                if (success && oddFile.exists()) {
                    triggerPrint(oddFile, "application/pdf", "$displayName (Odd Pages)")
                } else {
                    showToast("Failed to prepare odd pages for duplex printing.")
                }
            }
        }
    }

    private fun triggerPrint(file: File, mimeType: String, displayName: String) {
        if (!file.exists()) {
            showToast("Cached print file no longer exists.")
            return
        }

        try {
            if (mimeType.contains("pdf", ignoreCase = true)) {
                val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val printAdapter = PdfPrintAdapter(
                    inputStreamProvider = { FileInputStream(file) },
                    documentName = displayName
                )
                printManager.print(
                    displayName,
                    printAdapter,
                    PrintAttributes.Builder().build()
                )
            } else if (mimeType.startsWith("image/")) {
                val printHelper = PrintHelper(this).apply {
                    scaleMode = PrintHelper.SCALE_MODE_FIT
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    printHelper.printBitmap(displayName, bitmap)
                } else {
                    showToast("Failed to render the shared photo.")
                }
            } else {
                showToast("Unsupported print type: $mimeType")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Print failure", e)
            showToast("Failed to open Print dialog: ${e.localizedMessage}")
        }
    }

    private fun clearCachedFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val printDir = File(filesDir, "printed_files")
                if (printDir.exists()) {
                    printDir.listFiles()?.forEach { it.delete() }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error clearing cache", e)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun MainScreen(
    paddingValues: PaddingValues,
    lastPrintJob: PrintJob?,
    preferredDuplexMode: DuplexMode,
    onDuplexModeChanged: (DuplexMode) -> Unit,
    onPrintAgain: (PrintJob) -> Unit,
    onClearHistory: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // App Branding/Hero Area - Elegant rounded box matching the w-32 h-32 rounded-[40px]
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_printer_hero),
                    contentDescription = "Quick Print Hero Illustration",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Text(
                text = "Quick Print Helper",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("app_title")
            )

            Text(
                text = "Share any PDF or photo to this app to print it directly using Android's system dialog.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 24.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 8.dp)
            )

            // Pill-shaped Segmented Button Group (prominent, centered, above pseudo-indicator)
            SegmentedControl(
                currentMode = preferredDuplexMode,
                onModeChanged = onDuplexModeChanged,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Dynamic Status Indicator
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50.dp)
                        )
                )
                Text(
                    text = "Ready to receive file",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Bottom elements: Dynamic history or No recent documents hint
            Spacer(modifier = Modifier.height(8.dp))

            if (lastPrintJob == null) {
                Text(
                    text = "No recent documents",
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // Dynamic Last printed file / Print Again card
            AnimatedVisibility(
                visible = lastPrintJob != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut()
            ) {
                if (lastPrintJob != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 320.dp)
                            .testTag("last_print_card"),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = null
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Last Printed Document",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val isPdf = lastPrintJob.mimeType.contains("pdf", ignoreCase = true)
                                Icon(
                                    imageVector = if (isPdf) Icons.Default.Description else Icons.Default.Image,
                                    contentDescription = if (isPdf) "PDF File" else "Photo File",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lastPrintJob.displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val formattedTime = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                                        .format(Date(lastPrintJob.timestamp))
                                    Text(
                                        text = formattedTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onPrintAgain(lastPrintJob) },
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .height(56.dp)
                                        .testTag("print_again_button"),
                                    shape = RoundedCornerShape(50.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Print,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Print Again", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = onClearHistory,
                                    modifier = Modifier
                                        .weight(0.7f)
                                        .height(56.dp)
                                        .testTag("clear_history_button"),
                                    shape = RoundedCornerShape(50.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = ButtonDefaults.outlinedButtonBorder.copy()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear History",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentedControl(
    currentMode: DuplexMode,
    onModeChanged: (DuplexMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        DuplexMode.ONE_SIDED to "One-sided",
        DuplexMode.DOUBLE_SIDED_MANUAL to "Double-sided",
        DuplexMode.ASK_EVERY_TIME to "Ask every time"
    )

    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .border(width = 1.dp, color = borderColor, shape = CircleShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEachIndexed { index, (mode, label) ->
            val isSelected = currentMode == mode
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onModeChanged(mode) }
                    .testTag("segment_${mode.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (index < modes.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(borderColor)
                )
            }
        }
    }
}

@Composable
fun AskDuplexDialog(
    fileName: String,
    fileSizeString: String,
    onDismiss: () -> Unit,
    onSelectMode: (DuplexMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Print Options",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "$fileName ($fileSizeString)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "How would you like to print this document?",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { onSelectMode(DuplexMode.ONE_SIDED) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text("One-sided", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onSelectMode(DuplexMode.DOUBLE_SIDED_MANUAL) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("Double-sided (manual)", fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

@Composable
fun DuplexProgressDialog(
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "Manual Duplex",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Text(
                text = "Even pages printed.\n\nFlip the paper stack and tap Continue to print odd pages.",
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(50.dp)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}


