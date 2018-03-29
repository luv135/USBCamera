package com.unistrong.luowei.usbcamera

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.usb.UsbDevice
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import com.unistrong.luowei.cameralib.base.ICamera
import com.unistrong.luowei.cameralib.base.IPreviewCallback
import com.unistrong.luowei.cameralib.impl.uvc.CameraHelper
import com.unistrong.luowei.cameralib.impl.uvc.USBCamera
import com.unistrong.luowei.cameralib.impl.uvc.UVCDevice
import com.unistrong.luowei.commlib.Log
import com.unistrong.luowei.kotlin.hide
import com.unistrong.luowei.kotlin.show
import com.unistrong.luowei.qrcodelib.impl.AsyncQRCoder
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File


class MainActivity : AppCompatActivity() {

    var camera: USBCamera? = null
    private var point: Point? = Point(2592, 1944)
    private var qrCoder = AsyncQRCoder()
    private var lastQrText: String? = null

    private val qrCallback: (text: String?, bitmap: Bitmap?) -> Unit = { text, bitmap ->
        text?.takeIf { it != lastQrText }
                ?.let {
                    lastQrText = it
                    runOnUiThread {
                        Log.d("text = $it")
                        qrTextView.text = it
//                        com.unistrong.luowei.commlib.Toast.shortToast(it)
                    }
                }
    }
    private val deviceCallback: (device: UsbDevice) -> Unit = {
        if (CameraHelper.isHDCamera(it) || CameraHelper.isPCCamera(it)) {
            tryOpenCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //        preview.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT)
        sendBroadcast(Intent("com.unistrong.luowei.LED_OCR_ON"))
        setupTackPicture()
        setupUI()
        com.unistrong.luowei.commlib.Toast.initToast(this)
        checkPermission()
    }

    override fun onResume() {
        super.onResume()
//        LightControl.ledCameraOn(activity)
        tryOpenCamera()
        CameraHelper.registerDevice(this.application, deviceCallback)
        startQrWork()
    }

    override fun onPause() {
        super.onPause()
        CameraHelper.unregisterDevice(this.application, deviceCallback)
        stopQrWork()
        camera?.close()
        camera = null
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkPermission()
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE), 0)
            return false
        }
        return true
    }


    private fun setupUI() {

        findViewById<View>(R.id.switchButton).setOnClickListener {
            val cameras = CameraHelper.getCameras(this)
            index = if (index + 1 < cameras?.size ?: 0) index + 1 else 0
            tryOpenCamera()
        }
        findViewById<View>(R.id.resolutionRateButton).setOnClickListener {
            if (camera?.getConfig()?.supportResolution != null) {
                AlertDialog.Builder(this)
                        .setTitle(getString(R.string.resolution_ratio))
                        .setSingleChoiceItems(
                                camera!!.getConfig().supportResolution!!
                                        .map { it.toString() }
                                        .toTypedArray(),
                                -1) { dialog, which ->
                            point = camera!!.getConfig().supportResolution!![which]
                            //                            preview.setAspectRatio(point!!.x, point!!.y)
                            camera?.updateResolution(point!!)
                            //                            uvc_camera_log_text_view.text = "$camerahardware \n Resolution:${camera!!.getConfig().currentResolution}"
                            //                            openCamera()
                            dialog.dismiss()
                        }
                        .show()
            }
        }
        okImageButton.setOnClickListener {
            operatorLayout2.hide()
            operatorLayout.show()
            val action = intent.action
            if (action == MediaStore.ACTION_IMAGE_CAPTURE) {
                returnBitmap()
                finish()
            }

            pictureView.hide()
        }
        cancelImageButton.setOnClickListener {
            operatorLayout2.hide()
            operatorLayout.show()
            pictureView.hide()
        }
    }

    private var mediaScannerConnection: MediaScannerConnection? = null

    private fun saveBitmap(yuvImage: YuvImage, width: Int, height: Int): Boolean {
        val action = intent.action

        if (action == MediaStore.ACTION_IMAGE_CAPTURE) {
            val bundle = intent.extras
            if (bundle != null) {
                mFileUri = bundle.getParcelable<Uri>(MediaStore.EXTRA_OUTPUT)
//                contentResolver.openOutputStream(mFileUri).apply {
//                    write(picBitmap!!.toByteArray(Bitmap.CompressFormat.PNG, 100))
//                    close()
//                }
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 100,
                        contentResolver.openOutputStream(mFileUri))
            }
            return true
        }
        val dir = File(Environment.getExternalStorageDirectory(), "DCIM").apply { mkdirs() }
        var path = ""
        val outputstream = File(dir, "${System.currentTimeMillis().toString()}_${point}.jpg").apply {
            path = absolutePath
        }.outputStream()
//        FileOutputStream(path)
//                .apply { write(picBitmap!!.toByteArray(Bitmap.CompressFormat.PNG, 100)) }
//                .close()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100,
                outputstream)
        mediaScannerConnection = MediaScannerConnection(this, object : MediaScannerConnection.MediaScannerConnectionClient {
            override fun onMediaScannerConnected() {
                mediaScannerConnection!!.scanFile(path, null)
            }

            override fun onScanCompleted(path: String?, uri: Uri?) {
                Log.d("path = $path")
                mediaScannerConnection?.disconnect()

            }

        }).apply { connect() }
        return false
    }

    private var mFileUri: Uri? = null

    private var picBitmap: Bitmap? = null

    private var takePiture: Boolean = false

    private fun setupTackPicture() {

        findViewById<View>(R.id.takePictureButton).setOnClickListener {
            takePiture = true
            Toast.makeText(this, getString(R.string.start_tack_picture), Toast.LENGTH_SHORT).show()
//
//            Log.d("拍照开始..")
//
//            val bitmap = camera?.tackPicture()
//            Log.d("拍照完成..")
//            bitmap ?: return@setOnClickListener
//            this.picBitmap = bitmap
//            saveBitmap()
//            pictureView.setImageBitmap(picBitmap)
            operatorLayout.hide()
//            Toast.makeText(this, getString(R.string.tack_picture_done), Toast.LENGTH_SHORT).show()
        }
    }

    private fun returnBitmap() {
        val intent = Intent("android.media.action.IMAGE_CAPTURE")
        if (mFileUri != null) {
            intent.setData(mFileUri)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }


    var index = 0

    private var camerahardware: String = ""

    private val callback: IPreviewCallback = object : IPreviewCallback {
        override fun onPreviewFrame(byteArray: ByteArray, camera: ICamera) {
            qrCoder.onPreviewFrame(byteArray, camera)
            if (takePiture) {
                takePiture = false
                val config = camera.getConfig()
                val width = config.currentResolution.x
                val height = config.currentResolution.y
//                val out = ByteArrayOutputStream()
                val yuvImage = YuvImage(byteArray, ImageFormat.NV21, width,
                        height, null)
//                yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
//                val raw = out.toByteArray()
//                picBitmap?.recycle()
//                picBitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                saveBitmap(yuvImage, width, height)
                runOnUiThread {
                    operatorLayout2.show()
//                    pictureView.setImageBitmap(picBitmap)
                    Toast.makeText(this@MainActivity, getString(R.string.tack_picture_done), Toast.LENGTH_SHORT).show()
                }
            }
        }

    }

    private fun openCamera() {
        camera?.close()
        camera = USBCamera(this)
        if (point != null)
            camera!!.getConfig().currentResolution = point!!
        val cameras = camera!!.getCameras()
        if (cameras.isEmpty()) return
        val iDevice = cameras[index]

        camerahardware = "(当前$index,检测到${cameras.size}个摄像头)\ncamera Info:\n productName=${(iDevice as UVCDevice).device.productName},\n vendorId${iDevice.device.vendorId},productId=${iDevice.device.productId}"
//        uvc_camera_log_text_view.text = "$camerahardware \n Resolution:${camera!!.getConfig().currentResolution}"
        camera!!.open(iDevice, preview)
//        camera!!.open(iDevice, preview.uvcPreviewView)
        camera!!.setPreviewCallback(callback)
//        qrCoder.scanArea = WeakReference(preview)
    }


    private fun tryOpenCamera() {
        val cameras = CameraHelper.getCameras(this)
        if (index < cameras.size) {
            openCamera()
        }
    }

    private fun startQrWork() {
        qrCoder.setResultCallback(qrCallback)
        qrCoder.start()
    }

    fun stopQrWork() {
        qrCoder.setResultCallback(null)
        qrCoder.stop()
    }
}
