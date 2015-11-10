package videohdr.view;

/**
 * The main view class, holding all the button logic and swipe control of the application.
 * This class is based on the GoogleSamples 'Camera2Video' and Camera2Basics
 * (https://github.com/googlesamples/android-Camera2Basic,
 * https://github.com/googlesamples/android-Camera2Video)
 */
import android.app.Activity;
import android.app.Fragment;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;

import videohdr.camera.HdrCamera;


public class VideoHdrFragment extends Fragment implements View.OnClickListener, HdrCamera.ConfigurePreviewListener {

    public static final String TAG = "VideoHdrFragment";

    //identifiers for the button states needed for saving /restoring the view
    private static final String BUTTON_RECORD_TEXT = "b.record.text";
    private static final String BUTTON_UNDEREXP_TEXT = "b.ue.text";
    private static final String BUTTON_OVEREXP_TEXT = "b.oe.text";
    private static final String BUTTON_RECORD_ENABLED = "b.record.active";
    private static final String BUTTON_UNDEREXP_ENABLED = "b.ue.active";
    private static final String BUTTON_OVEREXP_ENABLED = "b.oe.active";
    private static final String SWITCH_AUTOEXP_ENABLED = "s.ae.active";

    /* UI FIELDS*/
    /**
     * TextureView used for the camera preview
     */
    private AutoFitTextureView mTextureView;

    /**
     * Buttons to start/stop video recording and switch to under/overexposed mode
     */
    private Button mRecordButton;
    private Button mOverexposeButton;
    private Button mUnderexposeButton;

    /**
     * Switch to toggle auto-exposure mode
     */
    private Switch mAutoExpSwitch;


    private Size mPreviewSize;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {

            //as soon as the texture is available we open the camera
            mHdrCamera.openCamera(mTextureView);
            /*all other surfaces should be created from HdrCamera*/
            Log.d(TAG, "onSurfaceTextureAvailable: CAMERA open");
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            Log.d(TAG, "opening configTrans from onSTSChanged with: w=" + width + ", h=" + height);
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };



    /* CAMERA AND CAMERA STATE FIELDS */
    /**
     * The camera object with which we will work, configured to be a back facing camera
     * with capability {@link android.hardware.camera2.CameraMetadata} HARDWARE_LEVEL_FULL
     * which allows for alternating exposure of video frames
     */

    private HdrCamera mHdrCamera;




    /* UI METHODS */

    public static VideoHdrFragment newInstance() {
        VideoHdrFragment fragment = new VideoHdrFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState){
        /* instantiate global variables that are used for the whole lifetime of this
        * fragment
        */
        super.onCreate(savedInstanceState);
        mHdrCamera = new HdrCamera(getActivity(), this);
        Log.d(TAG, "camera object created");
    }

    @Override
    public void onDestroy(){
        mHdrCamera.cleanup();
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /**
     * Here we instantiate the Camera Preview TextureView and the record button
     */
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {

        //set up UI elements (Buttons and switches)
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mTextureView.setGestureListener(getActivity(), mViewListener);

        mRecordButton = (Button) view.findViewById(R.id.b_record);
        mRecordButton.setOnClickListener(this);

        mOverexposeButton = (Button) view.findViewById(R.id.b_overexpose);
        mOverexposeButton.setOnClickListener(this);

        mUnderexposeButton = (Button) view.findViewById(R.id.b_underexpose);
        mUnderexposeButton.setOnClickListener(this);

        mAutoExpSwitch = (Switch)  view.findViewById(R.id.sw_auto_exp);

        //set a listener for the auto exposure switch state)
        mAutoExpSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) { //disable over/underexp. buttons, start metering
                    mUnderexposeButton.setEnabled(false);
                    mOverexposeButton.setEnabled(false);
                    mHdrCamera.startAutoMetering();
                } else { //enable buttons again, stop metering
                    mUnderexposeButton.setEnabled(true);
                    mOverexposeButton.setEnabled(true);
                    mHdrCamera.stopAutoMetering();
                }
            }
        });

        if(savedInstanceState != null){
            Log.d(TAG, "restoring button states");
            //restore button states (enabled/disabled, text displayed)
            mRecordButton.setEnabled(savedInstanceState.getBoolean(BUTTON_RECORD_ENABLED));
            mOverexposeButton.setEnabled(savedInstanceState.getBoolean(BUTTON_OVEREXP_ENABLED));
            mUnderexposeButton.setEnabled(savedInstanceState.getBoolean(BUTTON_UNDEREXP_ENABLED));
            mAutoExpSwitch.setEnabled(savedInstanceState.getBoolean(SWITCH_AUTOEXP_ENABLED));

            mRecordButton.setText(savedInstanceState.getCharSequence(BUTTON_RECORD_TEXT));
            mOverexposeButton.setText(savedInstanceState.getCharSequence(BUTTON_OVEREXP_TEXT));
            mUnderexposeButton.setText(savedInstanceState.getCharSequence(BUTTON_UNDEREXP_TEXT));
        }


    }

    @Override
    public void onResume() {
        super.onResume();
        if (mTextureView.isAvailable()) {
            mHdrCamera.openCamera(mTextureView);
            Log.d(TAG, "onResume: CAMERA is open");
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
            Log.d(TAG, "onResume: surface texture listener set");
        }
    }

    @Override
    public void onPause() {
        mHdrCamera.closeCamera();
        Log.d(TAG, "onPause: CAMERA is closed");
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.d(TAG, "saving button states");
        //Save button states
        //save enabled state
        outState.putBoolean(BUTTON_RECORD_ENABLED, mRecordButton.isEnabled());
        outState.putBoolean(BUTTON_OVEREXP_ENABLED, mOverexposeButton.isEnabled());
        outState.putBoolean(BUTTON_UNDEREXP_ENABLED, mUnderexposeButton.isEnabled());
        outState.putBoolean(SWITCH_AUTOEXP_ENABLED, mAutoExpSwitch.isEnabled());

        //save text state
        outState.putCharSequence(BUTTON_RECORD_TEXT, mRecordButton.getText());
        outState.putCharSequence(BUTTON_OVEREXP_TEXT, mOverexposeButton.getText());
        outState.putCharSequence(BUTTON_UNDEREXP_TEXT, mUnderexposeButton.getText());

        super.onSaveInstanceState(outState);
    }

    /**
     * Listener for scroll events so that the user can manually adjust over/underexpose parameters.
     * This listener will only trigger changes in MODE_UNDEREXPOSE or MODE_OVEREXPOSE
     */

    private GestureDetector.OnGestureListener mViewListener
            = new GestureDetector.SimpleOnGestureListener() {

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            HdrCamera.CameraState camState = mHdrCamera.getCameraState();
            if(camState == HdrCamera.CameraState.MODE_UNDEREXPOSE
                    || camState == HdrCamera.CameraState.MODE_OVEREXPOSE) {

                //calculate how much the exposure should be scaled up/down from the motion event
                float height = mTextureView.getHeight();

                float yDistNorm = distanceY / height;

                final float ACCELERATION_FACTOR = 8;
                double scaleFactor = Math.pow(2.f, yDistNorm * ACCELERATION_FACTOR);

                //depending on the camera mode change the exposure of the corresponding frame
                switch(camState){
                    case MODE_UNDEREXPOSE:{
                        mHdrCamera.adjustUnderExposureManually(scaleFactor);
                        break;
                    }
                    case MODE_OVEREXPOSE: {
                        mHdrCamera.adjustOverexposureManually(scaleFactor);
                        break;
                    }
                    default: Log.e(TAG, "this can't happen");
                }
            }
            return true;
        }
    };


    /**
     * Three buttons are available which are controlled in this method:
     * - RECORD/STOP: (start/stop recoding video)
     * - O-EXP/FUSE: (overexposed mode and back to fuse mode)
     * - U-EXP/FUSE: (underexposed mode and back to fuse mode)
     * The buttons are only clickable in certain camera states and trigger state changes when
     * pressed. Auto exposure is not a real camera mode itself, but will simply disable the O-EXP and
     * U-EXP button. A simple graphical representation of the state machine can be seen in my
     * Bachelor Thesis.
     */
    @Override
    public void onClick(View v) {
        int viewID = v.getId();

        switch(mHdrCamera.getCameraState()){
            /*while fusing all three buttons are clickable
            * (unless auto-exp is activated, then only record is enabled)*/
            case MODE_FUSE:{
                switch (viewID){
                    //start recoding, disable all other buttons
                    case R.id.b_record: {
                        mHdrCamera.startRecording();
                        mRecordButton.setText(R.string.stop);
                        mAutoExpSwitch.setEnabled(false);
                        mOverexposeButton.setEnabled(false);
                        mUnderexposeButton.setEnabled(false);
                        break;
                    }
                    //underexpose mode, disable record button
                    case R.id.b_underexpose: {
                        mAutoExpSwitch.setEnabled(false);
                        mHdrCamera.startUnderexposeCapture();
                        mUnderexposeButton.setText(R.string.b_text_return);
                        mRecordButton.setEnabled(false);
                        break;
                    }
                    //overexpose mode, disable record button
                    case R.id.b_overexpose: {
                        mAutoExpSwitch.setEnabled(false);
                        mHdrCamera.startOverexposeCapture();
                        mOverexposeButton.setText(R.string.b_text_return);
                        mRecordButton.setEnabled(false);
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_FUSE");
                }
                break;
            }
            /* in underexpose mode we can either switch back to fuse mode
            * or directly to overexpose mode
            * (while in this mode no auto-exp is possible) */
            case MODE_UNDEREXPOSE: {
                switch (viewID){
                    //switch back to fuse mode
                    case R.id.b_underexpose: {
                        mAutoExpSwitch.setEnabled(true);
                        mHdrCamera.startFuseCapture();
                        mUnderexposeButton.setText(R.string.b_text_underexpose);
                        mRecordButton.setEnabled(true);
                        break;
                    }
                    //switch directly to overexpose mode
                    case R.id.b_overexpose: {
                        mHdrCamera.startOverexposeCapture();
                        mOverexposeButton.setText(R.string.b_text_return);
                        mUnderexposeButton.setText(R.string.b_text_underexpose);
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_UNDEREXPOSE");
                }
                break;
            }
            /* in overexpose mode we can either switch back to fuse mode
            * or directly to underexpose mode
            * (while in this mode no auto-exp is possible) */
            case MODE_OVEREXPOSE:{
                mAutoExpSwitch.setEnabled(false);
                switch (viewID){
                    case R.id.b_underexpose: {
                        mHdrCamera.startUnderexposeCapture();
                        mUnderexposeButton.setText(R.string.b_text_return);
                        mOverexposeButton.setText(R.string.b_text_overexpose);
                        break;
                    }
                    case R.id.b_overexpose: {
                        mAutoExpSwitch.setEnabled(true);
                        mHdrCamera.startFuseCapture();
                        mOverexposeButton.setText(R.string.b_text_overexpose);
                        mRecordButton.setEnabled(true);
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_OVEREXPOSE");
                }
                break;
            }
            //while recording, the only thing we can do is stop the recording
            case MODE_RECORD:{
                switch (viewID){
                    case R.id.b_record: {
                        mAutoExpSwitch.setEnabled(true);
                        mHdrCamera.stopRecording();
                        mRecordButton.setText(R.string.record);
                        if(!mAutoExpSwitch.isChecked()) {
                            mUnderexposeButton.setEnabled(true);
                            mOverexposeButton.setEnabled(true);
                        }
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_RECORD");
                }
                break;
            }
            default: Log.e(TAG, "Illegal camera state");
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * This method was implemented when I still tried to support video capturing in landscape
     * and portrait mode. I decided late into the development to only allow landscape mode, which is
     * why this method is way more complicated than it needs to be. But I will leave it like that,
     * since no problems have arisen from it.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    public void configureTransform(int viewWidth, int viewHeight) {

        Log.d(TAG, "executing configureTransform");
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            Log.d(TAG, "aborting configureTransform");
            return;
        }

        //get the current rotation of the phone screen
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

        //scale and rotate the frames to the preview screen
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);

        //in the following line the width and height is switched to account for weird renderscript behaviour
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight());

        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max(
                (float) viewHeight / mPreviewSize.getHeight(),
                (float) viewWidth / mPreviewSize.getWidth());
        matrix.postScale(scale, scale, centerX, centerY);

        if (Surface.ROTATION_0 == rotation) {
            matrix.postRotate(90, centerX, centerY);
        } else if (Surface.ROTATION_270 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }

        mTextureView.setTransform(matrix);
    }

    /* ConfigurePreviewListener METHODS */
    @Override
    public void onOrientationChanged(Size prevSize, int width, int height){
        mPreviewSize = prevSize;
        configureTransform(width, height);
    }

}
