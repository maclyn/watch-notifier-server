package com.hckthn.watchoutserver;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class MainActivity extends Activity {
	public static final String TAG = "MainActivity";
	public static final int CONTACT_REQUEST = 5000;
	
	public static final String MESSAGE_PREF = "msg_pref";
	public static final String OK_PREF = "ok_pref";
	public static final String ALERT_PREF = "alert_pref";
	public static final String CONTACT_BASE = "contact_names";
	public static final String CONTACT_NUMBER_BASE = "contact_numbers";
	
	SharedPreferences pref;
	
	Button startService;
	
	boolean isStarted = false;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		pref = PreferenceManager.getDefaultSharedPreferences(this);
		
		startService = (Button) this.findViewById(R.id.startService);
		startService.setOnClickListener(new OnClickListener(){
			@Override
			public void onClick(View v) {
				Intent watchLink = new Intent(MainActivity.this, WatchLink.class);
				Intent nl = new Intent(MainActivity.this, NotificationPusher.class);
				if(!isStarted){
					MainActivity.this.startService(watchLink);
					MainActivity.this.startService(nl);
					startService.setText("Stop Service");
					isStarted = true;
				} else {
					MainActivity.this.stopService(watchLink);
					startService.setText("Start Service");
					isStarted = false;
				}
			}
		});
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
	}
	
	@Override
	public void onPause(){
		super.onPause();
	}
	
	@Override
	public void onResume(){		
		super.onResume();
	}

	private void log(String text){
		Log.d(TAG, text);
	}
}
