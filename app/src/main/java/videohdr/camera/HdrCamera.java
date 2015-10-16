package videohdr.camera;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.List;

import videohdr.capture.AlternatingCaptureSession;
import videohdr.capture.OverExpCaptureSession;
import videohdr.capture.SimpleCaptureSession;
import videohdr.capture.UnderExpCaptureSession;
import videohdr.renderscript.PreviewFuseProcessor;
import videohdr.view.AutoFitTextureView;
import videohdr.recorder.VideoRecorder;
import videohdr.recorder.VideoSizeConfiguration;

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

    /* THE ACTIVITY USING THE DEVICE */
    private Activity mAssociatedActivity;

    /* THREADING FOR CAMERA OPERATIONS */
    private Handler mCameraHandler;

    private Handler mRecorderHandler;

    /* PREVIEW TEXTURE */
    private AutoFitTextureView mPreviewTextureView;

    /* SIZES FOR THE DIFFERENT SURFACES */
    private Size mPreviewSize;
    private Size mRecordSize;
    private Size mMeteringSize;

    /* Consumer Surfaces of this camera */
    /* Will probably contain the MediaRecorder surface and several Renderscripts
       (Histogram, Preview generation)
     */
    private List<Surface> mConsumerSurfaces = Arrays.asList(null, null);

    /* Object handling the Video recording of this camera */
    /* currently deactivated to debug preview surface stuff. Because this needs debugging itself:
     * the second time a VideoRecorder is created MediaRecorder.prepare() fails. this occurs usually
     * after tilting the phone after a preview can be seen */
    private VideoRecorder mVideoRecorder;

    //PreviewFuseProcessor in charge of fusing double exposure frames by passing it through a renderscript
    private PreviewFuseProcessor mPreviewFuseProcessor;
    /*exposure metering object. should persist throughout lifetime of app. but the contained histogramProcessor
    * needs to be explicitly created/destroyed every time the camera is opened/closed
    * */
    private ExposureMeter mExposureMeter;
    //Renderscript object used for two scripts: preview fusion and histogram
    private RenderScript mRS;

    //Configurable Capture Session that triggers camera frame capture
    private SimpleCaptureSession mCaptureSession;
    //Listener for preview changes made from the camera
    private ConfigurePreviewListener mConfigPreviewListener;

    //possible camera modes
    public enum CameraState {
        MODE_FUSE,
        MODE_UNDEREXPOSE,
        MODE_OVEREXPOSE,
        MODE_RECORD
    }
    private CameraState mCameraState = CameraState.MODE_FUSE;

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
        mExposureMeter = new ExposureMeter(this);

        //pick an actual camera device: we want a back facing camera with certain capabilities
        createWithCapabilities();

        //starting camera thread
        HandlerThread mCameraThread = new HandlerThread("CameraOpsThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        //starting recorder thread
        HandlerThread mRecorderThread = new HandlerThread("RecorderOpsThread");
        mRecorderThread.start();
        mRecorderHandler = new Handler(mRecorderThread.getLooper());
    }



    /* CAMERA STATE & RESOURCES METHODS */

    private final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;

            //create alternating session and start first request
            /* no need to call AlternatingCaptureSession.setAlternatingCapture here since after creation of
            * aCaptureSession a Callback (onConfigured) will directly invoke that method
            * for now we just assume that it is best to reset the values every time the camera is closed and then
            * opened again*/


            switch(mCameraState){
                case MODE_FUSE: {
                    mCaptureSession = new AlternatingCaptureSession(HdrCamera.this,
                            mConsumerSurfaces,
                            mExposureMeter,
                            mCameraHandler);
                    break;
                }
                case MODE_OVEREXPOSE: {
                    mCaptureSession = new UnderExpCaptureSession(HdrCamera.this,
                            mConsumerSurfaces,
                            mExposureMeter,
                            mCameraHandler);
                    break;
                }
                case MODE_UNDEREXPOSE: {
                    mCaptureSession = new UnderExpCaptureSession(HdrCamera.this,
                            mConsumerSurfaces,
                            mExposureMeter,
                            mCameraHandler);
                    break;
                }
                default: Log.e(TAG, "in this mode the camera should not be opened");
            }

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
        chooseOutputSizes();

        //first configure the preview texture
        configurePreview();

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

                mCaptureSession.close();
                mPreviewFuseProcessor.stop(); //no longer fuse
                //mExposureMeter.destroyHistogramProcessor();FIXME

                if (mVideoRecorder != null) {
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
    private void chooseOutputSizes(){

        //get possible sizes for use from the camera characteristics
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        mPreviewSize = VideoSizeConfiguration.choosePreviewSize(map);

        mRecordSize = VideoSizeConfiguration.chooseVideoSize(map);

        //mMeteringSize = VideoSizeConfiguration.chooseMeteringSize(map);FIXME

    }

    private void configurePreview(){
        Log.d(TAG, "PreviewSize chosen is: " + mPreviewSize);

        int orientation = mAssociatedActivity.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mPreviewTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mPreviewTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }

        mConfigPreviewListener.onOrientationChanged(mPreviewSize, mPreviewTextureView.getWidth(), mPreviewTextureView.getHeight());

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

        //set up exposure metering with the appropriate histogram input
        //Surface meteringSurface = mExposureMeter.setupHistogramProcessor(mRS,mMeteringSize);FIXME

        //set up PreviewFuseProcessor
        mPreviewFuseProcessor = new PreviewFuseProcessor(mRS, mPreviewSize);
        Surface previewFuseSurface = mPreviewFuseProcessor.getInputSurface();


        //set up the VideoRecorder for the correct size and orientation of captured frames
        mVideoRecorder = new VideoRecorder(mAssociatedActivity, rotation, mRecordSize);
        Surface recorderSurface = mVideoRecorder.getRecorderSurface();

        //connect output of the fuse script to the preview texture
        mPreviewFuseProcessor.setOutputSurface(previewSurface);

        mConsumerSurfaces.set(0,previewFuseSurface);
        mConsumerSurfaces.set(1,recorderSurface);
        //mConsumerSurfaces.set(2,meteringSurface);FIXME
    }

    public void startUnderexposeCapture(){
        mCameraState = CameraState.MODE_UNDEREXPOSE;
        mCaptureSession = new UnderExpCaptureSession(HdrCamera.this,
                mConsumerSurfaces,
                mExposureMeter,
                mCameraHandler);
    }

    public void startOverexposeCapture(){
        mCameraState = CameraState.MODE_OVEREXPOSE;
        mCaptureSession = new OverExpCaptureSession(HdrCamera.this,
                mConsumerSurfaces,
                mExposureMeter,
                mCameraHandler);
    }

    public void startFuseCapture(){
        mCameraState = CameraState.MODE_FUSE;
        mCaptureSession = new AlternatingCaptureSession(HdrCamera.this,
                mConsumerSurfaces,
                mExposureMeter,
                mCameraHandler);

    }

    public void adjustUnderExposureManually(double factor){
        mExposureMeter.adjustUnderexposure(factor);
    }

    public void adjustOverexposureManually(double factor){
        mExposureMeter.adjustOverexposure(factor);
    }

    /* GETTER & SETTER METHODS */

    public CameraDevice getCameraDevice(){
        return mCameraDevice;
    }

    public CameraState getCameraState(){
        return mCameraState;
    }

    /* RECORDER OPERATION METHODS */
    public void startRecording() throws IllegalStateException {
        Log.d(TAG, "trying to start recording");
        mRecorderHandler.post(new Runnable() {
            @Override
            public void run() {
                mVideoRecorder.start();
            }
        });
        mCameraState = CameraState.MODE_RECORD;
    }

    public void stopRecording() throws IllegalStateException {
        Log.d(TAG, "trying to stop recording");
        mRecorderHandler.post(new Runnable() {
            @Override
            public void run() {
                mVideoRecorder.stop();
            }
        });
        /* preview needs to be restarted because stopping the videorecorder will put the
        * used MediaRecorder into the Initialize state, which means no output surface is available.
        * This also means the whole camera surface connection is reset and needs to be built again.
        * essentially we need to do the whole thing that is done when opening the camera*/
        //TODO restart preview in a better way
        mCaptureSession.close(); //stop camera outputs
        mPreviewFuseProcessor.stop(); //no longer fuse
        //mExposureMeter.destroyHistogramProcessor(); FIXME

        mCameraState = CameraState.MODE_FUSE;

        setupSurfaces(); //reconnect surfaces

        //restart session
        mCaptureSession = new AlternatingCaptureSession(HdrCamera.this,
                mConsumerSurfaces,
                mExposureMeter,
                mCameraHandler);


    }

    public void cleanup(){
        /* any resource cleanup? */
        mExposureMeter.finish();
    }



    /* HELPER METHODS */

    private boolean hasCapability(int[] capabilities, int capability) {
        for (int c : capabilities) {
            if (c == capability) return true;
        }
        return false;
    }

    public interface ConfigurePreviewListener{
        void onOrientationChanged(Size prevSize, int width, int height);
    }
}
