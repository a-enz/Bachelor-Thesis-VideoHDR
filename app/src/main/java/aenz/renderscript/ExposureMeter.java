package aenz.renderscript;

import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import aenz.videohdr.AlternatingSession;

/**
 * Evaluates output from Histogram Processor and influences input values to AlternatingSession
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
    * [ ] send new values on to adjust AlternatingSession if the change of values was big enough
    *
    * !! new histogram processor needed every time camera is reopened (because of surface size)
    *
    * */

    private static final String TAG = "ExposureMeter";

    //Histogram processor
    private HistogramProcessor mHistProc = null; //has to be created by setupHistogramProcessor

    //The capture session we want to influence;
    private AlternatingSession mCaptureSession;

    public ExposureMeter(){

    }


    /* HISTOGRAM EVALUATION METHODS */
    private void evaluate(int[] frameHistogram){

        //check histograms -> maybe evaluate if they are really from dark and bright frame
        //check if if even more over/underexposed than before
        //give estimate depending on some set metrics
        //send estimate to alternatingsession


        int sumColors = 0;
        int sumPixels = 0;
        for(int i = 0; i < frameHistogram.length; i ++){
            sumColors += i * frameHistogram[i];
            sumPixels += frameHistogram[i];
        }


        Log.d(TAG, "Mean pixel value: " + sumColors / sumPixels);

        /*TODO
        * [ ] check if it is bright/dark histogram
        * [ ] evaluate accordingly, and update recommended values
        * [ ] run a background job that updates alternatingsessoin periodically*/



        //mCaptureSession.setAlternatingCapture(); method should end with a call to this
    }





    /* HISTOGRAM PROCESSOR HANDLING */
    /**
     * creates a new HistogramProcessor with the needed input size and returns the input surface to
     * the Processor.
     * @param rs Renderscript object
     * @param inputSize input size of the frames
     * @return the input surface for the HistogramProcessor
     */
    public Surface setupHistogramProcessor(RenderScript rs, Size inputSize, AlternatingSession captureSession){
        mCaptureSession = captureSession;
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





    /* HELPER METHODS */

}
