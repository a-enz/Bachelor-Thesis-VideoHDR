package videohdr.camera;


import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import videohdr.renderscript.HistogramProcessor;

/**
 * Evaluates output from Histogram Processor and influences input values to AlternatingCaptureSession
 * (ISO and exposure time of bright/dark frame)
 * Created by andi on 13.07.2015.
 */
public class ExposureMeter implements HistogramProcessor.EventListener {

    private static final String TAG = "ExposureMeter";

    //timing constants
    private static final long MICRO_SECOND = 1000;
    private static final long MILLI_SECOND = MICRO_SECOND * 1000;
    private static final long ONE_SECOND = MILLI_SECOND * 1000;

    public static final long FRAME_DURATION = ONE_SECOND / 30; //has to be accessible from exposure metering


    //bounds for exposure time and iso
    private static final long MAX_DURATION = FRAME_DURATION / 4;
    private static final int MAX_ISO = 1200;
    private static final int MIN_ISO = 80;

    //initial exposure time and iso
    private static final int INITIAL_EVEN_ISO = MIN_ISO;
    private static final long INITIAL_EVEN_EXPOSURE = ONE_SECOND / 600;
    private static final int INITIAL_ODD_ISO = MIN_ISO;
    private static final long INITIAL_ODD_EXPOSURE = MAX_DURATION;


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


        /*if(mCaptureSession != null) mCaptureSession.onMeterEvent(stuff, stuff); */
    }





    /* HISTOGRAM PROCESSOR HANDLING */
    /**
     * creates a new HistogramProcessor with the needed input size and returns the input surface to
     * the Processor.
     * @param rs Renderscript object
     * @param inputSize input size of the frames
     * @return the input surface for the HistogramProcessor
     */
    public Surface setupHistogramProcessor(RenderScript rs, Size inputSize){
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

    public void setMeteringEventListener(EventListener listener){
        mCaptureSession = listener;
    }

    /* SPLIT METHODS FOR EXPOSURE ADJUSTMENTS */
    //less problems with corner cases
    public void adjustOverexposure(double factor){
        if(mCaptureSession == null) return;
        long dur_o;
        long dur_u;
        int iso_o;
        int iso_u;
        synchronized (this){
            dur_o = currentMeteringValues.overexposeDuration;
            dur_u= currentMeteringValues.underexposeDuration;
            iso_o = currentMeteringValues.overexposeIso;
            iso_u = currentMeteringValues.underexposeIso;
        }
        long dur_new_o = (long) (dur_o * factor);
        int iso_new_o = (int) (iso_o * factor);

        if(iso_o != MIN_ISO){
            iso_new_o = (iso_new_o > MAX_ISO) ? MAX_ISO :
                    ((iso_new_o < iso_u) ? iso_u : iso_new_o);
            dur_new_o = dur_o;
        }
        else {
            if(dur_new_o > MAX_DURATION){
                dur_new_o = MAX_DURATION;
                iso_new_o = MIN_ISO + 1;
            }
            else {
                dur_new_o = (dur_new_o < dur_u) ? dur_u : dur_new_o;
                iso_new_o = iso_o;
            }
        }
        synchronized (this){
            currentMeteringValues.overexposeDuration = dur_new_o;
            currentMeteringValues.overexposeIso = iso_new_o;
        }
        Log.d(TAG, currentMeteringValues.toString());
        mCaptureSession.onMeterEvent(currentMeteringValues);
    }

    public void adjustUnderexposure(double factor){
        if(mCaptureSession == null) return;
        long dur_o;
        long dur_u;
        int iso_o;
        int iso_u;
        synchronized (this){
            dur_o = currentMeteringValues.overexposeDuration;
            dur_u= currentMeteringValues.underexposeDuration;
            iso_o = currentMeteringValues.overexposeIso;
            iso_u = currentMeteringValues.underexposeIso;
        }
        long dur_new_u = (long) (dur_u * factor);
        int iso_new_u = (int) (iso_u * factor);

        if(iso_u != MIN_ISO){
            iso_new_u = (iso_new_u >= iso_o) ? iso_o :
                    ((iso_new_u <= MIN_ISO) ? MIN_ISO : iso_new_u);
            dur_new_u = dur_u;
        }
        else {
            if(dur_new_u > MAX_DURATION){
                dur_new_u = MAX_DURATION;
                iso_new_u = (iso_o != MIN_ISO) ? MIN_ISO + 1 : MIN_ISO;
            }
            else{
                dur_new_u = (dur_new_u > dur_o) ? dur_o : dur_new_u;
                iso_new_u = iso_u;
            }
        }

        synchronized (this){
            currentMeteringValues.underexposeDuration = dur_new_u;
            currentMeteringValues.underexposeIso = iso_new_u;
        }
        Log.d(TAG, currentMeteringValues.toString());
        mCaptureSession.onMeterEvent(currentMeteringValues);
    }


    @Override
    public void onHistogramAvailable(int[] frameHistogram) {
        evaluate(frameHistogram);
    }


    /* GETTER & SETTER */
    public MeteringValues getMeteringValues(){
        MeteringValues res;
        synchronized (this){
            res = currentMeteringValues;
        }
        return res;
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

        @Override
        public String toString(){
            return "UnderExp: (ISO: " + underexposeIso + ", DUR: " + underexposeDuration + ") \n" +
                    "OverExp: (ISO: " + overexposeIso + ", DUR: " + overexposeDuration + ")";
        }
    }

}
