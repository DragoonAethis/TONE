package eu.dragonic.tone.streamer;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.SurfaceHolder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspServer;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.util.Locale;
import java.util.Objects;

// The primary RTSP server activity.
public class StreamingActivity extends AppCompatActivity implements Session.Callback, SurfaceHolder.Callback {
    private static final String TAG = "StreamingActivity"; // Logging tag.

    private boolean mVisible; // Is the UI visible?
    private SurfaceView mSurfaceView; // Stream preview itself.
    private TextView mStreamOverlayView; // Info overlay.

    private FrameLayout mContentView; // View holding the stream preview and overlay.
    private LinearLayout mControlsView; // View holding all the buttons.

    private ImageButton mSwitchCameraButton;
    private Button mToggleStreamButton;
    private ImageButton mSettingsButton;

    private boolean mStreaming; // Are we currently streaming?
    int mChosenCameraId = 0;

    private Intent RtspServerIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streaming);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        mVisible = true;
        mStreaming = false;

        mSurfaceView = findViewById(R.id.stream_surface_view);
        mStreamOverlayView = findViewById(R.id.stream_overlay_view);

        mContentView = findViewById(R.id.fullscreen_stream_content);
        mControlsView = findViewById(R.id.fullscreen_content_controls);

        mSwitchCameraButton = findViewById(R.id.switch_camera_button);
        mToggleStreamButton = findViewById(R.id.toggle_stream_button);
        mSettingsButton = findViewById(R.id.settings_button);

        if (!requestPermissions()) {
            mStreamOverlayView.setText(getString(R.string.requesting_permissions));
        }

        // Toggle the controls/system UI visibility on main content tap.
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) { toggleUiVisibility(); }
        });

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        //findViewById(R.id.settings_button).setOnClickListener();
        mSwitchCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStreaming) return; // Try not to sabotage ourselves mid-stream.
                mChosenCameraId = (mChosenCameraId + 1) % (Camera.getNumberOfCameras());
                Log.d(TAG, String.format("onClick: new camera: %d", mChosenCameraId));
                setSessionParameters();
            }
        });

        mToggleStreamButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStreaming) disableStreaming();
                else enableStreaming();
            }
        });

        mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStreaming) return;

                Intent settingsIntent = new Intent(StreamingActivity.this, SettingsActivity.class);
                startActivity(settingsIntent);
            }
        });
    }

    // Returns false if we're missing some required perms.
    private boolean requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 0);
            return false;
        } else return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] results) {
        switch (requestCode) {
        case 0: {
            // If request is cancelled, the result arrays are empty.
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                mStreamOverlayView.setText(getString(R.string.permissions_granted));
            } else {
                mStreamOverlayView.setText(getString(R.string.permissions_denied));
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(getString(R.string.permissions_denied))
                        .setMessage(getString(R.string.permissions_denied_description))
                        .setNeutralButton(getString(R.string.got_it), null)
                        .show();
            }
        } break;
        }
    }

    private void enableStreaming() {
        if (!requestPermissions()) {
            mStreamOverlayView.setText(getString(R.string.requesting_permissions));
            return;
        }

        mStreaming = true;
        setSessionParameters();

        RtspServerIntent = new Intent(this, RtspServer.class);
        startService(RtspServerIntent);

        // Reconfigure UI
        mToggleStreamButton.setText(R.string.stop_streaming_button);
        mSwitchCameraButton.setEnabled(false);
        mSwitchCameraButton.setAlpha(0.3f);
        mSettingsButton.setEnabled(false);
        mSettingsButton.setAlpha(0.3f);
    }

    private void disableStreaming() {
        mStreaming = false;
        stopService(RtspServerIntent);

        // Reconfigure UI
        mToggleStreamButton.setText(R.string.stream_button);
        mSwitchCameraButton.setEnabled(true);
        mSwitchCameraButton.setAlpha(1.0f);
        mSettingsButton.setEnabled(true);
        mSettingsButton.setAlpha(1.0f);
    }

    // Set all the session streaming params from the shared settings.
    private void setSessionParameters() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int videoWidth = 1280, videoHeight = 720, videoFramerate = 30, videoBitrate = 500000;
        int audioBitrate = 160000, audioFreq = 48000;

        try {
            String videoResolutionString = prefs.getString("video_resolution", getString(R.string.pref_default_video_resolution));
            String[] videoResolution = videoResolutionString.split("x");

            if (videoResolution.length != 2) {
                throw new Exception("Invalid resolution string - expected WxH, got: " + videoResolutionString);
            }

            videoWidth = Integer.parseInt(videoResolution[0]);
            videoHeight = Integer.parseInt(videoResolution[1]);
        } catch (Exception e) { /* TODO: Handle invalid resolutions, fill resolution list properly, etc.*/ }

        try {
            // TODO: Handle framerate switching too.
            videoBitrate = Integer.parseInt(Objects.requireNonNull(prefs.getString("video_bitrate", getString(R.string.pref_default_video_bitrate))));
            audioBitrate = Integer.parseInt(Objects.requireNonNull(prefs.getString("audio_bitrate", getString(R.string.pref_default_audio_bitrate))));
            audioFreq = Integer.parseInt(Objects.requireNonNull(prefs.getString("audio_frequency", getString(R.string.pref_default_audio_frequency))));
        } catch (Exception e) { /* Eh, we've got the defaults up there. They're all const values, anyway.*/ }

        SessionBuilder.getInstance()
                .setCamera(mChosenCameraId)
                .setCallback(this)
                .setSurfaceView(mSurfaceView)
                .setPreviewOrientation(0) // Landscape
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_AAC)
                .setAudioQuality(new AudioQuality(audioFreq, audioBitrate))
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setVideoQuality(new VideoQuality(videoWidth, videoHeight, videoFramerate, videoBitrate));
    }

    private void toggleUiVisibility() {
        if (mVisible) { // Hide stuff.
            mVisible = false;
            mControlsView.setVisibility(View.GONE);
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            //startLockTask(); // Disable hardware buttons and navigation
        } else { // Show all the things!
            mVisible = true;
            mControlsView.setVisibility(View.VISIBLE);
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

            //stopLockTask(); // Enable hardware buttons again
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}

    @Override
    public void onBitrateUpdate(long bitrate) {
        mStreamOverlayView.setText(String.format(Locale.ENGLISH, "%d Kbps", bitrate / 1024));
    }

    @Override
    public void onSessionError(int reason, int streamType, Exception e) {
        mStreamOverlayView.setText(String.format(getString(R.string.stream_error), e.getMessage()));
        disableStreaming();
    }

    @Override public void onPreviewStarted() {}
    @Override public void onSessionConfigured() {}

    @Override
    public void onSessionStarted() {
        mStreamOverlayView.setText(getString(R.string.stream_connected));
    }

    @Override
    public void onSessionStopped() {
        mStreamOverlayView.setText(getString(R.string.stream_disconnected));
    }
}
