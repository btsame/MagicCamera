package com.seu.magicfilter.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.os.Environment;
import android.util.AttributeSet;
import android.util.Log;

import com.seu.magicfilter.camera.CameraEngine;
import com.seu.magicfilter.camera.utils.CameraInfo;
import com.seu.magicfilter.filter.base.MagicCameraInputFilter;
import com.seu.magicfilter.filter.helper.MagicFilterType;
import com.seu.magicfilter.helper.SavePictureTask;
import com.seu.magicfilter.utils.MagicParams;
import com.seu.magicfilter.utils.OpenGlUtils;
import com.seu.magicfilter.utils.Rotation;
import com.seu.magicfilter.utils.TextureRotationUtil;
import com.seu.magicfilter.video.TextureMovieEncoder;
import com.seu.magicfilter.widget.base.MagicBaseView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by why8222 on 2016/2/25.
 */
public class MagicCameraView extends MagicBaseView implements Camera.PreviewCallback{

    private final MagicCameraInputFilter cameraInputFilter;

    private SurfaceTexture surfaceTexture;

    public MagicCameraView(Context context) {
        this(context, null);
    }

    private boolean recordingEnabled;
    private int recordingStatus;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;
    private static TextureMovieEncoder videoEncoder = new TextureMovieEncoder();
    private File outputFile;

    public MagicCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        outputFile = new File(Environment.getExternalStorageDirectory().getPath(), "test.mp4");
        cameraInputFilter = new MagicCameraInputFilter();
        recordingStatus = -1;
        recordingEnabled = false;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        super.onSurfaceCreated(gl, config);
        recordingEnabled = videoEncoder.isRecording();
        if (recordingEnabled) {
            recordingStatus = RECORDING_RESUMED;
        } else {
            recordingStatus = RECORDING_OFF;
        }
        cameraInputFilter.init();
        if (textureId == OpenGlUtils.NO_TEXTURE) {
            textureId = OpenGlUtils.getExternalOESTextureID();
            if (textureId != OpenGlUtils.NO_TEXTURE) {
                surfaceTexture = new SurfaceTexture(textureId);
                surfaceTexture.setOnFrameAvailableListener(onFrameAvailableListener);
                CameraEngine.startPreview(surfaceTexture);
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        super.onSurfaceChanged(gl, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        super.onDrawFrame(gl);
        if (surfaceTexture == null)
            return;
        surfaceTexture.updateTexImage();
        if (recordingEnabled) {
            switch (recordingStatus) {
                case RECORDING_OFF:
                    CameraInfo info = CameraEngine.getCameraInfo();
                    videoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            outputFile, MagicParams.videoWidth, MagicParams.videoHeight,
                            1000000, EGL14.eglGetCurrentContext(),
                            info));
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    videoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        } else {
            switch (recordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    videoEncoder.stopRecording();
                    recordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        }
        float[] mtx = new float[16];
        surfaceTexture.getTransformMatrix(mtx);
        cameraInputFilter.setTextureTransformMatrix(mtx);
        int id = textureId;
        if (filter == null) {
            cameraInputFilter.onDrawFrame(textureId, gLCubeBuffer, gLTextureBuffer);
        } else {
            id = cameraInputFilter.onDrawToTexture(textureId);
            filter.onDrawFrame(id, gLCubeBuffer, gLTextureBuffer);
        }

        videoEncoder.setTextureId(id);
        videoEncoder.frameAvailable(surfaceTexture);

    }

    private void detachFrame(){
        IntBuffer ib = IntBuffer.allocate(imageWidth * imageHeight);
        GLES20.glReadPixels(0, 0, imageWidth, imageHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ib);
        Bitmap result = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        result.copyPixelsFromBuffer(IntBuffer.wrap(ib.array()));

        File file = getOutputMediaFile();

        if (file.exists()) {
            file.delete();
        }
        try {
            FileOutputStream out = new FileOutputStream(file);
            result.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.flush();
            out.close();
            result.recycle();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public File getOutputMediaFile() {
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MagicCamera");
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINESE).format(new Date());
        File mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                "IMG_" + timeStamp + ".jpg");

        return mediaFile;
    }

    private SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestRender();
        }
    };

    @Override
    public void setFilter(MagicFilterType type) {
        super.setFilter(type);
        videoEncoder.setFilter(type);
    }

    private void openCamera() {
        if (CameraEngine.getCamera() == null){
            CameraEngine.openCamera();
            CameraEngine.setPreviewCallback(this);
        }
        CameraInfo info = CameraEngine.getCameraInfo();
        if (info.orientation == 90 || info.orientation == 270) {
            imageWidth = info.previewHeight;
            imageHeight = info.previewWidth;
        } else {
            imageWidth = info.previewWidth;
            imageHeight = info.previewHeight;
        }
        cameraInputFilter.onInputSizeChanged(imageWidth, imageHeight);

        /**
         * 處理貼圖方向 很重要哦
         */
        float[] textureCords = TextureRotationUtil.getRotation(Rotation.fromInt(info.orientation),
                info.isFront, false);
        gLTextureBuffer.clear();
        gLTextureBuffer.put(textureCords).position(0);
        videoEncoder.setPreviewSize(imageWidth, imageHeight);
        if (surfaceTexture != null)
            CameraEngine.startPreview(surfaceTexture);
    }

    public void changeRecordingState(boolean isRecording) {
        recordingEnabled = isRecording;
    }

    protected void onFilterChanged() {
        super.onFilterChanged();
        cameraInputFilter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
        if (filter != null)
            cameraInputFilter.initCameraFrameBuffer(imageWidth, imageHeight);
        else
            cameraInputFilter.destroyFramebuffers();
    }

    public void onResume() {
        super.onResume();
        openCamera();
    }

    public void onPause() {
        super.onPause();
        CameraEngine.releaseCamera();
    }

    @Override
    public void savePicture(final SavePictureTask savePictureTask) {
//        detach = true;
        CameraEngine.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (filter != null) {
                    queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            final Bitmap photo = OpenGlUtils.drawToBitmapByFilter(bitmap, filter,
                                    imageWidth, imageHeight);
                            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                            filter.onInputSizeChanged(imageWidth, imageHeight);
                            if (photo != null)
                                savePictureTask.execute(photo);
                        }
                    });
                } else {
                    savePictureTask.execute(bitmap);
                }
            }
        });
    }

    public static boolean detach = false;
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if(detach){
            FileOutputStream outStream = null;
            try {
                Log.e(MagicCameraView.class.getSimpleName(), camera.getParameters().getPreviewSize().width + "*" + camera.getParameters().getPreviewSize().height);
                YuvImage yuvimage = new YuvImage(data, ImageFormat.NV21,camera.getParameters().getPreviewSize().width,camera.getParameters().getPreviewSize().height,null);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                yuvimage.compressToJpeg(new Rect(0,0,camera.getParameters().getPreviewSize().width,camera.getParameters().getPreviewSize().height), 80, baos);

                outStream = new FileOutputStream(String.format("/sdcard/%d.jpg", System.currentTimeMillis()));
                outStream.write(baos.toByteArray());
                outStream.close();

                Log.d(MagicCameraView.class.getSimpleName(), "onPreviewFrame - wrote bytes: " + data.length);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
            }



          /*  Log.e(MagicCameraView.class.getSimpleName(), "***************************" + camera.getParameters().getPreviewFormat());
            Log.e(MagicCameraView.class.getSimpleName(), imageWidth + "*" + imageHeight);//2160*3840  1080*1920
            YuvImage yuvimage = new YuvImage(data, ImageFormat.NV21, imageWidth, imageHeight,null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuvimage.compressToJpeg(new Rect(0, 0, imageWidth, imageHeight), 100, baos);

            byte[] rgbData = baos.toByteArray();

            final SavePictureTask savePictureTask = new SavePictureTask(getOutputMediaFile(), null);
            final Bitmap bitmap = BitmapFactory.decodeByteArray(rgbData, 0, rgbData.length);
            if (filter != null) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap photo = OpenGlUtils.drawToBitmapByFilter(bitmap, filter,
                                imageWidth, imageHeight);
                        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                        filter.onInputSizeChanged(imageWidth, imageHeight);
                        if (photo != null)
                            savePictureTask.execute(photo);
                    }
                });
            } else {
                savePictureTask.execute(bitmap);
            }*/
            detach = false;
        }

        camera.addCallbackBuffer(data);
    }
}
