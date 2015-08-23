package aenz.renderscript;

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
 * rightness levels
 */
public class HistogramProcessor {

    private static final String TAG = "HistogramProcessor";


    private static final int EIGHT_BIT_COLOR_SIZE = 256;

    /**
     * Input and Output Allocation and the Histogram as an int array
     */
    private Allocation inputImageAllocation;
    private Allocation outputHistogramAllocation;
    private int[] flatRgbHistogram = new int[EIGHT_BIT_COLOR_SIZE];

    /**
     * Thread for the renderscript execution
     */

    private HandlerThread mProcessingThread;
    private Handler mProcessingHandler;

    /**
     * Script we use
     */

    private final ScriptIntrinsicHistogram mHistogramScript;


    public HistogramProcessor(RenderScript rs, Size inputDimensions){

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
        mProcessingThread = new HandlerThread("ViewfinderProcessor");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());


        //The Histogram renderscript
        mHistogramScript = ScriptIntrinsicHistogram.create(rs,Element.U8_4(rs));
        //output allocation of renderscript TODO can i set that here or do i need to refresh that after every Allocation.iosend()
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

            mFrameCounter++;


            //processing pass
            mHistogramScript.forEach(mInputAllocation);

            //copy to int[]
            outputHistogramAllocation.copyTo(flatRgbHistogram);


            if(mFrameCounter < 59 /*do something every 59th frame*/) return; //TODO maybe histogram output not sent on every frame
            mFrameCounter = 0;
            int pos = 0;
            //do something with a listener(ExposureMetering)?

        }
    }
}
