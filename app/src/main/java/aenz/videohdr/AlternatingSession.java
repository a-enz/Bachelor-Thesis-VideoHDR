package aenz.videohdr;

import android.hardware.camera2.*;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Abstracting a Session with repeatingBurst containing alternating exposure shots as
 * an AlternatingSession
 *
 * A AlternatingSession should be created for a specific device, some consumer surfaces and
 * a Thread handling the operation.
 *
 * Later on only parameters for frame exposure should be changed (like iso, exposure time)
 * Created by andi on 14.08.2015.
 */
public class AlternatingSession {
    private static final String TAG = "AlternatingSession";

    //timing constants
    private static final long MICRO_SECOND = 1000;
    private static final long MILLI_SECOND = MICRO_SECOND * 1000;
    private static final long ONE_SECOND = MILLI_SECOND * 1000;

    public static final long FRAME_DURATION = ONE_SECOND / 30; //has to be accessible from exposure metering

    //initial exposure time and iso
    private static final long INITIAL_ODD_EXPOSURE = ONE_SECOND / 33;
    private static final int INITIAL_ODD_ISO = 120;
    private static final long INITIAL_EVEN_EXPOSURE = ONE_SECOND / 33;
    private static final int INITIAL_EVEN_ISO = 120;

    /*The associated CameraCaptureSession. Should not change, or else we need to create
    a new AlternatingSession as well */
    private CameraCaptureSession mCaptureSession;

    /*builder for the camera device we are using for the alternating session preview
      or the record request, depending on how the AlternatingSession was created (isRecording)
     */
    private CaptureRequest.Builder mRequestBuilder;

    //list of the double exposure capture requests
    private List<CaptureRequest> mDoubleExposure = Arrays.asList(null,null);

    //consumer surfaces of this alternating session
    private List<Surface> mConsumerSurfaces;

    //handler to background thread to process camera operations on
    private Handler mCameraHandler;

    //associated hardware camera device
    private final HdrCamera mCamera;


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
                    //TODO maybe more settings here are needed for the whole session
                    mRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);


                    /* after configuration start of with some initial values */
                    //TODO would probably be better to remember values from previous open camera
                    /* since the brightness and such should not have changed that much?*/
                    setAlternatingCapture(INITIAL_EVEN_ISO,
                            INITIAL_ODD_ISO,
                            INITIAL_EVEN_EXPOSURE,
                            INITIAL_ODD_EXPOSURE);

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
     * CaptureCallback. What happens with captured results. Only the Meta Info of sensor capture contained
     * in CaptureResult objects is accessible.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
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
                    //TODO here we could access meta information of the frames of TotalCaptureResult
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
     * @param cameraHandler background camera thread to handle the requests
     */
    public AlternatingSession(HdrCamera device,
                              List<Surface> consumers,
                              Handler cameraHandler) {

        mCamera = device;
        mConsumerSurfaces = consumers;
        mCameraHandler = cameraHandler;



        createSessionAndCaptureBuilder(); //TODO handle if (creation failed) returns false
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
            /*TODO maybe we should separate this part below from createSessionAndCaptureBuilder and make two different methods*/
            /*not quite sure if it is a good idea to separate requests for preview only and record */

                mRequestBuilder = mCamera.getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureRequest");
            e.printStackTrace();
        }

        return (mCaptureSession == null || mRequestBuilder == null);
    }

    /*for now only for the previewBuilder */
    /**
     * Create and execute (enqueue) a capture request. These are the only request settings the should
     * be modified during a session
     * @param evenIso value for the even frame: between 80 and 1200?
     * @param oddIso value for the odd frame: between 80 and 1200?
     * @param mEvenExposure value in milliseconds. should not be larger than FRAME_DURATION
     * @param mOddExposure value in milliseconds. should not be larger than FRAME_DURATION //TODO where to check for that. probably in Exposure Metering
     */
    public void setAlternatingCapture(int evenIso, int oddIso,
                                      long mEvenExposure, long mOddExposure){


        mRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_DURATION);

        //evenFrame -> should be the short exposure (darker frame)
        mRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, evenIso);
        mRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mEvenExposure);
        //mPreviewBuilder.setTag(mEvenExposureTag);
        mDoubleExposure.set(0, mRequestBuilder.build());


        //oddFrame -> should be the longer exposure (brighter frame)
        mRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, oddIso);
        mRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mOddExposure);
        //mHdrBuilder.setTag(mOddExposureTag);
        mDoubleExposure.set(1, mRequestBuilder.build());

        try {
            mCaptureSession.setRepeatingBurst(mDoubleExposure, mCaptureCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED setRepeatingBurst");
            e.printStackTrace();
        }
    }
}