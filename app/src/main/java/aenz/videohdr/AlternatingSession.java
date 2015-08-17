package aenz.videohdr;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
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

    /*The associated CameraCaptureSession. Should not change, or else we need to create
    a new AlternatingSession as well */
    private CameraCaptureSession mCaptureSession;

    //builder for the camera device we are using for the alternating session preview
    private CaptureRequest.Builder mPreviewBuilder;

    //builder used for requests during actual recording
    private CaptureRequest.Builder mRecordBuilder;

    //list of the double exposure capture requests
    private List<CaptureRequest> mDoubleExposure = new ArrayList<>(2);

    //consumer surfaces of this alternating session
    private List<Surface> mConsumerSurfaces;

    //handler to background thread to process camera operations on
    private Handler mCameraHandler;

    //associated hardware camera device
    private final CameraDevice mCamera;


    /**
     * State Callback for the Capture Session
     */
    private final CameraCaptureSession.StateCallback mStateCallback =
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    mCameraHandler.post(new Runnable() {
                        public void run() {
                            //TODO let stuff run which means calling setAlternatingRequest with maybe some preset values
                        }
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigureFailed");
                    //TODO probably should close the camera and end session properly
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
    public AlternatingSession(CameraDevice device,
                              List<Surface> consumers,
                              Handler cameraHandler) {

        mCamera = device;
        mConsumerSurfaces = consumers;
        mCameraHandler = cameraHandler;

        mDoubleExposure.add(null);
        mDoubleExposure.add(null); //TODO possible to do this nicer? maybe something like {null, null}

        createSessionAndCaptureBuilder(); //TODO handle if (creation failed) returns false
    }

    /**
     * initialize CameraCaptureSession and CaptureRequest.Builder objects for this session
     * @return success
     */
    private boolean createSessionAndCaptureBuilder(){

        try {
            mCamera.createCaptureSession(mConsumerSurfaces, mStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureSession");
            e.printStackTrace();
            mCamera.close(); //TODO is this really necessary here? although something did go horribly wrong
        }


        try {
            /*TODO maybe we should separate this part below from createSessionAndCaptureBuilder and make two different methods*/
            mPreviewBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mRecordBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureRequest");
            e.printStackTrace();
        }

        if (mCaptureSession == null || mPreviewBuilder == null || mRecordBuilder == null)
            return false;

        //TODO previewBuilder and RecordBuilder have different set of target surfaces
        /* PreviewBuilder: should have several Renderscript target surfaces
        * RecordBuilder: same as PreviewBuilder with additional MediaRecorder surface */
        for(Surface surface : mConsumerSurfaces){
            mPreviewBuilder.addTarget(surface);
            mRecordBuilder.addTarget(surface);
        }

        return true;
    }

    /*for now only for the previewBuilder */
    /**
     * Create and execute (enqueue) a capture request
     * @param evenIso value between 80 and 1200?
     * @param oddIso value between 80 and 1200?
     * @param mEvenExposure value in milliseconds. should not be larger than FRAME_DURATION
     * @param mOddExposure value in milliseconds. should not be larger than FRAME_DURATION
     */
    public void setAlternatingCapture(int evenIso, int oddIso,
                                      long mEvenExposure, long mOddExposure){
        //TODO handle standard configuration stuff
        //TODO handle exposure times, iso


        mPreviewBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, ONE_SECOND / 30); ///TODO this is fixed

        //evenFrame -> should be the short exposure (darker frame)
        mPreviewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, evenIso);
        mPreviewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mEvenExposure);
        //mPreviewBuilder.setTag(mEvenExposureTag);
        mDoubleExposure.set(0, mPreviewBuilder.build());


        //oddFrame -> should be the longer exposure (brighter frame)
        mPreviewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, oddIso);
        mPreviewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mOddExposure);
        //mHdrBuilder.setTag(mOddExposureTag);
        mDoubleExposure.set(1, mPreviewBuilder.build());

        try {
            mCaptureSession.setRepeatingBurst(mDoubleExposure, mCaptureCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED setRepeatingBurst");
            e.printStackTrace();
        }
    }
}
