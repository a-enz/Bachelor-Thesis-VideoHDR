package aenz.videohdr;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.List;

import aenz.renderscript.PreviewFuseProcessor;

/**
 * Created by andi on 13.07.2015.
 *
 * Any method concerning the camera hardware should go in here.
 *
 */
public class HdrCamera {

    private static final String TAG = "HdrCamera";

    /* CAMERA DEVICE AND SESSION VARIABLES*/
    private CameraManager mManager;
    private CameraDevice mCameraDevice;
    private CameraCharacteristics mCameraCharacteristics;
    private String mCameraID;
    private CameraCaptureSession mCameraSession;

    /* THE ACTIVITY USING THE DEVICE */
    private Activity mAssociatedActivity;

    /* THREADING FOR CAMERA OPERATIONS */
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    /* PREVIEW TEXTURE */
    private AutoFitTextureView mPreviewTextureView;

    /* SIZES FOR THE DIFFERENT SURFACES */
    private Size mPreviewSize;
    private Size mRecordSize;
    private Size mHistogramSize;

    /* Consumer Surfaces of this camera */
    /* Will probably contain the MediaRecorder surface and several Renderscripts
       (Histogram, Preview generation)
     */
    private List<Surface> mConsumerSurfaces = Arrays.asList(null, null); //TODO maybe instantiate better elsewhere

    /* Object handling the Video recording of this camera */
    /* currently deactivated to debug preview surface stuff. Because this needs debugging itself:
     * the second time a VideoRecorder is created MediaRecorder.prepare() fails. this occurs usually
     * after tilting the phone after a preview can be seen */
    private VideoRecorder mVideoRecorder; //TODO reactive and initialize with proper video sizes (StreamconfigurationMatp)

    //PreviewFuseProcessor in charge of fusing double exposure frames by passing it through a renderscript
    private PreviewFuseProcessor mPreviewFuseProcessor;
    //Renderscript object used for two scripts: preview fusion and histogram
    private RenderScript mRS;

    //Configurable Capture Session that triggers camera frame capture
    private AlternatingSession mCaptureSession;
    //Listener for preview changes made from the camera
    private ConfigurePreviewListener mConfigPreviewListener;

    /**
     * Creator Method of this class. Instantiate the Camera with desired capabilities like
     * back facing, a sufficient hardware support level, a.s.o
     * A Thread to handle camera operation in the background is also created.
     *
     * @param activity associated activity
     */

    public HdrCamera(Activity activity, ConfigurePreviewListener previewListener){
        //instantiate with the correct camera ID, everything else is done by callback

        mConfigPreviewListener = previewListener;
        mAssociatedActivity = activity;
        mManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        mRS = RenderScript.create(activity);

        //pick an actual camera device: we want a back facing camera with certain capabilities
        createWithCapabilities();

        //starting camera thread
        mCameraThread = new HandlerThread("CameraOpsThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

    }



    /* CAMERA STATE & RESOURCES METHODS */

    private final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;

            //create alternating session and start first request
            /* no need to call AlternatingSession.setAlternatingCapture here since after creation of
            * aCaptureSession a Callback (onConfigured) will directly invoke that method
            * for now we just assume that it is best to reset the values every time the camera is closed and then
            * opened again*/

            //TODO later on this will probably be a global object since we need to access setter method from histogram/exposure metering
            mCaptureSession = new AlternatingSession(HdrCamera.this,
                    mConsumerSurfaces,
                    mCameraHandler);

        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            Log.d(TAG, "onDisconnected: CAMERA is closed");
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            Log.d(TAG, "on Error: CAMERA is closed");
            mCameraDevice = null;
        }
    };


    private boolean createWithCapabilities(){

        try{
            //we need a back facing camera that allows for per-frame control
            for(String id : mManager.getCameraIdList()){
                mCameraCharacteristics = mManager.getCameraCharacteristics(id);

                int facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);

                int level = mCameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                boolean hasFullLevel
                        = (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);

                int[] capabilities = mCameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                int syncLatency = mCameraCharacteristics.get(CameraCharacteristics.SYNC_MAX_LATENCY);
                boolean hasManualControl = hasCapability(capabilities,
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
                boolean hasEnoughCapability = hasManualControl &&
                        syncLatency == CameraCharacteristics.SYNC_MAX_LATENCY_PER_FRAME_CONTROL;

                // All these are guaranteed by
                // CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, but checking for only
                // the things we care about expands range of devices we can run on
                // We want:
                //  - Back-facing camera
                //  - Manual sensor control
                //  - Per-frame synchronization (so that exposure can be changed every frame)
                if (facing == CameraCharacteristics.LENS_FACING_BACK &&
                        (hasFullLevel || hasEnoughCapability)) {
                    // Found suitable camera - set cameraID
                    mCameraID = id;
                    return true;
                }
            }
        } catch (CameraAccessException e){
            Log.d(TAG, "accessing the camera failed");
        }
        //TODO error dialog in case no camera was found
        return false;
    }

    /* Open Method for the camera, needs to run on background thread since it is a long operation */
    public void openCamera(AutoFitTextureView previewTexture){

        mPreviewTextureView = previewTexture;

        //figure out good preview and recording sizes (and size of histogram input)
        mPreviewSize = configurePreview(mPreviewTextureView);
        mRecordSize = mPreviewSize;
        mHistogramSize = mPreviewSize;

        //TODO configure also size of recorded video. preview depends on it?
        //first configure the preview texture

        /* create VideoRecorder, PreviewFuseProcessor, HistogramProcessor and
            connect their surfaces to camera & preview texture*/
        setupSurfaces();

        //open the camera device with defined State Callbacks and a Handler to the camera thread
        mCameraHandler.post(new Runnable() {
            public void run() {
                if (mCameraDevice != null) {
                    throw new IllegalStateException("Camera already open");
                }
                try {
                    mManager.openCamera(mCameraID, mCameraStateCallback, mCameraHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                    //TODO error handling
                }
            }
        });

        /* starting an alternating capture session is only done after successful opening the camera
        * and is handled by the mCameraStateCallback object */
    }

    public void closeCamera(){
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {

                mPreviewFuseProcessor.stop(); //no longer fuse
                mCaptureSession.close();

                if(mVideoRecorder != null) {
                    mVideoRecorder.release();
                    mVideoRecorder = null;
                }

                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }



            }
        });
    }



    /* CAMERA OPERATION METHODS */

    /* configure preview size depending on possible texture sizes provided by the camera hardware
    * found in CameraCharacteristics
    * */
    private Size configurePreview(AutoFitTextureView textureView){

        //get possible sizes for use with SurfaceTextures
        Size previewSize = VideoSizeConfiguration.choosePreviewSize(
                mCameraCharacteristics,
                textureView.getWidth(),
                textureView.getHeight());

        Log.d(TAG, "PreviewSize chosen is: " + previewSize);

        int orientation = mAssociatedActivity.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        Log.d(TAG, "opening configTrans from configPrev with: w=" + textureView.getWidth() + ", h=" + textureView.getHeight());
        mConfigPreviewListener.onOrientationChanged(previewSize, textureView.getWidth(), textureView.getHeight());

        return previewSize;
    }

    /**
     * Set up the consumer surfaces of the camera, should not have more than 4
     * Also configure the output of the preview-fuser to the preview surface
     *
     */

    private void setupSurfaces(){
        int rotation = mAssociatedActivity.getWindowManager().getDefaultDisplay().getRotation();


        //preview surface (texture view)
        SurfaceTexture texture = mPreviewTextureView.getSurfaceTexture();
        assert texture!=null;
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(texture); //create surface for the textureView



        //set up PreviewFuseProcessor
        mPreviewFuseProcessor = new PreviewFuseProcessor(mRS, mPreviewSize);
        Surface previewFuseSurface = mPreviewFuseProcessor.getInputSurface();



        //set up the VideoRecorder for the correct size and orientation of captured frames
        mVideoRecorder = new VideoRecorder(mAssociatedActivity, rotation, mRecordSize);
        Surface recorderSurface = mVideoRecorder.getRecorderSurface();



        //TODO Histogram surface


        //connect output of the fuse script to the preview texture
        mPreviewFuseProcessor.setOutputSurface(previewSurface);
        /* TODO maybe the consumer surfaces should differ between 'just preview' and 'preview & recording'*/

        //connect fusescript, recorder and exposuremetering directly to camera output
        mConsumerSurfaces.set(0, previewFuseSurface);
        mConsumerSurfaces.set(1, recorderSurface);
        //TODO update mConsumerSurfaces with a third object to make space for histogram
    }

    /* GETTER & SETTER METHODS */

    public CameraDevice getCameraDevice(){
        return mCameraDevice;
    }

    /* RECORDER OPERATION METHODS */
    public void startRecording() throws IllegalStateException {
        Log.d(TAG, "trying to start recording");
        mVideoRecorder.start();
    }

    public void stopRecording() throws IllegalStateException {
        Log.d(TAG, "trying to stop recording");
        mVideoRecorder.stop();
        /* preview needs to be restarted because stopping the videorecorder will put the
        * used MediaRecorder into the Initialize state, which means no output surface is available.
        * This also means the whole camera surface connection is reset and needs to be built again.
        * essentially we need to do the whole thing that is done when opening the camera*/
        //TODO restart preview in a better way
        mCaptureSession.close(); //stop camera outputs
        mPreviewFuseProcessor.stop(); //no longer fuse

        setupSurfaces(); //reconnect surfaces

        //restart session
        mCaptureSession = new AlternatingSession(HdrCamera.this,
                mConsumerSurfaces,
                mCameraHandler);

    }




    /* HELPER METHODS */

    private boolean hasCapability(int[] capabilities, int capability) {
        for (int c : capabilities) {
            if (c == capability) return true;
        }
        return false;
    }

    public interface ConfigurePreviewListener{
        public void onOrientationChanged(Size prevSize, int width, int height);
    }
}
