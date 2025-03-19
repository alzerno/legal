package com.example.legalevidence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.app.AlertDialog
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.security.MessageDigest

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: Button
    private lateinit var videoButton: Button
    private lateinit var audioButton: Button
    private lateinit var notesButton: Button
    private lateinit var locationText: TextView
    private lateinit var timestampText: TextView
    private lateinit var evidenceListButton: Button
    
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFile: String = ""
    
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUEST_VIDEO_CAPTURE = 1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI components
        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.capture_button)
        videoButton = findViewById(R.id.video_button)
        audioButton = findViewById(R.id.audio_button)
        notesButton = findViewById(R.id.notes_button)
        locationText = findViewById(R.id.location_text)
        timestampText = findViewById(R.id.timestamp_text)
        evidenceListButton = findViewById(R.id.evidence_list_button)
        
        // Request permissions
        if (allPermissionsGranted()) {
            startCamera()
            initLocationService()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        // Set up the listeners
        captureButton.setOnClickListener { takePhoto() }
        videoButton.setOnClickListener { recordVideo() }
        audioButton.setOnClickListener { recordAudio() }
        notesButton.setOnClickListener { addTextNote() }
        evidenceListButton.setOnClickListener {
            val intent = Intent(this, EvidenceListActivity::class.java)
            startActivity(intent)
        }
        
        // Set up output directory
        outputDirectory = getOutputDirectory()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Update timestamp periodically
        updateTimestamp()
    }
    
    private fun updateTimestamp() {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTime = sdf.format(Date())
        timestampText.text = "Timestamp: $currentTime"
        
        // Update every second
        timestampText.postDelayed({ updateTimestamp() }, 1000)
    }
    
    private fun initLocationService() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            // Request location updates
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, 10f, this
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing location service: ${e.message}")
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = createFile(outputDirectory, "jpg")
        
        val metadata = ImageCapture.Metadata().apply {
            // Add location metadata if available
            currentLocation?.let { location ->
                this.location = android.location.Location("").apply {
                    latitude = location.latitude
                    longitude = location.longitude
                }
            }
        }
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Log.d(TAG, "Photo capture succeeded: $savedUri")
                    
                    // Generate hash for file integrity
                    val fileHash = generateFileHash(photoFile)
                    
                    // Save evidence record with metadata
                    saveEvidenceRecord(
                        "Photo",
                        savedUri.toString(),
                        currentLocation?.latitude,
                        currentLocation?.longitude,
                        fileHash
                    )
                    
                    Toast.makeText(
                        baseContext,
                        "Photo saved: ${photoFile.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }
    
    private fun recordVideo() {
        Intent(MediaStore.ACTION_VIDEO_CAPTURE).also { takeVideoIntent ->
            takeVideoIntent.resolveActivity(packageManager)?.also {
                startActivityForResult(takeVideoIntent, REQUEST_VIDEO_CAPTURE)
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VIDEO_CAPTURE && resultCode == RESULT_OK) {
            val videoUri: Uri? = data?.data
            if (videoUri != null) {
                // Copy the video to our secure folder
                val videoFile = createFile(outputDirectory, "mp4")
                try {
                    contentResolver.openInputStream(videoUri)?.use { inputStream ->
                        FileOutputStream(videoFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    val savedUri = Uri.fromFile(videoFile)
                    Log.d(TAG, "Video saved: $savedUri")
                    
                    // Generate hash
                    val fileHash = generateFileHash(videoFile)
                    
                    // Save evidence record
                    saveEvidenceRecord(
                        "Video",
                        savedUri.toString(),
                        currentLocation?.latitude,
                        currentLocation?.longitude,
                        fileHash
                    )
                    
                    Toast.makeText(
                        baseContext,
                        "Video saved: ${videoFile.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: IOException) {
                    Log.e(TAG, "Error copying video file: ${e.message}")
                }
            }
        }
    }
    
    private fun recordAudio() {
        if (!isRecording) {
            startAudioRecording()
        } else {
            stopAudioRecording()
        }
    }
    
    private fun startAudioRecording() {
        try {
            outputFile = "${outputDirectory.absolutePath}/${SimpleDateFormat(
                "yyyy-MM-dd-HH-mm-ss-SSS",
                Locale.getDefault()
            ).format(Date())}.mp3"
            
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile)
                prepare()
                start()
            }
            
            isRecording = true
            audioButton.text = "Stop Recording"
            
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start audio recording: ${e.message}")
        }
    }
    
    private fun stopAudioRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            audioButton.text = "Record Audio"
            
            val audioFile = File(outputFile)
            val fileHash = generateFileHash(audioFile)
            
            saveEvidenceRecord(
                "Audio",
                Uri.fromFile(audioFile).toString(),
                currentLocation?.latitude,
                currentLocation?.longitude,
                fileHash
            )
            
            Toast.makeText(this, "Recording saved: ${File(outputFile).name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio recording: ${e.message}")
        }
    }
    
    private fun addTextNote() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add Evidence Note")
        
        val input = EditText(this)
        builder.setView(input)
        
        builder.setPositiveButton("Save") { _, _ ->
            val noteText = input.text.toString()
            if (noteText.isNotEmpty()) {
                try {
                    // Create a text file with the note
                    val noteFile = createFile(outputDirectory, "txt")
                    noteFile.writeText(noteText)
                    
                    val noteUri = Uri.fromFile(noteFile)
                    
                    // Generate hash
                    val fileHash = generateFileHash(noteFile)
                    
                    // Save evidence record
                    saveEvidenceRecord(
                        "Note",
                        noteUri.toString(),
                        currentLocation?.latitude,
                        currentLocation?.longitude,
                        fileHash
                    )
                    
                    Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Log.e(TAG, "Error saving note: ${e.message}")
                }
            }
        }
        
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
    
    private fun generateFileHash(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead = inputStream.read(buffer)
                
                while (bytesRead != -1) {
                    md.update(buffer, 0, bytesRead)
                    bytesRead = inputStream.read(buffer)
                }
            }
            
            val bytes = md.digest()
            val sb = StringBuilder()
            for (byte in bytes) {
                sb.append(String.format("%02x", byte))
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating file hash: ${e.message}")
            ""
        }
    }
    
    private fun saveEvidenceRecord(
        type: String,
        uri: String,
        latitude: Double?,
        longitude: Double?,
        fileHash: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = EvidenceDatabase.getDatabase(applicationContext)
            val evidence = Evidence(
                id = 0, // Auto-generated
                type = type,
                uri = uri,
                timestamp = System.currentTimeMillis(),
                latitude = latitude,
                longitude = longitude,
                fileHash = fileHash,
                notes = ""
            )
            db.evidenceDao().insert(evidence)
        }
    }
    
    private fun createFile(baseFolder: File, extension: String) = File(
        baseFolder, SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss-SSS",
            Locale.getDefault()
        ).format(Date()) + ".$extension"
    )
    
    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "LegalEvidence").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                initLocationService()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
    
    override fun onLocationChanged(location: Location) {
        currentLocation = location
        locationText.text = "Location: ${location.latitude}, ${location.longitude}"
    }
    
    // Required but empty LocationListener methods
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isRecording) {
            stopAudioRecording()
        }
    }
    
    companion object {
        private const val TAG = "LegalEvidenceApp"
    }
}

// Data classes and database setup
data class Evidence(
    val id: Long,
    val type: String,
    val uri: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val fileHash: String,
    val notes: String
)

// DAO interface
interface EvidenceDao {
    @androidx.room.Insert
    fun insert(evidence: Evidence)
    
    @androidx.room.Query("SELECT * FROM evidence ORDER BY timestamp DESC")
    fun getAllEvidence(): List<Evidence>
    
    @androidx.room.Query("SELECT * FROM evidence WHERE id = :id")
    fun getEvidence(id: Long): Evidence
}

// Database class
@androidx.room.Database(entities = [Evidence::class], version = 1)
abstract class EvidenceDatabase : androidx.room.RoomDatabase() {
    abstract fun evidenceDao(): EvidenceDao
    
    companion object {
        @Volatile
        private var INSTANCE: EvidenceDatabase? = null
        
        fun getDatabase(context: android.content.Context): EvidenceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    EvidenceDatabase::class.java,
                    "evidence_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Evidence List Activity
class EvidenceListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_list)
        
        val recyclerView = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        // Load evidence list
        CoroutineScope(Dispatchers.IO).launch {
            val db = EvidenceDatabase.getDatabase(applicationContext)
            val evidenceList = db.evidenceDao().getAllEvidence()
            
            runOnUiThread {
                recyclerView.adapter = EvidenceAdapter(evidenceList) { evidence ->
                    // Open evidence detail activity
                    val intent = Intent(this@EvidenceListActivity, EvidenceDetailActivity::class.java)
                    intent.putExtra("EVIDENCE_ID", evidence.id)
                    startActivity(intent)
                }
            }
        }
    }
}

// Evidence Adapter
class EvidenceAdapter(
    private val evidenceList: List<Evidence>,
    private val onItemClick: (Evidence) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<EvidenceAdapter.ViewHolder>() {
    
    class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val typeText: TextView = view.findViewById(R.id.evidence_type)
        val dateText: TextView = view.findViewById(R.id.evidence_date)
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.evidence_item, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val evidence = evidenceList[position]
        holder.typeText.text = evidence.type
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = sdf.format(Date(evidence.timestamp))
        holder.dateText.text = dateString
        
        holder.itemView.setOnClickListener { onItemClick(evidence) }
    }
    
    override fun getItemCount() = evidenceList.size
}

// Evidence Detail Activity
class EvidenceDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_detail)
        
        val evidenceId = intent.getLongExtra("EVIDENCE_ID", -1)
        if (evidenceId == -1L) {
            finish()
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            val db = EvidenceDatabase.getDatabase(applicationContext)
            val evidence = db.evidenceDao().getEvidence(evidenceId)
            
            runOnUiThread {
                displayEvidence(evidence)
            }
        }
    }
    
    private fun displayEvidence(evidence: Evidence) {
        val typeText: TextView = findViewById(R.id.detail_type)
        val dateText: TextView = findViewById(R.id.detail_date)
        val locationText: TextView = findViewById(R.id.detail_location)
        val hashText: TextView = findViewById(R.id.detail_hash)
        val mediaView: android.widget.ImageView = findViewById(R.id.media_view)
        val shareButton: Button = findViewById(R.id.share_button)
        val exportButton: Button = findViewById(R.id.export_button)
        
        // Set basic info
        typeText.text = "Type: ${evidence.type}"
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = sdf.format(Date(evidence.timestamp))
        dateText.text = "Date: $dateString"
        
        locationText.text = if (evidence.latitude != null && evidence.longitude != null) {
            "Location: ${evidence.latitude}, ${evidence.longitude}"
        } else {
            "Location: Not available"
        }
        
        hashText.text = "File Hash: ${evidence.fileHash}"
        
        // Handle media display based on type
        when (evidence.type) {
            "Photo" -> {
                mediaView.visibility = android.view.View.VISIBLE
                mediaView.setImageURI(Uri.parse(evidence.uri))
            }
            "Video" -> {
                // In a real app, we'd use a video player here
                mediaView.visibility = android.view.View.VISIBLE
                // Set a thumbnail or video player
            }
            "Audio" -> {
                // In a real app, we'd use an audio player here
                mediaView.visibility = android.view.View.GONE
            }
            "Note" -> {
                mediaView.visibility = android.view.View.GONE
                try {
                    val noteContent = File(Uri.parse(evidence.uri).path!!).readText()
                    findViewById<TextView>(R.id.note_content).apply {
                        visibility = android.view.View.VISIBLE
                        text = noteContent
                    }
                } catch (e: IOException) {
                    Log.e("EvidenceDetail", "Error reading note: ${e.message}")
                }
            }
        }
        
        // Share functionality
        shareButton.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, Uri.parse(evidence.uri))
                when (evidence.type) {
                    "Photo" -> type = "image/*"
                    "Video" -> type = "video/*"
                    "Audio" -> type = "audio/*"
                    "Note" -> type = "text/plain"
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Evidence"))
        }
        
        // Export functionality - would create a PDF report in real app
        exportButton.setOnClickListener {
            Toast.makeText(this, "Exporting evidence report...", Toast.LENGTH_SHORT).show()
            // In a real app, this would generate a complete PDF report with all metadata
        }
    }
}
