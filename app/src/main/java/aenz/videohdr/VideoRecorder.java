package aenz.videohdr;

import android.app.Activity;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by andi on 13.07.2015.
 *
 * Wrapper Class for whatever is used to capture frames
 * For now this is implemented with {@link MediaRecorder} for simplicity.Usage of this
 * wrapper class is the same as for MediaRecorder detailed on Android API
 * Later on I might use {@link android.media.MediaCodec} for more in depth control over
 * the video frames.
 *
 * This class takes care of storing the file (and generating metadata of the frames)
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    /* Helper stuff for the screen rotations */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private MediaRecorder mRecorder; //one recorder per videoRecorder

    /* File storage and naming, timestamp added later on */
    private static final File directoryPath = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM + "/Camera/"); //TODO after storing rename with timestamp or something
    private static final File tempVideoFile = new File(directoryPath,"tmp.mp4");

    public VideoRecorder(int rotation, int width, int height){

        mRecorder = new MediaRecorder();
        try {
            setupRecorder(rotation, width, height);
        } catch (IOException e) {
            Log.d(TAG, "MediaRecorder setup failed");
            e.printStackTrace();
        }
    }


    /**
     * Set up MediaRecorder with predefined Values
     * @param rotation screen rotation
     * @param width
     * @param height
     * @throws IOException
     */
    private void setupRecorder(int rotation, int width, int height) throws IOException {

        mRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setOutputFile(tempVideoFile.getAbsolutePath());
        Log.d(TAG, "outputfile set to: " +tempVideoFile);
        mRecorder.setVideoEncodingBitRate(10000000);
        mRecorder.setVideoFrameRate(30);
        mRecorder.setVideoSize(width, height);
        mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        int orientation = ORIENTATIONS.get(rotation);
        mRecorder.setOrientationHint(orientation);
        mRecorder.prepare();
    }

    public Surface getRecorderSurface(){
        return mRecorder.getSurface();
    }

    public void start(){
        try {
            mRecorder.start();
        }catch(IllegalStateException e){
            Log.d(TAG, "starting the MediaRecorder failed");
            e.printStackTrace();
        }
    }

    public String stop(){
        try {
            mRecorder.stop();
            mRecorder.reset();
        }catch (IllegalStateException e){
            Log.d(TAG, "stopping the MediaRecorder failed");
            e.printStackTrace();
        }

        //rename file with timestamp
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmSSS").format(new Date());
        String filePath = directoryPath + "VID_" + timeStamp + ".mp4";
        tempVideoFile.renameTo(new File(filePath));
        Log.d(TAG, "Output video filepath correct? " + filePath);

        return filePath;
    }

    public void release(){
        mRecorder.release();
        mRecorder = null;
    }
}
