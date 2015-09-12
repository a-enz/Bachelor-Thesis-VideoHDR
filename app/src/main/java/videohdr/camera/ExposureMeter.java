package videohdr.camera;

import android.renderscript.RenderScript;
import android.util.Size;
import android.view.Surface;

import videohdr.renderscript.HistogramProcessor;

/**
 * Evaluates output from Histogram Processor and influences input values to AlternatingCaptureSession
 * (ISO and exposure time of bright/dark frame)
 * Created by andi on 13.07.2015.
 */
public class ExposureMeter implements HistogramProcessor.EventListener {
    /* TODO
    * [x] create histogram processor
    * [ ] connect surfaces
    * [x] subscribe listener to histogram output
    * [ ] react on output by evaluating the histogram
    * [ ] depending on evaluation results and PREVIOUS CaptureRequests values set new values
    * [ ] send new values on to adjust AlternatingCaptureSession if the change of values was big enough
    * [ ] OPTIONAL: evaluation could be better done on a background thread?
    * !! new histogram processor needed every time camera is reopened (because of surface size)
    *
    * */

    private static final String TAG = "ExposureMeter";

    //timing constants
    private static final long MICRO_SECOND = 1000;
    private static final long MILLI_SECOND = MICRO_SECOND * 1000;
    private static final long ONE_SECOND = MILLI_SECOND * 1000;

    public static final long FRAME_DURATION = ONE_SECOND / 30; //has to be accessible from exposure metering

    //initial exposure time and iso
    private static final int INITIAL_EVEN_ISO = 120;
    private static final long INITIAL_EVEN_EXPOSURE = ONE_SECOND / 30;
    private static final int INITIAL_ODD_ISO = 120;
    private static final long INITIAL_ODD_EXPOSURE = ONE_SECOND / 150;

    //the metering values
    private MeteringValues currentMeteringValues;

    //Histogram processor
    private HistogramProcessor mHistProc = null; //has to be created by setupHistogramProcessor

    //The capture session we want to influence;
    private EventListener mCaptureSession;

    public ExposureMeter(){
        synchronized (this){
            currentMeteringValues = new MeteringValues(INITIAL_EVEN_ISO,
                                                        INITIAL_EVEN_EXPOSURE,
                                                        INITIAL_ODD_ISO,
                                                        INITIAL_ODD_EXPOSURE);
        }
    }


    /* HISTOGRAM EVALUATION METHODS */
    private void evaluate(int[] frameHistogram){

        //check histograms -> maybe evaluate if they are really from dark and bright frame
        //check if if even more over/underexposed than before
        //give estimate depending on some set metrics
        //send estimate to AlternatingCaptureSession


        int sumColors = 0;
        int sumPixels = 0;
        for(int i = 0; i < frameHistogram.length; i ++){
            sumColors += i * frameHistogram[i];
            sumPixels += frameHistogram[i];
        }


        //Log.d(TAG, "Mean pixel value: " + sumColors / sumPixels);

        /*TODO
        * [ ] check if it is bright/dark histogram
        * [ ] evaluate accordingly, and update recommended values
        * [ ] run a background job that updates alternatingsessoin periodically*/



        //mCaptureSession.onMeterEvent(null, null);
    }





    /* HISTOGRAM PROCESSOR HANDLING */
    /**
     * creates a new HistogramProcessor with the needed input size and returns the input surface to
     * the Processor.
     * @param rs Renderscript object
     * @param inputSize input size of the frames
     * @return the input surface for the HistogramProcessor
     */
    public Surface setupHistogramProcessor(RenderScript rs, Size inputSize, EventListener captureSession){
        mCaptureSession = captureSession; //TODO delete this and create a listener that checks on exposure meter events
        mHistProc = new HistogramProcessor(rs,inputSize, this);
        return mHistProc.getInputSurface();
    }

    /**
     * should be called when closing the camera and ensures no listener event is created from discarded
     * HistogramProcessor
     */
    public void destroyHistogramProcessor(){
        mHistProc.disconnectListener(); //no more evaluation calls as soon as camera closes
        mHistProc = null;
    }


    @Override
    public void onHistogramAvailable(int[] frameHistogram) {
        evaluate(frameHistogram);
    }


    /* GETTER & SETTER */
    public MeteringValues getMeteringValues(){
        synchronized (this){
            MeteringValues res = currentMeteringValues;
        }
        return currentMeteringValues;
    }



    /* HELPER METHODS AND CLASSES*/

    //Listener Interface to Inform subscribers that the Exposure Parameters have changed
    public interface EventListener {
        /**
         *
         * @param param contains iso and duration of over and underexposure
         */
        void onMeterEvent(MeteringValues param);
    }

    public class MeteringValues{
        private int underexposeIso;
        private long underexposeDuration;
        private int overexposeIso;
        private long overexposeDuration;

        public MeteringValues(int uIso, long uDuration, int oIso, long oDuration){
            underexposeIso = uIso;
            underexposeDuration = uDuration;

            overexposeIso = oIso;
            overexposeDuration = oDuration;
        }


        public int getUnderexposeIso() {
            return underexposeIso;
        }

        public long getUnderexposeDuration() {
            return underexposeDuration;
        }

        public int getOverexposeIso() {
            return overexposeIso;
        }

        public long getOverexposeDuration() {
            return overexposeDuration;
        }
    }

}
