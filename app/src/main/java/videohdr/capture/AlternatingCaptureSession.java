package videohdr.capture;

import android.hardware.camera2.*;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import java.util.Arrays;
import java.util.List;

import videohdr.camera.ExposureMeter;
import videohdr.camera.HdrCamera;

/**
 * Abstracting a Session with repeatingBurst containing alternating exposure shots as
 * an AlternatingCaptureSession
 *
 * A AlternatingCaptureSession should be created for a specific device, some consumer surfaces and
 * a Thread handling the operation.
 *
 * Later on only parameters for frame exposure should be changed (like iso, exposure time)
 * Created by andi on 14.08.2015.
 */
public class AlternatingCaptureSession extends SimpleCaptureSession {
    private static final String TAG = "AlternatingCapSess";

    //list of the double exposure capture requests
    private List<CaptureRequest> mDoubleExposure = Arrays.asList(null,null);

    /**
     * Creator of this class.
     * @param device hardware device we want to execute the capture requests on
     * @param consumers consumer surfaces of captured requests
     * @param cameraHandler background camera thread to handle the requests
     */
    public AlternatingCaptureSession(HdrCamera device,
                                     List<Surface> consumers,
                                     ExposureMeter meter,
                                     Handler cameraHandler) {

        super(device, consumers, meter, cameraHandler);
    }

    /*for now only for the previewBuilder */
    /**
     * Create and execute (enqueue) a capture request. These are the only request settings the should
     * be modified during a session
     */
    protected void setCaptureParameters(ExposureMeter.MeteringParam param){

        int evenIso = param.getUnderexposeIso();
        int oddIso = param.getOverexposeIso();
        long mEvenExposure = param.getUnderexposeDuration();
        long mOddExposure = param.getOverexposeDuration();

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
