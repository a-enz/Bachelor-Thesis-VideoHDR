package videohdr.view;

import android.app.Activity;
import android.os.Bundle;


public class MainActivity extends Activity {

    /**
     * Create the VideoHdrFragment. All the view logic can be found in there
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (null == savedInstanceState) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, VideoHdrFragment.newInstance())
                    .commit();
        }
    }
}
