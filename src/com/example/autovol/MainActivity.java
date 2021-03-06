package com.example.autovol;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.autovol.ml.CurrentState;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.Schedule;
import edu.mit.media.funf.pipeline.BasicPipeline;
import edu.mit.media.funf.probe.builtin.BatteryProbe;
import edu.mit.media.funf.probe.builtin.BluetoothProbe;
import edu.mit.media.funf.probe.builtin.ProximitySensorProbe;
import edu.mit.media.funf.probe.builtin.SimpleLocationProbe;
import edu.mit.media.funf.probe.builtin.WifiProbe;

public class MainActivity extends Activity {
	// Local host
	//private static final String BASE_URL = "http://10.0.1.17:8080";
	
	// AWS host
	private static final String BASE_URL = "http://ec2-54-186-90-159.us-west-2.compute.amazonaws.com:8080";
	
	public static final String SMO_URL = BASE_URL + "/AutoVolWeb/SMOClassifyServlet";
	public static final String PIPELINE_NAME = "default";
	public static final int ARCHIVE_DELAY = 15 * 60;
	private FunfManager funfManager;
	private BasicPipeline pipeline;
	
	private SimpleLocationProbe locationProbe;
	private ActivityProbe activityProbe;
	private BluetoothProbe bluetoothProbe;
	private MyLightSensorProbe lightProbe;
	private WifiProbe wifiProbe;
	private ProximitySensorProbe proximityProbe;
	private BatteryProbe batteryProbe;
	private RingerVolumeProbe ringerProbe;

	private TextView k3Text, k7Text, rawK3Text, rawK7Text, locClusterText;
	private Button classifyButton, backgroundClassifyButton, historyButton;
	private ServiceConnection funfManagerConn = new ServiceConnection() {    
	    @Override
	    public void onServiceConnected(ComponentName name, IBinder service) {
	    	Log.d("MainActivity", "onServiceConnected");
	        funfManager = ((FunfManager.LocalBinder)service).getManager();
	        pipeline = new BasicPipeline();
	        funfManager.registerPipeline(PIPELINE_NAME, pipeline);
	        funfManager.enablePipeline(PIPELINE_NAME);
	        
	        
	        Gson gson = funfManager.getGson();
	        locationProbe = gson.fromJson(new JsonObject(), SimpleLocationProbe.class);
	        activityProbe = gson.fromJson(new JsonObject(), ActivityProbe.class);
	        //audioProbe = gson.fromJson(new JsonObject(), AudioProbe.class);
	        bluetoothProbe = gson.fromJson(new JsonObject(), BluetoothProbe.class);
	        lightProbe = gson.fromJson(new JsonObject(), MyLightSensorProbe.class);
	        wifiProbe = gson.fromJson(new JsonObject(), WifiProbe.class);
	        proximityProbe = gson.fromJson(new JsonObject(),ProximitySensorProbe.class);
	        batteryProbe = gson.fromJson(new JsonObject(), BatteryProbe.class);
	        ringerProbe = gson.fromJson(new JsonObject(), RingerVolumeProbe.class);
	        
	        funfManager.requestData(pipeline, locationProbe.getConfig());
	        activityProbe.registerPassiveListener(pipeline); //scheduling in probe
	        funfManager.requestData(pipeline, bluetoothProbe.getConfig());
	        
	        //TODO: see if this fixes
	        lightProbe.registerPassiveListener(pipeline);
	        //audioProbe.registerPassiveListener(pipeline);
	        
	        funfManager.requestData(pipeline, wifiProbe.getConfig());
	        funfManager.requestData(pipeline, proximityProbe.getConfig());
	        funfManager.requestData(pipeline, batteryProbe.getConfig());
	        ringerProbe.registerPassiveListener(pipeline);
	        
	        funfManager.registerPipelineAction(pipeline, BasicPipeline.ACTION_ARCHIVE, 
	        		new Schedule.BasicSchedule(new BigDecimal(ARCHIVE_DELAY), null, false, false));

	        CurrentState.get().enable(funfManager);
	        ClassifyAlarm.scheduleRepeatedAlarm(MainActivity.this);
	    }
	    
	    @Override
	    public void onServiceDisconnected(ComponentName name) {
	        funfManager = null;
	    }
	};
	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		classifyButton = (Button) findViewById(R.id.classify_button);
		classifyButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				remoteClassify();
			}
		});
		
		backgroundClassifyButton = (Button) findViewById(R.id.background_classify_button);
		backgroundClassifyButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent serviceIntent = new Intent(MainActivity.this, ClassifyService.class);
				startService(serviceIntent);
			}
		});
		
		historyButton = (Button) findViewById(R.id.history_button);
		historyButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(MainActivity.this, ResultsActivity.class);
				startActivity(intent);
			}
		});
		
		k3Text = (TextView) findViewById(R.id.k3_result_text);
		k7Text = (TextView) findViewById(R.id.k7_result_text);
		rawK3Text = (TextView) findViewById(R.id.raw_k3_result_text);
		rawK7Text = (TextView) findViewById(R.id.raw_k7_result_text);
		locClusterText = (TextView) findViewById(R.id.loc_cluster_text);
	    
	    bindService(new Intent(this, FunfManager.class), funfManagerConn, BIND_AUTO_CREATE);
	    
	    Log.d("MainActivity", "Play Services Connected: " + servicesConnected());
	}
	
	@Override
	protected void onDestroy() {
		CurrentState.get().disable(funfManager);
		funfManager.disablePipeline(PIPELINE_NAME);
		unbindService(funfManagerConn);
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
    private boolean servicesConnected() {
        // Check that Google Play services is available
        int resultCode =
                GooglePlayServicesUtil.
                        isGooglePlayServicesAvailable(this);
        // If Google Play services is available
        if (ConnectionResult.SUCCESS == resultCode) {
            // In debug mode, log the status
            Log.d("Activity Recognition",
                    "Google Play services is available.");
            // Continue
            return true;
        // Google Play services was not available for some reason
        } else {
            return false;
        }
    }
    
    private void remoteClassify() {
    	if (!CurrentState.get().dataIsReady()) {
    		Toast.makeText(this, "Data not ready yet", Toast.LENGTH_SHORT).show();
    		return;
    	}
    	String reqUrl = SMO_URL + "?" + CurrentState.get().requestParamString();
    	
    	new AsyncTask<String, Void, JSONObject>() {

			@Override
			protected JSONObject doInBackground(String... urls) {
				HttpURLConnection urlConnection = null;
			    try {
			    	URL url = new URL(urls[0]);
			    	urlConnection = (HttpURLConnection) url.openConnection();
			      InputStream in = new BufferedInputStream(urlConnection.getInputStream());
			      BufferedReader streamReader = new BufferedReader(new InputStreamReader(in, "UTF-8")); 
			      StringBuilder responseStrBuilder = new StringBuilder();

			      String inputStr;
			      while ((inputStr = streamReader.readLine()) != null)
			          responseStrBuilder.append(inputStr);
			      
			      JSONObject result = new JSONObject(responseStrBuilder.toString());
			      return result;
			    } catch (MalformedURLException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (JSONException e) {
					e.printStackTrace();
				} finally {
			    	if (urlConnection != null)
			    		urlConnection.disconnect();
			    }
				
			    return null;
			}
			
			@Override
			protected void onPostExecute(JSONObject result) {
				if (result != null) {
					try {
						k3Text.setText(result.getString("k3"));
						k7Text.setText(result.getString("k7"));
						
						rawK3Text.setText(result.getString("raw_k3"));
						rawK7Text.setText(result.getString("raw_k7"));
						locClusterText.setText(result.getString("loc"));
					} catch (JSONException e) {
						Log.d("MainActivity", "json exception");
						e.printStackTrace();
					}
					Toast.makeText(MainActivity.this, "New Suggestion: " + result.toString(), Toast.LENGTH_SHORT).show();
				}
			}
    		
		}.execute(reqUrl);

    }

}
