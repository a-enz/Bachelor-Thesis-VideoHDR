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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import aenz.renderscript.PreviewFuser;

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

    /* Consumer Surfaces of this camera */
    /* Will probably contain the MediaRecorder surface and several Renderscripts
       (Histogram, Preview generation)
     */
    private List<Surface> mConsumerSurfaces;// = Arrays.asList(null); //TODO maybe instantiate better elsewhere

    /* Object handling the Video recording of this camera */
    private VideoRecorder mVideoRecorder; //TODO reactive and initialize with proper video sizes (StreamconfigurationMatp)

    //Renderscript object used for two scripts: preview fusion and histogram
    private RenderScript mRS;




    /**
     * Creator Method of this class. Instantiate the Camera with desired capabilites like
     * back facing, a sufficient hardware support level, a.s.o
     * A Thread to handle camera operation in the background is also created.
     *
     * @param activity associated activity
     */

    public HdrCamera(Activity activity){
        //instantiate with the correct camera ID, everything else is done by callback

        mAssociatedActivity = activity;
        mManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        mRS = RenderScript.create(activity);

        createWithCapabilities();

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
            * aCaptureSession a Callback (onConfigured) will directly invoke that method //TODO initial values?
            * for now we just assume that it is best to reset the values every time the camera is closed and then
            * opened again*/

            //TODO later on this will probably be a global object since we need to access setter method from histogram/exposure metering
            AlternatingSession aCaptureSession = new AlternatingSession(HdrCamera.this,
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
    public void openCamera(AutoFitTextureView textureView){

        //TODO configure also size of output video. preview depends on it?
        //first configure the preview texture
        Size previewSize = configurePreview(textureView);

        //TODO wrong width/height given to videoRecorder and previewsurface
        //set up the VideoRecorder for the correct size and orientation of captured frames
        int rotation = mAssociatedActivity.getWindowManager().getDefaultDisplay().getRotation();
        int width = textureView.getWidth();
        int height = textureView.getHeight(); //TODO sizes received through VideoSizeCOnfiguration.chooseVideoSize
        //mVideoRecorder = new VideoRecorder(rotation, width, height);

        //set consumer surfaces
        setupSurfaces(textureView, previewSize);

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
                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
                /*
                if(mVideoRecorder != null) {
                    mVideoRecorder.release();
                    mVideoRecorder = null;
                }*/
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

        //setAspectRation should fire onSurfaceTextureSizeChanged so no need to call it explicitly after this
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        return previewSize;
    }

    /**
     * Set up the consumer surfaces of the camera, should not have more than 4
     * Also configure the output of the preview-fuser to the preview surface
     *
     * @param previewTextureView the preview texture view for this open camera
     */
    private void setupSurfaces(AutoFitTextureView previewTextureView, Size previewSize){
        int width = previewSize.getWidth();
        int height = previewSize.getHeight();

        //preview surface (texture view)
        SurfaceTexture texture = previewTextureView.getSurfaceTexture();
        assert texture!=null;
        texture.setDefaultBufferSize(width, height);
        //TODO does previewTextureVIew have the correct sizes? it should have since it was configured previously in configurePreview()
        Surface previewSurface = new Surface(texture); //create surface for the textureView
        // TODO release previewSurface: in closeCamera()?

        /*consumer surfaces (rs fuse, rs histogram, recorder)*/
        //Recorder Surface
        //Surface recorderSurface = mVideoRecorder.getRecorderSurface();

        //PreviewFuser surface
        PreviewFuser previewFuser = new PreviewFuser(mRS,width, height);
        Surface previewFuserSurface = previewFuser.getInputSurface();

        //TODO Histogram surface

        /* connect surfaces to camera output and preview to RS output */
        //connect fuser output to app preview
        previewFuser.setOutputSurface(previewSurface);

        //add direct consumer surfaces of camera device
        /* TODO maybe the consumer surfaces should differ between 'just preview' and 'preview & recording'*/
        //TODO are the sizes supported by
        mConsumerSurfaces = Arrays.asList(previewFuserSurface);
        //mConsumerSurfaces.set(1, recorderSurface);
        //TODO update mConsumerSurfaces with a third object to make space for renderscript
    }

    /* GETTER & SETTER METHODS */

    public CameraDevice getCameraDevice(){
        return mCameraDevice;
    }

    /* RECORDER OPERATION METHODS */
    public void startRecording(){
        mVideoRecorder.start();
    }

    public void stopRecording(){
        mVideoRecorder.stop();
    }




    /* HELPER METHODS */

    private boolean hasCapability(int[] capabilities, int capability) {
        for (int c : capabilities) {
            if (c == capability) return true;
        }
        return false;
    }
}
