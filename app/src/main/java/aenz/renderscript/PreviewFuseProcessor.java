package aenz.renderscript;

import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

/**
 * Mostly copied from HdrViewfinders ViewfinderProcessor class
 * Combined with a renderscript we fuse a double exposure into a single frame
 * Created by andi on 13.07.2015.
 */
public class PreviewFuseProcessor {

    private static final String TAG = "PreviewFuseProcessor";


    private Allocation mInputAllocation;
    private Allocation mPrevAllocation;
    private Allocation mOutputAllocation;

    private HandlerThread mProcessingThread;
    private Handler mProcessingHandler;
    private ScriptC_preview_fuse mFuseScript;

    public ProcessingTask mFuseTask;


    public PreviewFuseProcessor(RenderScript rs, Size previewSize) {
        int width = previewSize.getWidth();
        int height = previewSize.getHeight();

        Type.Builder yuvTypeBuilder = new Type.Builder(rs, Element.YUV(rs));
        yuvTypeBuilder.setX(width);
        yuvTypeBuilder.setY(height);
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);
        mInputAllocation = Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);


        Type.Builder rgbTypeBuilder = new Type.Builder(rs, Element.RGBA_8888(rs));
        rgbTypeBuilder.setX(width);
        rgbTypeBuilder.setY(height);
        mPrevAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        mProcessingThread = new HandlerThread("PreviewFuseProcessor");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());

        mFuseScript = new ScriptC_preview_fuse(rs);

        mFuseScript.set_gPrevFrame(mPrevAllocation);

        mFuseTask = new ProcessingTask(mInputAllocation);
    }

    public Surface getInputSurface() {
        return mInputAllocation.getSurface();
    }

    public void setOutputSurface(Surface output) {
        mOutputAllocation.setSurface(output);
    }


    /**
     * Simple class to keep track of incoming frame count,
     * and to process the newest one in the processing thread
     */
    class ProcessingTask implements Runnable, Allocation.OnBufferAvailableListener {
        private int mPendingFrames = 0;
        private int mFrameCounter = 0;

        private Allocation mInputAllocation;

        public ProcessingTask(Allocation input) {
            mInputAllocation = input;
            mInputAllocation.setOnBufferAvailableListener(this);

        }

        @Override
        public void onBufferAvailable(Allocation a) {
            synchronized(this) {
                mPendingFrames++;
                mProcessingHandler.post(this);
            }
        }

        @Override
        public void run() {

            // Find out how many frames have arrived
            int pendingFrames;
            synchronized(this) {
                pendingFrames = mPendingFrames;
                mPendingFrames = 0;

                // Discard extra messages in case processing is slower than frame rate
                //TODO this should be avoided because it may mess up parity of dark/bright frames
                mProcessingHandler.removeCallbacks(this);
            }

            // Get to newest input
            for (int i = 0; i < pendingFrames; i++) {
                mInputAllocation.ioReceive();
            }

            mFuseScript.set_gFrameCounter(mFrameCounter++);
            mFuseScript.set_gCurrentFrame(mInputAllocation);

            if(mFrameCounter % 15 == 0) Log.d(TAG, "fusing frames");

            // Run processing pass
            mFuseScript.forEach_fuseFrames(mPrevAllocation, mOutputAllocation);
            mOutputAllocation.ioSend();
        }
    }

}
