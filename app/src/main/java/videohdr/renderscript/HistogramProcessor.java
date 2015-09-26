package videohdr.renderscript;

import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicHistogram;
import android.renderscript.Type;
import android.util.Size;
import android.view.Surface;

/**
 * Created by andi on 13.07.2015.
 *
 * Together with a RenderScript we generate a Histogram of every captured frame.
 * The Histogram consists of one array of length 256 and measures the occurrence of different
 * brightness levels
 */
public class HistogramProcessor {

    private static final String TAG = "HistogramProcessor";


    private static final int EIGHT_BIT_COLOR_SIZE = 256;

    /**
     * Input and Output Allocation and the Histogram as an int array
     */
    private Allocation inputImageAllocation;
    private Allocation outputHistogramAllocation;
    private int[] frameHist = new int[EIGHT_BIT_COLOR_SIZE];

    /**
     * Thread for the renderscript execution
     */

    private Handler mProcessingHandler;


    /**
     * HistogramListener
     */
    private EventListener mHistogramListener = null;
    /**
     * Script we use
     */

    private final ScriptIntrinsicHistogram mHistogramScript;


    public HistogramProcessor(RenderScript rs, Size inputDimensions, EventListener listener){

        //assign listener
        mHistogramListener = listener;

        //build input allocation
        Type.Builder yuvTypeBuilder = new Type.Builder(rs, Element.YUV(rs));
        yuvTypeBuilder.setX(inputDimensions.getWidth());
        yuvTypeBuilder.setY(inputDimensions.getHeight());
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);
        inputImageAllocation = Allocation.createTyped(rs, yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);

        //build output allocation
        outputHistogramAllocation = Allocation.createSized(rs, Element.I32(rs),
                EIGHT_BIT_COLOR_SIZE);

        //a background thread to work the RS
        HandlerThread mProcessingThread = new HandlerThread("ViewfinderProcessor");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());


        //The Histogram renderscript
        mHistogramScript = ScriptIntrinsicHistogram.create(rs,Element.U8_4(rs));
        /* used together with .forEach_Dot()
            presets are: {0.299f, 0.587f, 0,114f,  0.f}
         */

        //output allocation of renderscript
        mHistogramScript.setOutput(outputHistogramAllocation);


        new ProcessingTask(inputImageAllocation);


    }

    public Surface getInputSurface(){
        return inputImageAllocation.getSurface();
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



            //processing pass
            mHistogramScript.forEach(mInputAllocation);
            outputHistogramAllocation.copyTo(frameHist);

            //copy to int[] //
            if (mFrameCounter++ % 3 == 0 && mHistogramListener != null) mHistogramListener.onHistogramAvailable(frameHist);


        }
    }

    public void disconnectListener(){
        mHistogramListener = null;
    }

    public interface EventListener {
        void onHistogramAvailable(int[] frameHistogram);
    }
}
