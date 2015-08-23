package aenz.videohdr;

import android.app.Activity;
import android.app.Fragment;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;


/**
 * A placeholder fragment containing a simple view.
 */
public class VideoHdrFragment extends Fragment implements View.OnClickListener, HdrCamera.ConfigurePreviewListener {

    public static final String TAG = "VideoHdrFragment";



    /* UI FIELDS*/
    /**
     * TextureView used for the camera preview
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to start/stop video recording
     */
    private Button mRecordButton;

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

            mHdrCamera.openCamera(mTextureView); //TODO provide also preview surface
            /*all other surfaces should be created from HdrCamera*/
            Log.d(TAG, "onSurfaceTextureAvailable: CAMERA open");
            //mHdrCamera.configurePreview(mTextureView, width, height); not here!
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
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
    private static boolean mIsRecording;




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
        mRecordButton = (Button) view.findViewById(R.id.b_video);
        mRecordButton.setOnClickListener(this);
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


    /**
     * Only one button is available right now, record start/stop. Here the recording should
     * be started while the Camera is already capturing Frames in the background like it would for the
     * recording. That is because we also need to provide a preview and do some exposure meetering to
     * get hopefully good parameter settings for the actual recorded part
     * @param v
     */
    @Override
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.b_video:{
                if(mIsRecording){
                    String filepath = mHdrCamera.stopRecording();


                    //register file with MediaScanner
                    MediaScannerConnection.scanFile(
                            this.getActivity(),
                            new String[]{filepath},
                            null,
                            new MediaScannerConnection.OnScanCompletedListener() {
                                @Override
                                public void onScanCompleted(String s, Uri uri) {
                                    Log.d(TAG, "file should now be visible");
                                }
                            }
                    );
                }
                else{
                    mHdrCamera.startRecording();
                }
                break;
            }

            default: Log.d(TAG, "onClick Method couldn't recognize view");
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
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max(
                (float) viewHeight / mPreviewSize.getHeight(),
                (float) viewWidth / mPreviewSize.getWidth());
        matrix.postScale(scale, scale, centerX, centerY);
        if (Surface.ROTATION_0 == rotation) {
            matrix.postRotate(90 * (rotation - 3), centerX, centerY);
        } else {
            matrix.postRotate(90 * (rotation - 1), centerX, centerY);
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
