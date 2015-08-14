package aenz.videohdr;

import android.app.Activity;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

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

    /*The associated CameraCaptureSession. Should not change, or else we need to create
    a new AlternatingSession as well */
    private CameraCaptureSession mCaptureSession;
    private Handler mCameraHandler;

    /* state callback for camera events */
    private CameraCaptureSession.StateCallback mStateCallback =
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    mCameraHandler.post(new Runnable() {
                        public void run() {
                            //TODO let stuff run
                        }
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigureFailed");
                }
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

        try {
            device.createCaptureSession(consumers, mStateCallback , cameraHandler);
        } catch (CameraAccessException e) {
            Log.d(TAG, "FAILED createCaptureSession in AlternatingSession");
            e.printStackTrace();
            device.close(); //TODO is this realy necessairy here? although something did go horribly wrong
        }
    }
}
