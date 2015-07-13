package aenz.videohdr;

import android.media.MediaRecorder;

/**
 * Created by andi on 13.07.2015.
 *
 * For now this is implemented with {@link MediaRecorder} for simplicity.
 * Later on I might use {@link android.media.MediaCodec} for more in depth control over
 * the video frames.
 *
 * This class takes care of storing the file and generating metadata of the frames
 */
public class VideoSaver {
    private static final String TAG = "VideoSaver";
}
