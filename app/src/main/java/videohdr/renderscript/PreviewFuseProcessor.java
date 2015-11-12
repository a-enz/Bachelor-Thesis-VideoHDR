package videohdr.renderscript;

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
 * Mostly copied from HdrViewfinders (https://github.com/googlesamples/android-HdrViewfinder)
 * ViewfinderProcessor class. Combined with a renderscript we fuse a double exposure into a single frame
 * The renderscript tied to this class is called "preview_fuse.rs" and can be found in
 * ...\app\src\main\rs\
 *
 * Created by Andreas Enz on 13.07.2015.
 */
public class PreviewFuseProcessor {

    private static final String TAG = "PreviewFuseProcessor";

    //weights will be computed as fractional parts of 10^5
    int[] weights = {25000,25732,26464,27194,27923,28650,29374,30095,30811,31523,32231,32932,33628,
            34316,34998,35672,36337,36993,37640,38277,38903,39518,40121,40712,41290,41855,42406,
            42942,43463,43968,44458,44930,45385,45823,46241,46641,47022,47382,47721,48039,48336,
            48610,48861,49089,49293,49472,49626,49754,49856,49931,49979,49999,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,
            50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,50000,49990,
            49954,49889,49798,49680,49536,49367,49173,48955,48713,48448,48161,47851,47520,47168,
            46796,46404,45992,45562,45114,44649,44166,43667,43152,42622,42077,41518,40945,40359,
            39761,39150,38528,37896,37253,36600,35939,35268,34590,33904,33211,32512,31807,31097,
            30382,29663,28940,28214,27486,26756,26025,25292,24560,23828,23097,22367,21639,20914,
            20192,19474,18760,18051,17347,16649,15957,15273,14596,13927,13267,12617,11976,11345,
            10726,10118,9522,8938,8368};

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

        //set up input allocation
        Type.Builder yuvTypeBuilder = new Type.Builder(rs, Element.YUV(rs));
        yuvTypeBuilder.setX(width);
        yuvTypeBuilder.setY(height);
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);
        mInputAllocation = Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);

        //set up output allocation
        Type.Builder rgbTypeBuilder = new Type.Builder(rs, Element.RGBA_8888(rs));
        rgbTypeBuilder.setX(width);
        rgbTypeBuilder.setY(height);
        mPrevAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        //provide weights array to script
        Allocation w = Allocation.createSized(rs, Element.I32(rs), weights.length);
        w.copyFrom(weights);


        //processing thread for this processor
        mProcessingThread = new HandlerThread("PreviewFuseProcessor");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());

        //the custom script used to fuse two frames
        mFuseScript = new ScriptC_preview_fuse(rs);

        //the fuse script needs a previous frame to fuse it with a current frame
        //this is the initialization
        mFuseScript.set_gPrevFrame(mPrevAllocation);
        mFuseScript.bind_weights(w);

        mFuseTask = new ProcessingTask(mInputAllocation);
    }

    /**
     * This surface object is used to provide camera output to this processor.
     * @return the input surface to this processor
     */
    public Surface getInputSurface() {
        return mInputAllocation.getSurface();
    }

    /**
     * This surface object is used to propagate the output of this processor
     * further.
     * @param output the output surface of this processor
     */
    public void setOutputSurface(Surface output) {
        mOutputAllocation.setSurface(output);
    }

    /**
     * Stop the PreviewFuseProcessor by destroying input and output surface and quitting
     * the processing thread.
     */
    public void stop(){

        mInputAllocation.destroy();
        mOutputAllocation.destroy();
        mProcessingThread.quit();
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
                mProcessingHandler.removeCallbacks(this);
            }


            // Get to newest input
            for (int i = 0; i < pendingFrames; i++) {
                mInputAllocation.ioReceive();
            }

            mFuseScript.set_gFrameCounter(mFrameCounter++);
            mFuseScript.set_gCurrentFrame(mInputAllocation);


            // Run processing pass
            mFuseScript.forEach_fuseFrames(mPrevAllocation, mOutputAllocation);
            mOutputAllocation.ioSend(); //send to output surface
        }
    }

}
