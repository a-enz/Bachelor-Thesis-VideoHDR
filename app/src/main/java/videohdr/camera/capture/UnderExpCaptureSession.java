package videohdr.camera.capture;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.List;

import videohdr.camera.ExposureMeter;
import videohdr.camera.HdrCamera;

/**
 * Class to influence capture settings while the camera is in the
 * Underexposed mode
 * Created by Andreas Enz on 12.09.2015.
 */
public class UnderExpCaptureSession extends SimpleCaptureSession {
    private static final String TAG = "OECapSess";


    //list of the double exposure capture requests

    /**
     * Creator of this class.
     * @param device hardware device we want to execute the capture requests on
     * @param consumers consumer surfaces of captured requests
     * @param cameraHandler background camera thread to handle the requests
     */
    public UnderExpCaptureSession(HdrCamera device,
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
        int iso = param.getUnderexposeIso();
        long exposureDuration = param.getUnderexposeDuration();

        //evenFrame -> should be the short exposure (darker frame)
        mRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        mRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureDuration);
        CaptureRequest mSingleExposure = mRequestBuilder.build();

        try {
            //set a repeating request (single request, repeated until a new request is provided)
            mCaptureSession.setRepeatingRequest(mSingleExposure, mCaptureCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED setRepeating");
            e.printStackTrace();
        }
    }
}
