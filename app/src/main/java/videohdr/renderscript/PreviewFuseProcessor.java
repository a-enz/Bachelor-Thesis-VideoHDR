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
    int[] weights = {25406,25675,25945,26215,26486,26758,27030,27303,27576,27850,28124,28399,28673,
            28948,29224,29499,29775,30050,30326,30602,30877,31153,31428,31704,31979,32253,32528,
            32802,33075,33348,33621,33893,34164,34435,34705,34974,35243,35510,35777,36042,36307,
            36570,36833,37094,37354,37613,37870,38126,38380,38633,38885,39135,39383,39629,39874,
            40117,40358,40597,40835,41070,41303,41534,41763,41990,42214,42436,42656,42873,43088,
            43301,43511,43718,43922,44124,44324,44520,44714,44904,45092,45277,45459,45638,45813,
            45986,46155,46322,46485,46644,46801,46954,47103,47250,47392,47532,47667,47799,47928,
            48053,48174,48292,48406,48516,48622,48725,48823,48918,49009,49097,49180,49259,49335,
            49406,49473,49537,49596,49652,49703,49750,49793,49832,49867,49898,49925,49948,49966,
            49981,49991,49997,50000,49997,49991,49981,49966,49948,49925,49898,49867,49832,49793,
            49750,49703,49652,49596,49537,49473,49406,49335,49259,49180,49097,49009,48918,48823,
            48725,48622,48516,48406,48292,48174,48053,47928,47799,47667,47532,47392,47250,47103,
            46954,46801,46644,46485,46322,46155,45986,45813,45638,45459,45277,45092,44904,44714,
            44520,44324,44124,43922,43718,43511,43301,43088,42873,42656,42436,42214,41990,41763,
            41534,41303,41070,40835,40597,40358,40117,39874,39629,39383,39135,38885,38633,38380,
            38126,37870,37613,37354,37094,36833,36570,36307,36042,35777,35510,35243,34974,34705,
            34435,34164,33893,33621,33348,33075,32802,32528,32253,31979,31704,31428,31153,30877,
            30602,30326,30050,29775,29499,29224,28948,28673,28399,28124,27850,27576,27303,27030,
            26758,26486,26215,25945,25675};

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
