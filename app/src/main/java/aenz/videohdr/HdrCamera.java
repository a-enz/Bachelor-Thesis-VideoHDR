package aenz.videohdr;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
    private ArrayList<Surface> mConsumerSurfaces;




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

        createWithCapabilities();

        mCameraThread = new HandlerThread("CameraOpsThread");
        mCameraThread.start();
    }



    /* CAMERA STATE & RESOURCES METHODS */


    private final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
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
    public void openCamera(){
        mCameraHandler = new Handler(mCameraThread.getLooper());

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

    }

    public void closeCamera(){
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCameraDevice != null) {
                    mCameraDevice.close();
                }
                mCameraDevice = null;
                mCameraSession = null;
                mConsumerSurfaces = null;
            }
        });
    }



    /* CAMERA OPERATION METHODS */

    /* configure preview size depending on possible texture sizes provided by the camera hardware
    * found in CameraCharacteristics
    * */
    public void configurePreview(AutoFitTextureView textureView, int width , int height){

        //get possible sizes for use with SurfaceTextures
        Size previewSize = VideoSizeConfiguration.choosePreviewSize(
                mCameraCharacteristics,
                width,
                height);

        Log.d(TAG, "PreviewSize chosen is: " + previewSize);

        int orientation = mAssociatedActivity.getResources().getConfiguration().orientation;

        //setAspectRation should fire onSurfaceTextureSizeChanged
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }
    }

    public void activate(){

        /*
            TODO create AlternatingSession (maybe already earlier) and start capture
            by some preset values.
         */

        //TODO somewhere should the results from the HistogramEvaluation feed back into AlternatingSession.setAlternatingCapture
    }


    /* HELPER METHODS */


    private boolean hasCapability(int[] capabilities, int capability) {
        for (int c : capabilities) {
            if (c == capability) return true;
        }
        return false;
    }
}
