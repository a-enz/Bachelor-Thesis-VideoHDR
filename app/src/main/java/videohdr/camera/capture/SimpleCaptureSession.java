package videohdr.camera.capture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.List;

import videohdr.camera.ExposureMeter;
import videohdr.camera.HdrCamera;

/**
 * This class was created to abstract and simplify the android API for this applications
 * purposes as much as possible. Setting capture values in the new camera2 API of android can
 * be quite a lengthy and cumbersome process, which I tried to wrap in this class.
 * Other classes only have to deal with with ISO and exposure time values for the camera
 * settings
 * Created by Andreas Enz on 12.09.2015.
 */

public abstract class SimpleCaptureSession implements ExposureMeter.EventListener {
    private static final String TAG = "SimpleCapSess";


    /*The associated CameraCaptureSession. Should not change, or else we need to create
    a new AlternatingCaptureSession as well */
    protected CameraCaptureSession mCaptureSession;

    /*builder for the camera device we are using for the alternating session preview*/
    protected CaptureRequest.Builder mRequestBuilder;

    //consumer surfaces of this alternating session
    protected List<Surface> mConsumerSurfaces;

    //handler to background thread to process camera operations on
    protected Handler mCameraHandler;

    //associated hardware camera device
    private final HdrCamera mCamera;

    private final ExposureMeter mExposureMeter;


    /**
     * State Callback for the Capture Session
     */
    private final CameraCaptureSession.StateCallback mStateCallback =
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    /* we assume this is only called when camera is opened */
                    mCaptureSession = session;
                    //add consumer surfaces to builder
                    for(Surface surface : mConsumerSurfaces){
                        mRequestBuilder.addTarget(surface);
                    }

                    //set auto exposure mode to off, otherwise we can't do manual double exposure
                    mRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    mRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, ExposureMeter.FRAME_DURATION);
                    mRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

                    ExposureMeter.MeteringParam param = mExposureMeter.getMeteringValues();
                    mExposureMeter.setMeteringEventListener(SimpleCaptureSession.this);

                    setCaptureParameters(param);

                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    /* this is called if the surfaces contain unsupported sizes (as defined by
                     StreamconfigurationMap.getoutputSizes(SurfaceHolder.class)) or too many target surfaces
                     are provided.
                     */

                    Log.d(TAG, "onConfigureFailed");
                    mCamera.closeCamera();


                }
            };


    /**
     * Here meta information of the completed frames can be accessed, not used for now
     */
    protected final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                /*
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
                    super.onCaptureProgressed(session, request, partialResult);
                }
                */
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                }

                /*
                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                }

                @Override
                public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId, long frameNumber) {
                    super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
                }

                @Override
                public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
                    super.onCaptureSequenceAborted(session, sequenceId);
                }
                */
            };

    /**
     * Creator of this class.
     * @param device hardware device we want to execute the capture requests on
     * @param consumers consumer surfaces of captured requests
     * @param meter changes to ISO and exposure time are give through this object
     * @param cameraHandler background camera thread to handle the requests
     */
    public SimpleCaptureSession(HdrCamera device,
                                     List<Surface> consumers,
                                     ExposureMeter meter,
                                     Handler cameraHandler) {

        mCamera = device;
        mConsumerSurfaces = consumers;
        mExposureMeter = meter;
        mCameraHandler = cameraHandler;


        createSessionAndCaptureBuilder();
    }

    /**
     * initialize CameraCaptureSession and CaptureRequest.Builder objects for this session
     * @return success
     */
    private boolean createSessionAndCaptureBuilder(){

        try {
            mCamera.getCameraDevice().createCaptureSession(mConsumerSurfaces, mStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureSession");
            e.printStackTrace();
            mCamera.closeCamera();
        }


        try {
            mRequestBuilder = mCamera.getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureRequest");
            e.printStackTrace();
        }

        //return false if the initialization failed
        return !(mCaptureSession == null || mRequestBuilder == null);
    }

    /*for now only for the previewBuilder */
    /**
     * Create and execute (enqueue) a capture request.
     */
    protected abstract void setCaptureParameters(ExposureMeter.MeteringParam param);

    public void close(){
        mCaptureSession.close();
    }

    @Override
    public void onMeterEvent(ExposureMeter.MeteringParam param) {
        setCaptureParameters(param);
    }
}
