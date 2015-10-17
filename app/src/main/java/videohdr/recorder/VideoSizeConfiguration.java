package videohdr.recorder;

/**
 * Helper Class to easily modify Preview and Recording parameters like size (aspect ratio)
 * depending on the camera hardware and the (end-)consumer surfaces:
 * -MediaRecorder
 * -SurfaceTexture
 */


import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.renderscript.Allocation;
import android.util.Log;
import android.util.Size;
import java.util.Comparator;

/**
 * Created by andi on 14.07.2015.
 *
 * Should be used by the Camera and MediaRecorder to configure:
 * - Preview Size
 * - Video Size
 */
public class VideoSizeConfiguration {

    private static final String TAG = "VideoSizeConfiguration";

    private static final float ASPECT_RATIO = 3.f / 4.f;
    private static final float ASPECT_RATIO_RECORD = 9.f / 16.f;
    private static final int MAX_RECORDING_WIDTH = 2500;
    private static final int MAX_PREVIEW_WIDTH = 1000;
    private static final int MAX_METERING_WIDTH = 400;

    private static final Class<Allocation> RENDERSCRIPT_CLASS = Allocation.class;

    private static final Class<MediaRecorder> RECORDER_CLASS = MediaRecorder.class;



    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     */

    public static Size choosePreviewSize(StreamConfigurationMap map){

        Size[] choices = map.getOutputSizes(RENDERSCRIPT_CLASS);

        for (Size size : choices) {
            if (size.getHeight() == size.getWidth() * ASPECT_RATIO && size.getWidth() <= MAX_PREVIEW_WIDTH) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable preview size");
        return choices[choices.length - 1];
    }

    /**
     * Choose largest Size according to ASPECT_RATION and MAX_WIDTH
     * @return
     */

    public static Size chooseVideoSize(StreamConfigurationMap map){

        Size[] choices = map.getOutputSizes(RECORDER_CLASS);

        for (Size size : choices) {
            if (size.getHeight() == size.getWidth() * ASPECT_RATIO_RECORD && size.getWidth() <= MAX_RECORDING_WIDTH) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable record size");
        return choices[choices.length - 1];
    }

    public static Size chooseMeteringSize(StreamConfigurationMap map){

        Size[] choices = map.getOutputSizes(RENDERSCRIPT_CLASS);

        for (Size size : choices) {
            if (size.getHeight() == size.getWidth() * ASPECT_RATIO && size.getWidth() <= MAX_METERING_WIDTH) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable metering size. \n Returned value: " + choices[choices.length - 1]);
        return choices[choices.length - 1];
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
