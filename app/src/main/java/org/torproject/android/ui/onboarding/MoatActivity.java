/* Copyright (c) 2020, Benjamin Erhart, Orbot / The Guardian Project - https://guardianproject.info */
/* See LICENSE for licensing information */
package org.torproject.android.ui.onboarding;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.torproject.android.R;
import org.torproject.android.service.OrbotService;
import org.torproject.android.service.TorServiceConstants;
import org.torproject.android.service.util.Prefs;

/**
 Implements the MOAT protocol: Fetches OBFS4 bridges via Meek Azure.

 The bare minimum of the communication is implemented. E.g. no check, if OBFS4 is possible or which
 protocol version the server wants to speak. The first should be always good, as OBFS4 is the most widely
 supported bridge type, the latter should be the same as we requested (0.1.0) anyway.

 API description:
 https://github.com/NullHypothesis/bridgedb#accessing-the-moat-interface
 */
public class MoatActivity extends AppCompatActivity implements View.OnClickListener, TextView.OnEditorActionListener {

    private static String moatBaseUrl = "https://bridges.torproject.org/moat";

    private ImageView mCaptchaIv;
    private ProgressBar mProgressBar;
    private EditText mSolutionEt;
    private Button mRequestBt;

    private String mChallenge;

    private RequestQueue mQueue;

    private String mTorStatus;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String host = intent.getStringExtra(OrbotService.EXTRA_SOCKS_PROXY_HOST);
            int port = intent.getIntExtra(OrbotService.EXTRA_SOCKS_PROXY_PORT,-1);
            String status = intent.getStringExtra(TorServiceConstants.EXTRA_STATUS);

            if (TextUtils.isEmpty(host)) {
                host = TorServiceConstants.IP_LOCALHOST;
            }

            if (port < 1) {
                port = Integer.parseInt(TorServiceConstants.SOCKS_PROXY_PORT_DEFAULT);
            }

            if (TextUtils.isEmpty(status)) {
                status = TorServiceConstants.STATUS_OFF;
            }

            setUp(host, port, status);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moat);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        setTitle(getString(R.string.request_bridges));

        mCaptchaIv = findViewById(R.id.captchaIv);
        mProgressBar = findViewById(R.id.progressBar);
        mSolutionEt = findViewById(R.id.solutionEt);
        mRequestBt = findViewById(R.id.requestBt);

        mCaptchaIv.setVisibility(View.GONE);
        mSolutionEt.setOnEditorActionListener(this);
        mRequestBt.setEnabled(false);
        mRequestBt.setOnClickListener(this);

        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver,
                new IntentFilter(TorServiceConstants.ACTION_STATUS));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.moat, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_refresh)
                .setEnabled(TorServiceConstants.STATUS_ON.equals(mTorStatus));

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onResume() {
        super.onResume();

        sendIntentToService(TorServiceConstants.ACTION_STATUS);
    }

    @Override
    public void onClick(View view) {
        Log.d(MoatActivity.class.getSimpleName(), "Request Bridge!");

        requestBridges(mSolutionEt.getText().toString());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_refresh:
                fetchCaptcha();

                return true;

            case android.R.id.home:
                finish();

                return true;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
        Log.d(MoatActivity.class.getSimpleName(), "Editor Action: actionId=" + actionId + ", IME_ACTION_GO=" + EditorInfo.IME_ACTION_GO);

        if (keyEvent != null && keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(textView.getWindowToken(), 0);
                }

                onClick(mSolutionEt);
            }

            return true;
        }

        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
    }

    private void fetchCaptcha() {
        mProgressBar.setVisibility(View.VISIBLE);
        mCaptchaIv.setVisibility(View.GONE);
        mRequestBt.setEnabled(false);

        JsonObjectRequest request = buildRequest("fetch",
                "\"type\": \"client-transports\", \"supported\": [\"obfs4\"]",
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        try {
                            JSONObject data = response.getJSONArray("data").getJSONObject(0);
                            mChallenge = data.getString("challenge");

                            byte[] image = Base64.decode(data.getString("image"), Base64.DEFAULT);
                            mCaptchaIv.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.length));

                            mProgressBar.setVisibility(View.GONE);
                            mCaptchaIv.setVisibility(View.VISIBLE);
                            mRequestBt.setEnabled(true);

                        } catch (JSONException e) {
                            Log.d(MoatActivity.class.getSimpleName(), "Error decoding answer: " + response.toString());

                            displayError(e, response);
                        }
                    }
                });

        if (request != null) {
            mQueue.add(request);
        }
    }

    private void requestBridges(String solution) {
        mProgressBar.setVisibility(View.VISIBLE);
        mRequestBt.setEnabled(false);

        JsonObjectRequest request = buildRequest("check",
                "\"id\": \"2\", \"type\": \"moat-solution\", \"transport\": \"obfs4\", \"challenge\": \""
                        + mChallenge + "\", \"solution\": \"" + solution + "\", \"qrcode\": \"false\"",
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        try {
                            JSONArray bridges = response.getJSONArray("data").getJSONObject(0).getJSONArray("bridges");

                            Log.d(MoatActivity.class.getSimpleName(), "Bridges: " + bridges.toString());

                            StringBuilder sb = new StringBuilder();

                            for (int i = 0; i < bridges.length(); i++) {
                                sb.append(bridges.getString(i)).append("\n");
                            }

                            Prefs.setBridgesList(sb.toString());
                            Prefs.putBridgesEnabled(true);

                            mProgressBar.setVisibility(View.GONE);

                            MoatActivity.this.setResult(RESULT_OK);
                            MoatActivity.this.finish();
                        }
                        catch (JSONException e) {
                            Log.d(MoatActivity.class.getSimpleName(), "Error decoding answer: " + response.toString());

                            displayError(e, response);
                        }
                    }
                });

        if (request != null) {
            mQueue.add(request);
        }
    }

    private JsonObjectRequest buildRequest(String endpoint, String payload, Response.Listener<JSONObject> listener) {
        JSONObject requestBody;

        try {
            requestBody = new JSONObject("{\"data\": [{\"version\": \"0.1.0\", " + payload + "}]}");
        } catch (JSONException e) {
            return null;
        }

        Log.d(MoatActivity.class.getSimpleName(), "Request: " + requestBody.toString());

        return new JsonObjectRequest(
                Request.Method.POST,
                moatBaseUrl + "/" + endpoint,
                requestBody,
                listener,
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d(MoatActivity.class.getSimpleName(), "Error response.");

                        displayError(error, null);
                    }
                }
        ) {
            public String getBodyContentType() {
                return "application/vnd.api+json";
            }
        };
    }

    private void sendIntentToService(final String action) {
        Intent intent = new Intent(this, OrbotService.class);
        intent.setAction(action);
        startService(intent);
    }

    private void setUp(String host, int port, String status) {
        Log.d(MoatActivity.class.getSimpleName(), "Tor status=" + status);

        mTorStatus = status;
        invalidateOptionsMenu();

        switch (status) {
            case TorServiceConstants.STATUS_OFF:
                sendIntentToService(TorServiceConstants.ACTION_START);

                break;

            case TorServiceConstants.STATUS_ON:
                Prefs.setBridgesList("moat");
                Prefs.putBridgesEnabled(true);

                Log.d(MoatActivity.class.getSimpleName(), "Set up Volley queue. host=" + host + ", port=" + port);

                mQueue = Volley.newRequestQueue(MoatActivity.this, new ProxiedHurlStack(host, port));

                sendIntentToService(TorServiceConstants.CMD_SIGNAL_HUP);

                fetchCaptcha();

                break;

            default:
                sendIntentToService(TorServiceConstants.ACTION_STATUS);
        }
    }

    private void displayError(Exception exception, JSONObject response) {

        String detail = null;

        // Try to decode potential error response.
        if (response != null) {
            try {
                detail = response.getJSONArray("errors").getJSONObject(0).getString("detail");
            } catch (JSONException e2) {
                // Ignore. Show first exception instead.
            }
        }

        mProgressBar.setVisibility(View.GONE);
        mRequestBt.setEnabled(mCaptchaIv.getVisibility() == View.VISIBLE);

        new AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(TextUtils.isEmpty(detail) ? exception.getLocalizedMessage() : detail)
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
