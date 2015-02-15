package com.hckthn.watchoutserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

public class WatchLink extends Service {
	static final String TAG = "WatchLink";
	
	public static final String EOS = "<!EOS!>";

	public static final String DISMISS_INTENT = "com.hkthn.watchoutserver.dismiss";
	public static final String ACTION_INTENT = "com.hkthn.watchoutserver.action";
	public static final String NOTIFICATION_INTENT = "com.hkthn.watchoutserver.notification";
	public static final String NOTIFICATION_R_INTENT = "com.hkthn.watchoutserver.notificationremoved";
	public static final String M_CLEARED = "com.hkthn.watchoutserver.mclr";
	public static final String M_UPDATED = "com.hkthn.watchoutserver.mupdate";
	public static final String PLAYBACK_INTENT = "com.hkthn.watchoutserver.playback";
	
	public static final int NOTIFICATION_ID = 300;
	public static final String UUID = "7bcc1440-858a-11e3-baa7-0800200c9a66"; 
		
	Handler h = new Handler(){
		@Override
		public void handleMessage(Message msg) {
			//Object always going to be a string
			log("Handler received message");
			String s = (String) msg.obj;
			handleInput(s);
		}
	};
	
	SharedPreferences prefs;
	BroadcastReceiver br;
	
	BluetoothAdapter ba;
	LocationManager lm;

	MediaRecorder mr;
	SimpleDateFormat fileNameDate;
	SimpleDateFormat locationSdf;
	String saveLocation;
	
	ConnectThread ct;
	
	IOThread io;
	
	boolean hasConnected = false;
	
	@Override
	public void onCreate(){
		super.onCreate();
		log("On create");
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		br = new BroadcastReceiver(){
			@Override
			public void onReceive(Context context, Intent intent) {
				log("Recieved new intent in BR");
				if(intent.getAction().equals(NOTIFICATION_INTENT)){
					log("Got notification request");
					//Send to phone if possible
					if(io != null){
						io.write(intent.getStringExtra("toSend"));
					}
				} else if (intent.getAction().equals(NOTIFICATION_R_INTENT)){
					//Send to phone if possible
					if(io != null){
						io.write(intent.getStringExtra("toSend"));
					}
				} else if (intent.getAction().equals(M_UPDATED)){
					if(io != null){
						io.write(intent.getStringExtra("data"));
					}
				} else if (intent.getAction().equals(M_CLEARED)){
					if(io != null){
						io.write(intent.getStringExtra("data"));
					}
				}
			}
		};
		IntentFilter intf = new IntentFilter();
		intf.addAction(NOTIFICATION_INTENT);
		intf.addAction(NOTIFICATION_R_INTENT);
		intf.addAction(M_UPDATED);
		intf.addAction(M_CLEARED);
		LocalBroadcastManager.getInstance(this).registerReceiver(br, intf);
		
		ba = BluetoothAdapter.getDefaultAdapter();
		lm = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
		
		if(!ba.isEnabled()){
			Toast.makeText(this, "Enabling Bluetooth to pair", Toast.LENGTH_LONG).show();
			ba.enable();
		}
		
		updateNotification("Watch link waiting", false);
		
		attemptToConnect();
	}
	
	public void updateNotification(String text, boolean shouldRestart){
		Intent startSettings = new Intent(this, MainActivity.class);
		startSettings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pi = PendingIntent.getActivity(this, -1, startSettings, 0);
		
		NotificationCompat.Builder n = new NotificationCompat.Builder(this)
			.setAutoCancel(false)
			.setOngoing(true)
			.setContentTitle("Phone notification server running")
			.setContentText(text)
			.setSmallIcon(R.drawable.ic_stat_clock)
			.setContentIntent(pi)
			.setPriority(Notification.PRIORITY_MIN);
		this.startForeground(NOTIFICATION_ID, n.build());
		
		if(shouldRestart){
			this.stopSelf();
			this.startService(new Intent(this, WatchLink.class));
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
		log("On destroy");
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
	    return START_STICKY;
	}
	
	private void log(String text){
		Log.d(TAG, text);
	}
	
	private void handleSocketConnection(BluetoothSocket socket){
		log("New socket to watch found");
		updateNotification("Watch link connected", false);
		io = new IOThread(socket, this);
		io.run();
	}
	
	private void handleInput(String dataIn){
		log("Data in: " + dataIn);
		dataIn = (String) dataIn.subSequence(0, dataIn.indexOf(EOS));
		int barPos = dataIn.indexOf("|");
		if(barPos != -1){
			String requestType = dataIn.substring(0, barPos);
			String requestData = dataIn.substring(barPos+1);
			log("Request type: " + requestType);
			log("Request data: " + requestData);
			
			if (requestType.equals("DISMISS")){
				log("Trying to dismiss");
				String[] allData = requestData.split("\\|");
				Intent i = new Intent();
				i.setAction(DISMISS_INTENT);
				i.putExtra("pkg", allData[0]);
				i.putExtra("tag", allData[1]);
				i.putExtra("id", allData[2]);
				LocalBroadcastManager.getInstance(this).sendBroadcast(i);
			} else if (requestType.equals("PLAYBACK")){
				log("Trying to playback");
				Intent i = new Intent();
				i.setAction(PLAYBACK_INTENT);
				i.putExtra("choice", requestData);
				LocalBroadcastManager.getInstance(this).sendBroadcast(i);
			} else if (requestType.equals("ACTION")){
				log("Trying to take action");
				Intent i = new Intent();
				i.setAction(ACTION_INTENT);
				String[] allData = requestData.split("\\|");
				i.putExtra("pkg", allData[0]);
				i.putExtra("id", allData[1]);
				i.putExtra("which", Integer.parseInt(allData[2]));
				LocalBroadcastManager.getInstance(this).sendBroadcast(i);
			}
		} else {
			log("Error! Improper formatting");
		}
	}
	
	private void attemptToConnect(){
		ct = new ConnectThread();
		ct.start();
	}
	
	private class IOThread extends Thread {
		private final BluetoothSocket bs;
		private final InputStream is;
		private final OutputStream os;
		
		private Context ctx;
		
		public IOThread(BluetoothSocket socket, Context ctx){
			log("IOThread created");
			bs = socket;
			InputStream in = null;
			OutputStream out = null;
			this.ctx = ctx;
			
			try {
				in = bs.getInputStream();
				out = bs.getOutputStream();
			} catch (IOException e) {}
			is = in;
			os = out;
		}
		
		public void run(){
			log("Running IOThread...");
			StringBuilder stringToSend = new StringBuilder();
			byte[] readBuffer = new byte[1024];
			int newBytes;
			
			while(true){
				try {
					while((newBytes = is.read(readBuffer)) != -1){ //So long as we're not at the end of stream
						stringToSend.append(new String(readBuffer, 0, newBytes, Charset.defaultCharset()));
						int eosIndex = stringToSend.indexOf(EOS);
						if(eosIndex != -1){
							String toSend = stringToSend.toString();
							Message m = h.obtainMessage(1, toSend);
							h.sendMessage(m);
							stringToSend = new StringBuilder();
						}
					}
				} catch (Exception e) {
					log("Exception:" + e.getMessage());
					log("ETOSTring: " + e.toString());
					log("IOThread done; connection lost");
					this.cancel();
					updateNotification("Watch link lost", true);
					break; //done -- connection lost					
				}
			}
			
			this.cancel();
		}
		
		public void write(String dataIn){
			log("Writing bytes to output streams");
			dataIn = dataIn + EOS;
			try {
				byte[] dataBytes = dataIn.getBytes();
				os.write(dataBytes);
			} catch (Exception e) {}
		}
		
		public void cancel(){
			log("Cancelling IOThread...");
			try {
				bs.close();
				((Service)ctx).stopSelf();
				ctx.startService(new Intent(ctx, WatchLink.class));
			} catch (IOException e) {}
		}
	}
	
	private class ConnectThread extends Thread {
		private final BluetoothServerSocket server;
		
		public ConnectThread(){
			BluetoothServerSocket tmp = null;
			try {
				log("Trying to listen...");
				tmp = ba.listenUsingRfcommWithServiceRecord("WatchLink", java.util.UUID.fromString(UUID));
				log("Listening worked");
			} catch (Exception e){
				log("Listening failed: " + e.getLocalizedMessage());
				updateNotification("Watch link disconnected", true);
			}	
			
			server = tmp;
		}
		
		public void run(){
			BluetoothSocket socket = null;
			boolean notAccepted = true;
			while(notAccepted){
				try {
					log("Waiting to accept connection...");
					socket = server.accept();
					log("Server accepted");
				} catch (Exception e){
					break;
				}
				
				if(socket != null){
					handleSocketConnection(socket);
					hasConnected = true;
					log("Has connected");
					try {
						server.close();
					} catch (Exception e) {
						log("Unable to close " + e.getMessage());
					}
					notAccepted = false;
				}
			}
		}
		
		public void cancel(){
			try {
				server.close();
			} catch (Exception e) {
				log("Unable to close " + e.getMessage());
			}
		}
	}
}
