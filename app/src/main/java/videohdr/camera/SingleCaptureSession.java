package videohdr.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.List;

/**
 * Created by andi on 12.09.2015.
 */
public class SingleCaptureSession extends SimpleCaptureSession{
    private static final String TAG = "SingleCapSess";


    //list of the double exposure capture requests
    private CaptureRequest mSingleExposure;

    /**
     * Creator of this class.
     * @param device hardware device we want to execute the capture requests on
     * @param consumers consumer surfaces of captured requests
     * @param cameraHandler background camera thread to handle the requests
     */
    public SingleCaptureSession(HdrCamera device,
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
    protected void setCaptureParameters(ExposureMeter.MeteringValues param){
        int iso;
        long exposureDuration;
        switch(mCamera.getCameraState()){
            case MODE_OVEREXPOSE: { //overexposing happens in the odd camera Frame
                iso = param.getOverexposeIso();
                exposureDuration = param.getOverexposeDuration();
                break;
            }
            case MODE_UNDEREXPOSE: { //underexposing happens in the even camera frame
                iso = param.getUnderexposeIso();
                exposureDuration = param.getUnderexposeDuration();
                break;
            }
            default: {
                Log.e(TAG, "capturing single exposure should not happen in this camera mode");
                return;
            }
        }

        //evenFrame -> should be the short exposure (darker frame)
        mRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        mRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureDuration);
        mSingleExposure = mRequestBuilder.build();

        try {
            mCaptureSession.setRepeatingRequest(mSingleExposure, mCaptureCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED setRepeatingBurst");
            e.printStackTrace();
        }
    }
}
