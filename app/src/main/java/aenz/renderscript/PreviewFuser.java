package aenz.renderscript;

import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Size;
import android.view.Surface;

/**
 * Created by andi on 13.07.2015.
 */
public class PreviewFuser {

    private static final String TAG = "PreviewFuser";


    private Allocation mInputHdrAllocation;
    private Allocation mInputNormalAllocation;
    private Allocation mPrevAllocation;
    private Allocation mOutputAllocation;

    private Surface mOutputSurface;
    private HandlerThread mProcessingThread;
    private Handler mProcessingHandler;
    private ScriptC_preview_fuser mHdrMergeScript;

    public ProcessingTask mHdrTask;
    public ProcessingTask mNormalTask;

    private Size mSize;

    private int mMode;

    //public final static int MODE_NORMAL = 0;
    public final static int MODE_SIDE_BY_SIDE = 0;
    public final static int MODE_HDR = 1;

    public PreviewFuser(RenderScript rs, Size dimensions) {
        mSize = dimensions;

        Type.Builder yuvTypeBuilder = new Type.Builder(rs, Element.YUV(rs));
        yuvTypeBuilder.setX(dimensions.getWidth());
        yuvTypeBuilder.setY(dimensions.getHeight());
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);
        mInputHdrAllocation = Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);
        mInputNormalAllocation = Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);

        Type.Builder rgbTypeBuilder = new Type.Builder(rs, Element.RGBA_8888(rs));
        rgbTypeBuilder.setX(dimensions.getWidth());
        rgbTypeBuilder.setY(dimensions.getHeight());
        mPrevAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        mProcessingThread = new HandlerThread("ViewfinderProcessor");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());

        mHdrMergeScript = new ScriptC_preview_fuser(rs);

        mHdrMergeScript.set_gPrevFrame(mPrevAllocation);

        mHdrTask = new ProcessingTask(mInputHdrAllocation, dimensions.getWidth()/2, true);
        mNormalTask = new ProcessingTask(mInputNormalAllocation, 0, false);

        setRenderMode(MODE_HDR);
    }

    public Surface getInputHdrSurface() {
        return mInputHdrAllocation.getSurface();
    }

    public Surface getInputNormalSurface() {
        return mInputNormalAllocation.getSurface();
    }

    public void setOutputSurface(Surface output) {
        mOutputAllocation.setSurface(output);
    }

    public void setRenderMode(int mode) {
        mMode = mode;
    }

    /**
     * Simple class to keep track of incoming frame count,
     * and to process the newest one in the processing thread
     */
    class ProcessingTask implements Runnable, Allocation.OnBufferAvailableListener {
        private int mPendingFrames = 0;
        private int mFrameCounter = 0;
        private int mCutPointX;
        private boolean mCheckMerge;

        private Allocation mInputAllocation;

        public ProcessingTask(Allocation input, int cutPointX, boolean checkMerge) {
            mInputAllocation = input;
            mInputAllocation.setOnBufferAvailableListener(this);
            mCutPointX = cutPointX;
            mCheckMerge = checkMerge;
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
                mProcessingHandler.removeCallbacks(this);
            }

            // Get to newest input
            for (int i = 0; i < pendingFrames; i++) {
                mInputAllocation.ioReceive();
            }

            mHdrMergeScript.set_gFrameCounter(mFrameCounter++);
            mHdrMergeScript.set_gCurrentFrame(mInputAllocation);


            // Run processing pass
            mHdrMergeScript.forEach_fuseFrames(mPrevAllocation, mOutputAllocation);
            mOutputAllocation.ioSend();
        }
    }

}
