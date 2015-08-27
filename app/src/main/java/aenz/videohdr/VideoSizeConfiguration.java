package aenz.videohdr;

/**
 * Helper Class to easily modify Preview and Recording parameters like size (aspect ratio)
 * depending on the camera hardware and the (end-)consumer surfaces:
 * -MediaRecorder
 * -SurfaceTexture
 */

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by andi on 14.07.2015.
 *
 * Should be used by the Camera and MediaRecorder to configure:
 * - Preview Size
 * - Video Size
 */
public final class VideoSizeConfiguration {

    private static final String TAG = "VideoSizeConfiguration";

    private static final float ASPECT_RATIO = 3.f / 4.f;
    private static final int MAX_RECORDING_WIDTH = 1080;

    private static final Class<SurfaceTexture> PREVIEW_CLASS = SurfaceTexture.class;

    private static final Class<MediaRecorder> RECORDER_CLASS = MediaRecorder.class;



    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     */

    public static Size choosePreviewSize(CameraCharacteristics characteristics,
                                         int width,
                                         int height){

        width /= 2;
        height /= 2;
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        Size[] choices = map.getOutputSizes(PREVIEW_CLASS);


        // Collect the supported resolutions that are at least as big as half the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * ASPECT_RATIO &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }


        /*
        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
        */
        return choices[17];
    }

    /**
     * Choose largest Size according to ASPECT_RATION and MAX_WIDTH
     * @param characteristics
     * @return
     */

    public static Size chooseVideoSize(CameraCharacteristics characteristics){
        return null;
        //TODO recorded video size configuration
    }



    /* UTIL */

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }
}
