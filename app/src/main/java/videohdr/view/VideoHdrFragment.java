package videohdr.view;

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

import videohdr.camera.HdrCamera;


/**
 * A placeholder fragment containing a simple view.
 */
public class VideoHdrFragment extends Fragment implements View.OnClickListener, HdrCamera.ConfigurePreviewListener {

    public static final String TAG = "VideoHdrFragment";

    //identifiers for the button states needed for saving /restoring the view
    private static final String BUTTON_RECORD_TEXT = "b.record.text";
    private static final String BUTTON_UNDEREXP_TEXT = "b.ue.text";
    private static final String BUTTON_OVEREXP_TEXT = "b.oe.text";
    private static final String BUTTON_RECORD_ENABLED = "b.record.active";
    private static final String BUTTON_UNDEREXP_ENABLED = "b.ue.active";
    private static final String BUTTON_OVEREXP_ENABLED = "b.oe.active";

    /* UI FIELDS*/
    /**
     * TextureView used for the camera preview
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to start/stop video recording
     */
    private Button mRecordButton;
    private Button mOverexposeButton;
    private Button mUnderexposeButton;

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

            mHdrCamera.openCamera(mTextureView);
            /*all other surfaces should be created from HdrCamera*/
            Log.d(TAG, "onSurfaceTextureAvailable: CAMERA open");
            //mHdrCamera.configurePreview(mTextureView, width, height); not here!
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





    /* VIDEO RECORDER AND RECORDER STATE FIELDS */
    /**
     * global recording state of the camera
     */
    private static boolean mIsRecording = false;




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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /**
     * Here we instantiate the Camera Preview TextureView and the record button
     */
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        //set up UI elements
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mTextureView.setGestureListener(getActivity(), mViewListener);

        mRecordButton = (Button) view.findViewById(R.id.b_record);
        mRecordButton.setOnClickListener(this);

        mOverexposeButton = (Button) view.findViewById(R.id.b_overexpose);
        mOverexposeButton.setOnClickListener(this);

        mUnderexposeButton = (Button) view.findViewById(R.id.b_underexpose);
        mUnderexposeButton.setOnClickListener(this);

        if(savedInstanceState != null){
            Log.d(TAG, "restoring button states");
            mRecordButton.setEnabled(savedInstanceState.getBoolean(BUTTON_RECORD_ENABLED));
            mOverexposeButton.setEnabled(savedInstanceState.getBoolean(BUTTON_OVEREXP_ENABLED));
            mUnderexposeButton.setEnabled(savedInstanceState.getBoolean(BUTTON_UNDEREXP_ENABLED));

            mRecordButton.setText(savedInstanceState.getCharSequence(BUTTON_RECORD_TEXT));
            mOverexposeButton.setText(savedInstanceState.getCharSequence(BUTTON_OVEREXP_TEXT));
            mUnderexposeButton.setText(savedInstanceState.getCharSequence(BUTTON_UNDEREXP_TEXT));
        }


    }

    @Override
    public void onResume() {
        super.onResume();
        if (mTextureView.isAvailable()) {
            //configureTransform(mTextureView.getWidth(), mTextureView.getHeight()); //TODO necessary here?
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
        //save enabled state
        outState.putBoolean(BUTTON_RECORD_ENABLED, mRecordButton.isEnabled());
        outState.putBoolean(BUTTON_OVEREXP_ENABLED, mOverexposeButton.isEnabled());
        outState.putBoolean(BUTTON_UNDEREXP_ENABLED, mUnderexposeButton.isEnabled());

        //save text state
        outState.putCharSequence(BUTTON_RECORD_TEXT, mRecordButton.getText());
        outState.putCharSequence(BUTTON_OVEREXP_TEXT, mOverexposeButton.getText());
        outState.putCharSequence(BUTTON_UNDEREXP_TEXT, mUnderexposeButton.getText());

        super.onSaveInstanceState(outState);
    }

    //Listener for scroll events so that we can manually adjust over/underexpose parameters
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

                float height = mTextureView.getHeight();

                float yDistNorm = distanceY / height;

                final float ACCELERATION_FACTOR = 8;
                double scaleFactor = Math.pow(2.f, yDistNorm * ACCELERATION_FACTOR);
                Log.d(TAG, "scaling by factor: " + scaleFactor);
                switch(camState){
                    case MODE_UNDEREXPOSE:{
                        mHdrCamera.startUnderexposeCapture();
                        break;
                    }
                    case MODE_OVEREXPOSE: {
                        mHdrCamera.startOverexposeCapture();
                        break;
                    }
                    default: Log.e(TAG, "this can't happen");
                }
            }
            return true;
        }
    };


    /**
     * Only one button is available right now, record start/stop. Here the recording should
     * be started while the Camera is already capturing Frames in the background like it would for the
     * recording. That is because we also need to provide a preview and do some exposure metering to
     * get hopefully good parameter settings for the actual recorded part
     * @param v
     */
    @Override
    public void onClick(View v) {
        int viewID = v.getId();

        switch(mHdrCamera.getCameraState()){
            case MODE_FUSE:{
                switch (viewID){
                    case R.id.b_record: {
                        mHdrCamera.startRecording();
                        mRecordButton.setText(R.string.stop);
                        mOverexposeButton.setEnabled(false);
                        mUnderexposeButton.setEnabled(false);
                        break;
                    }
                    case R.id.b_underexpose: {
                        mHdrCamera.startUnderexposeCapture();
                        mUnderexposeButton.setText(R.string.b_text_return);
                        mRecordButton.setEnabled(false);
                        break;
                    }
                    case R.id.b_overexpose: {
                        mHdrCamera.startOverexposeCapture();
                        mOverexposeButton.setText(R.string.b_text_return);
                        mRecordButton.setEnabled(false);
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_FUSE");
                }
                break;
            }
            case MODE_UNDEREXPOSE:{
                switch (viewID){
                    case R.id.b_underexpose: {
                        mHdrCamera.startFuseCapture();
                        mUnderexposeButton.setText(R.string.b_text_underexpose);
                        mRecordButton.setEnabled(true);
                        break;
                    }
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
            case MODE_OVEREXPOSE:{
                switch (viewID){
                    case R.id.b_underexpose: {
                        mHdrCamera.startUnderexposeCapture();
                        mUnderexposeButton.setText(R.string.b_text_return);
                        mOverexposeButton.setText(R.string.b_text_overexpose);
                        break;
                    }
                    case R.id.b_overexpose: {
                        mHdrCamera.startFuseCapture();
                        mOverexposeButton.setText(R.string.b_text_overexpose);
                        mRecordButton.setEnabled(true);
                        break;
                    }
                    default: Log.e(TAG, "Illegal button press during MODE_OVEREXPOSE");
                }
                break;
            }
            case MODE_RECORD:{
                switch (viewID){
                    case R.id.b_record: {
                        mHdrCamera.stopRecording();
                        mRecordButton.setText(R.string.record);
                        mUnderexposeButton.setEnabled(true);
                        mOverexposeButton.setEnabled(true);
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

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

        Matrix matrix = new Matrix();

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        //in the following declaration the width and height is switched to account for weird renderscript behaviour :S
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
            matrix.postRotate(90,centerX,centerY);
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
