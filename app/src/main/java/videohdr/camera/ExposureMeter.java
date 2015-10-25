package videohdr.camera;


import android.os.Environment;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

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

    //EVALUATION VALUES & CONSTANTS
    //used to evaluate if frame is an over or underexposed frame
    private static final int UO_CHECK_CHANNELS = 4;
    private static final float OVEREXP_RATIO_THRESH = 0.005f;
    private static final float UNDEREXP_RATIO_THRESH = 0.005f;

    private float prev_mean_brightness = 0;

    //used to evaluate if change to current frame is needed
    private static final float OVEREXP_UPPER_THRESHHOLD = 0.2f;
    private static final float OVEREXP_LOWER_THRESHHOLD = 0.2f;
    private static final float OVEREXP_WELLEXP_THRESHHOLD = 0.2f;
    private static final float UNDEREXP_UPPER_THRESHHOLD = 0.2f;
    private static final float UNDEREXP_LOWER_THRESHHOLD = 0.2f;
    private static final float UNDEREXP_WELLEXP_THRESHHOLD = 0.2f;




    //the metering values
    private MeteringParam currentMeteringParam;

    //auto metering values
    private boolean isAutoMetering;
    private static final double AUTO_EXP_INC_FACTOR_WEAK = 1.001f;
    private static final double AUTO_EXP_INC_FACTOR_STRONG = 1.02f;
    private static final double AUTO_EXP_DEC_FACTOR_WEAK = 0.999f;
    private static final double AUTO_EXP_DEC_FACTOR_STRONG = 0.98f;

    //Histogram processor
    private HistogramProcessor mHistProc = null; //has to be created by setupHistogramProcessor

    private int totalMeteringPixels = 0;

    //Log file for the histogram for this particular session
    private BufferedWriter logFileWriter;
    private int histogramTAG = 0;


    //The capture session we want to influence;
    private HdrCamera mCamera;
    private EventListener mCaptureSession;


    public ExposureMeter(HdrCamera camera){
        mCamera = camera;

        File logFile = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM + "/Camera/HistLOG_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".txt");

        try{
            logFileWriter = new BufferedWriter(new FileWriter(logFile, true));
        } catch (IOException e){
            Log.d(TAG, "error creating the LogFileWriter");
            logFileWriter = null;
        }

        synchronized (this){
            currentMeteringParam = new MeteringParam(INITIAL_EVEN_ISO,
                                                        INITIAL_EVEN_EXPOSURE,
                                                        INITIAL_ODD_ISO,
                                                        INITIAL_ODD_EXPOSURE);
        }
    }


    /* HISTOGRAM EVALUATION METHODS */

    /**
     * This method is mostly hardcoded for my specific case: somehow upper- and lowermost
     * channels of the frameHistogram are never filled at all. Unfortunately the number of empty
     * channels is not even symmetric for upper/lower channels.
     * - using Nexus 6 phone
     * - using very small frame size for histogram
     * @param frameHistogram histogram, has to be of size 256
     */
    private void evaluate(int[] frameHistogram){
        histogramTAG++;
        HdrCamera.CameraState camState = mCamera.getCameraState();
        if (totalMeteringPixels == 0 ||
                !(camState == HdrCamera.CameraState.MODE_RECORD ||
                camState == HdrCamera.CameraState.MODE_FUSE)) return;



        //first log this histogram with the current tag FIXME might be better to disable when not needed for dev
        if(!isAutoMetering && camState == HdrCamera.CameraState.MODE_RECORD){
            logHistogram(frameHistogram);
        }


        if(isAutoMetering && mCaptureSession != null) {

            int brightnessSum = 0;
            for(int i = 0; i < frameHistogram.length; i++){
                brightnessSum += frameHistogram[i] * i;
                //TODO pack more into this so that we don't have to loop over the hist several times
            }
            float mean_brightness = (float) brightnessSum / totalMeteringPixels;

            //PRE-EVALUATION CHECKS
            //check if this frame is supposed to be the over or underexposed
            int underExpAmount = 0;
            int overExpAmount = 0;

            //percentage of pixels deemed severely over/underexposed
            int startPos = -1;
            while(frameHistogram[++startPos]==0); //this line is used to circumvent weird camera behaviour
            for(int i = startPos; startPos + UO_CHECK_CHANNELS > i; i++){
                underExpAmount += frameHistogram[i];
            }

            startPos = frameHistogram.length;
            while(frameHistogram[--startPos]==0); //this line is used to circumvent weird camera behaviour
            for(int i = startPos; startPos - UO_CHECK_CHANNELS < i; i--){
                overExpAmount += frameHistogram[i];
            }

            float underExpRatio = (float) underExpAmount / totalMeteringPixels;
            float overExpRatio = (float) overExpAmount / totalMeteringPixels;

            //stop evaluation if this was the first frame
            if(prev_mean_brightness == 0) {
                //set values for next evaluation
                prev_mean_brightness = mean_brightness;
                Log.d(TAG, "First automatic evaluation of a frame");
                return;
            }

            boolean isUnderExposedFrame = prev_mean_brightness > mean_brightness * 1.01f; //leave some wiggle room
            boolean isOverExposedFrame = prev_mean_brightness < mean_brightness * 0.99f;

            if(!isOverExposedFrame && !isUnderExposedFrame){ //can't decide, brightness levels too similar
                //initiate general spread of capture parameters
                if(histogramTAG % 2 == 0){
                    Log.d(TAG, "initiating spread in frame brightness");
                    changeOverExpParam(AUTO_EXP_INC_FACTOR_STRONG);
                    changeUnderExpParam(AUTO_EXP_DEC_FACTOR_STRONG);
                    mCaptureSession.onMeterEvent(currentMeteringParam);
                }
                return;
            }

            /*from this point on 'isOverExposedFrame != isUnderExposedFrame' should hold
            * since not both of them can be true (see initialization of those values)*/

            // influence underexp values
            if(isUnderExposedFrame) {
                Log.d(TAG, "This frame is: UNDER exposed. Mean brightness: " + mean_brightness);


            }

            //influence overexp values
            if(isOverExposedFrame) {
                Log.d(TAG, "This frame is: OVER exposed. Mean brightness: " + mean_brightness);
            }



            //set values for next evaluation
            prev_mean_brightness = mean_brightness;

            /*influence the camera capture settings - at most every 2nd evaluation run
            * which means every second frame. It wouldn't make sense to do it every time
            * since the camera changes the capture values only after a burst (in this case consisting
            * of 2 frames, is finished) */
            if(histogramTAG % 2 == 0) {
                mCaptureSession.onMeterEvent(currentMeteringParam);
            }
        }
        else { //not auto metering: reset values of previous frame
            prev_mean_brightness = 0;
        }
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
        totalMeteringPixels = inputSize.getWidth() * inputSize.getHeight();
        return mHistProc.getInputSurface();
    }

    /**
     * should be called when closing the camera and ensures no listener event is created from discarded
     * HistogramProcessor
     */
    public void destroyHistogramProcessor(){
        mHistProc.disconnectListener(); //no more evaluation calls as soon as camera closes
        totalMeteringPixels = 0;
        mHistProc = null;
    }

    private void logHistogram(int[] frameHistogram){
        String histString = "[" + String.format("%08d",histogramTAG) + "]::";

        int j = 0;
        while(j < frameHistogram.length - 1) histString += frameHistogram[j++] + ",";
        histString += frameHistogram[j];

        if(logFileWriter != null){
            try {
                logFileWriter.append(histString);
                logFileWriter.newLine();
            } catch(IOException e){
                Log.d(TAG, "error writing histogram to file");
            }
        }

    }

    public void setMeteringEventListener(EventListener listener){
        mCaptureSession = listener;
    }

    /* SPLIT METHODS FOR EXPOSURE ADJUSTMENTS */
    //less problems with corner cases
    public void adjustOverexposure(double factor){
        if(mCaptureSession == null) return;

        changeOverExpParam(factor); //values will be stored in 'currentMeteringParam'

        Log.d(TAG, currentMeteringParam.toString());
        mCaptureSession.onMeterEvent(currentMeteringParam);
    }

    public void adjustUnderexposure(double factor){
        if(mCaptureSession == null) return;

        changeUnderExpParam(factor); //values will be stored in 'currentMeteringParam'

        Log.d(TAG, currentMeteringParam.toString());
        mCaptureSession.onMeterEvent(currentMeteringParam);
    }

    public void startAutoMetering(){
        isAutoMetering = true;
    }

    public void stopAutoMetering(){
        isAutoMetering = false;
    }

    private void changeUnderExpParam(double factor){
        Log.d(TAG, "adjusting UNDER exp by factor " + factor);
        long dur_o;
        long dur_u;
        int iso_o;
        int iso_u;
        synchronized (this){
            dur_o = currentMeteringParam.overexposeDuration;
            dur_u= currentMeteringParam.underexposeDuration;
            iso_o = currentMeteringParam.overexposeIso;
            iso_u = currentMeteringParam.underexposeIso;
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
            currentMeteringParam.underexposeDuration = dur_new_u;
            currentMeteringParam.underexposeIso = iso_new_u;
        }
    }

    private void changeOverExpParam(double factor){
        Log.d(TAG, "adjusting OVER exp by factor " + factor);
        long dur_o;
        long dur_u;
        int iso_o;
        int iso_u;
        synchronized (this){
            dur_o = currentMeteringParam.overexposeDuration;
            dur_u= currentMeteringParam.underexposeDuration;
            iso_o = currentMeteringParam.overexposeIso;
            iso_u = currentMeteringParam.underexposeIso;
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
        synchronized (this) {
            currentMeteringParam.overexposeDuration = dur_new_o;
            currentMeteringParam.overexposeIso = iso_new_o;
        }
    }

    public void finish(){
        try{
            if(logFileWriter != null) logFileWriter.close();
        } catch(IOException e) {
            Log.d(TAG, "closing the logfile writer failed");
        }
    }


    @Override
    public void onHistogramAvailable(int[] frameHistogram) {
        evaluate(frameHistogram);
    }


    /* GETTER & SETTER */
    public MeteringParam getMeteringValues(){
        MeteringParam res;
        synchronized (this){
            res = currentMeteringParam;
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
        void onMeterEvent(MeteringParam param);
    }

    public class MeteringParam {
        private int underexposeIso;
        private long underexposeDuration;
        private int overexposeIso;
        private long overexposeDuration;

        public MeteringParam(int uIso, long uDuration, int oIso, long oDuration){
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
