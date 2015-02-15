package com.hckthn.watchoutserver;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteController;
import android.media.RemoteController.MetadataEditor;
import android.media.ThumbnailUtils;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

public class NotificationPusher extends NotificationListenerService implements android.media.RemoteController.OnClientUpdateListener {
	public static final String NOTIFICATION_SEND = "com.hckthn.watchoutserver.NOTFICATION_SEND";
	public static final String TAG = "NotificationPusher";
	
	BroadcastReceiver br;
	
	SimpleDateFormat process; 
	
	SharedPreferences reader;
	SharedPreferences.Editor writer;
	
	Hashtable<String, List<PendingIntent>> actionIntents;
	
	PackageManager pm;
	
	AudioManager am;
	RemoteController rc;
	
	@Override
	public void onCreate(){
		super.onCreate();
		log("On create");
		pm = this.getPackageManager();
		
		process = new SimpleDateFormat("h:mm a", Locale.US);
		
		reader = PreferenceManager.getDefaultSharedPreferences(this);
		writer = reader.edit();
		
		rc = new RemoteController(this, this);
		rc.setArtworkConfiguration(400, 400);
		
		am = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
		am.registerRemoteController(rc);
		
		actionIntents = new Hashtable<String, List<PendingIntent>>();
		
		br = new BroadcastReceiver(){
			@Override
			public void onReceive(Context context, Intent intent) {
				if(intent.getAction().equals(WatchLink.DISMISS_INTENT)){
					log("Got dismiss intent; trying to dismiss");
					String pkg = intent.getStringExtra("pkg");
					String tag = intent.getStringExtra("tag");
					int id = Integer.parseInt(intent.getStringExtra("id"));
					log("Pkg: " + pkg + " tag: " + tag + " id: " + id);
					if(tag.equals("null")) tag = null;
					NotificationPusher.this.cancelNotification(pkg, tag, id);
				} else if (intent.getAction().equals(WatchLink.PLAYBACK_INTENT)){
					String data = intent.getStringExtra("choice");
					if(data.equals("0")){
						KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
						KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
						rc.sendMediaKeyEvent(down);
						rc.sendMediaKeyEvent(up);
					} else if (data.equals("1")){
						KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT);
						KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT);
						rc.sendMediaKeyEvent(down);
						rc.sendMediaKeyEvent(up); 
					} else if (data.equals("vup")) {
						am.adjustVolume(AudioManager.ADJUST_RAISE, 0);
					} else if (data.equals("vdn")) {
						am.adjustVolume(AudioManager.ADJUST_LOWER, 0);
					} else {
						KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
						KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
						rc.sendMediaKeyEvent(down);
						rc.sendMediaKeyEvent(up);
					}
				} else if (intent.getAction().equals(WatchLink.ACTION_INTENT)){
					String packageName = intent.getStringExtra("pkg");
					int which = intent.getIntExtra("which", 0);
					String id = intent.getStringExtra("id");
					
					log("Pkg: " + packageName);
					log("Id: " + id);
					log("Which: " + which);
					
					try {
						actionIntents.get(packageName + id).get(which).send();
						log("Send successful");
					} catch (Exception e) {
						log("Message: " + e.getMessage());
						log("Failed to do action");
					}
				}
			}			
		};
		IntentFilter dismissFilter = new IntentFilter();
		dismissFilter.addAction(WatchLink.DISMISS_INTENT);
		dismissFilter.addAction(WatchLink.PLAYBACK_INTENT);
		dismissFilter.addAction(WatchLink.ACTION_INTENT);
		LocalBroadcastManager.getInstance(this).registerReceiver(br, dismissFilter);
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
		log("On destroy");
	}

	@Override
	public void onNotificationPosted(StatusBarNotification sbn) {
		if(sbn.isOngoing()) return; //Don't want to capture these 
		
		String title = sbn.getNotification().extras.getString(Notification.EXTRA_TITLE);
		String text = sbn.getNotification().extras.getString(Notification.EXTRA_TEXT);
		String infoText = sbn.getNotification().extras.getString(Notification.EXTRA_INFO_TEXT);
		String subText = sbn.getNotification().extras.getString(Notification.EXTRA_SUB_TEXT);
		String summaryText = sbn.getNotification().extras.getString(Notification.EXTRA_SUMMARY_TEXT);
		log("Title: " + title);
		log("Text: " + text);
		log("InfoText: " + infoText);
		log("SubText: " + subText);
		log("SummaryText: " + summaryText);
		
		//Save data to be sent via intent
		String string1 = "" ;
		String string2 = "";
		if(!reader.getBoolean(sbn.getPackageName() + "_enabled_alt", false)){
			if(title != null && text != null){ //Standard notification; proccess like normal
				log("Standard notification processing");
				string1 = title;
				string2 = text;
			} else {
				//Handle, assuming the presence of a title indicates whether we have a "readable" notification
				log("Alernate notification processing");
				if(title != null){
					log("Title found");
					string1 = title;
					if(infoText != null && !tryParse(infoText)){
						log("Using infoText");
						string2 = infoText;
					} else if (subText != null && !tryParse(infoText)){
						log("Using subText");
						string2 = subText; 
					} else if (summaryText != null && !tryParse(summaryText)){
						log("Using summaryText");
						string2 = summaryText;
					} else {
						log("Using reflection for string2");
						//Try to get it out via hacky reflection
						List<String> remainders = extractText(sbn.getNotification().contentView);
						String fallBack = "";
						if(remainders.size() > 0){
							for(String s : remainders){
								if(!s.equals(string1)){
									if(tryParse(s)){
										fallBack = s;
									} else {
										string2 = s;
										 break;
									}
								}
							}
						} else {
							if(!fallBack.isEmpty()){
								string2 = fallBack;
							} else {
								string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
							}
						}
					}
				} else {
					log("Missing title; applying reflection");
					//Hacky reflection time~
					List<String> remainders = extractText(sbn.getNotification().contentView);
					if(remainders.size() >= 2){ //Best we're going to get; just apply it
						if(tryParse(remainders.get(0))){ //Make sure date is at least below
							string1 = remainders.get(1);
							string2 = remainders.get(0);
						} else {
							string1 = remainders.get(0);
							string2 = remainders.get(1);
						}
					} else if (remainders.size() == 1) {
						if(tryParse(remainders.get(0))){ //We just have the date; treat like below
							String appName = sbn.getPackageName();
							try {
								appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(appName, 0));
							} catch (Exception e) {
							}
							string1 = appName; //Set to appname
							string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
						} else {
							string1 = remainders.get(0);
							string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
						}
					} else {
						String appName = sbn.getPackageName();
						try {
							appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(appName, 0));
						} catch (Exception e) {
						}
						string1 = appName; //Set to appname
						string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
					}
				}
			}
		} else {
			log("Intentionally using alternative processing");
			
			List<String> remainders = extractText(sbn.getNotification().contentView);
			if(remainders.size() >= 2){ //Best we're going to get; just apply it
				if(tryParse(remainders.get(0))){ //Make sure date is at least below
					string1 = remainders.get(1);
					string2 = remainders.get(0);
				} else {
					string1 = remainders.get(0);
					string2 = remainders.get(1);
				}
			} else if (remainders.size() == 1) {
				if(tryParse(remainders.get(0))){ //We just have the date; treat like below
					String appName = sbn.getPackageName();
					try {
						appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(appName, 0));
					} catch (Exception e) {
					}
					string1 = appName; //Set to appname
					string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
				} else {
					string1 = remainders.get(0);
					string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
				}
			} else {
				String appName = sbn.getPackageName();
				try {
					appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(appName, 0));
				} catch (Exception e) {
				}
				string1 = appName; //Set to appname
				string2 = process.format(new Date(sbn.getNotification().when)); //Set to date
			}
		}
		
		String toSend = "NOTIFICATION|" + string1 + "|" + string2 + "|" + sbn.getPackageName() + "|" + sbn.getTag() + "|" + sbn.getId() + "|";
		log("To send: " + toSend);
		
		Bitmap tinyBitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.ic_launcher);
		try {
			tinyBitmap = BitmapFactory.decodeResource(pm.getResourcesForApplication(sbn.getPackageName()), sbn.getNotification().icon);
		} catch (Exception e) {
		}
		tinyBitmap = Bitmap.createScaledBitmap(tinyBitmap, 32, 32, true);
		ByteArrayOutputStream os = new ByteArrayOutputStream(); 
		tinyBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, (OutputStream) os); 
		byte[] b = os.toByteArray();
		String imageText = Base64.encodeToString(b, Base64.DEFAULT);
		toSend += imageText;
		
		//Add in app name
		String appName = sbn.getPackageName();
		try {
			appName = (String) pm.getApplicationLabel(pm.getApplicationInfo(appName, 0));
		} catch (Exception e) {
		}
		toSend += "|" + appName;
		
		//Add in actions
		if(sbn.getNotification().actions != null){
			int action_num = sbn.getNotification().actions.length;
			toSend += "|" + action_num;
			
			String toSendBefore = toSend;
			
			try {
				if(action_num > 0){
					toSend += "|";
					List<PendingIntent> actions = new ArrayList<PendingIntent>();
					
					for(int i = 0; i < action_num; i++){
						toSend += sbn.getNotification().actions[i].title + "|";
						
						Bitmap icoBmp = BitmapFactory.decodeResource(this.getResources(), R.drawable.ic_launcher);
						try {
							icoBmp = BitmapFactory.decodeResource(pm.getResourcesForApplication(sbn.getPackageName()), sbn.getNotification().actions[i].icon);
						} catch (Exception e) {
						}
						icoBmp = Bitmap.createScaledBitmap(icoBmp, 32, 32, true);
						ByteArrayOutputStream icoOs = new ByteArrayOutputStream(); 
						icoBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, (OutputStream) icoOs); 
						byte[] icoB = icoOs.toByteArray();
						String icoText = Base64.encodeToString(icoB, Base64.DEFAULT);
						toSend += icoText;
						
						if(i != action_num - 1) toSend += "|";
						
						actions.add(sbn.getNotification().actions[i].actionIntent);
					}
					
					actionIntents.put(sbn.getPackageName() + sbn.getId(), actions);
				} 
			} catch (Exception e) {
				log("Error: " + e.getMessage());
				toSend = toSendBefore;
			}
		} else {
			toSend += "|0";
		}
		
		Intent i = new Intent();
		i.setAction(WatchLink.NOTIFICATION_INTENT);
		i.putExtra("toSend", toSend);
		LocalBroadcastManager.getInstance(this).sendBroadcast(i);
		
		log("Sent notification");
	}

	@Override
	public void onNotificationRemoved(StatusBarNotification sbn) {
		log("Removed notification");
		
		String toSend = "NOTIFICATION_REMOVED|" + sbn.getPackageName() + "|" + sbn.getTag() + "|" + sbn.getId();
		
		Intent i = new Intent();
		i.setAction(WatchLink.NOTIFICATION_R_INTENT);
		i.putExtra("toSend", toSend);
		LocalBroadcastManager.getInstance(this).sendBroadcast(i);
		log("Sent notification removal");
	}
	
	private void log(String text){
		Log.d(TAG, text);
	}
	
	private List<String> extractText(RemoteViews content){
		LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		ViewGroup localView = null;
		
		try{
			localView = (ViewGroup) inflater.inflate(content.getLayoutId(), null);
			content.reapply(getApplicationContext(), localView);
		} catch(Exception e) {
			//We can't get it, for whatever reason -- certain apps (e.g. Apollo) don't allow this
		}

		//Try to extract the data
		List<String> notificationTexts = new ArrayList<String>();

		if(localView != null){	
			for(int i = 0; i <= localView.getChildCount(); i++) {
				StoredContentData elementData = extractData(localView, localView.getChildAt(i), new StoredContentData(new ArrayList<String>(), -1f));
				notificationTexts.addAll(elementData.textViews);
			}
		} else {
			//Try the alternate, hard-coded fallback for reading notification text contained here:
			//http://stackoverflow.com/questions/9292032/extract-notification-text-from-parcelable-contentview-or-contentintent
			try {
				@SuppressWarnings("rawtypes")
				Class viewClass = content.getClass();
				
				List<String> values = new ArrayList<String>();
				Field allFields[] = viewClass.getDeclaredFields();
				for(int i = 0; i < allFields.length; i++) {
					if(!allFields[i].getName().equals("mActions")) continue;
					
					allFields[i].setAccessible(true);
					
					@SuppressWarnings("unchecked")
					ArrayList<Object> actions = (ArrayList<Object>) allFields[i].get(content);
					for(Object object : actions) {
						Field innerFields[] = object.getClass().getDeclaredFields();
						
						Object val = null;
						@SuppressWarnings("unused")
						Integer type = null;
						@SuppressWarnings("unused")
						Integer vId = null;
						for(Field field : innerFields){
							field.setAccessible(true);
							
							if(field.getName().equals("value")){
								val = field.get(object);
							} else if(field.getName().equals("type")) {
								type = field.getInt(object);
							} else if(field.getName().equals("viewId")){
								vId = field.getInt(object);
							}
						}
						
						if(val != null){
							values.add(val.toString());
						}
					}
				}
				
				for(int i = 0; i < values.size(); i++){
					notificationTexts.add(values.get(i));
				}
			} catch(Exception e){
			}
		}
		
		return notificationTexts;
	}
	
	private StoredContentData extractData(View parentView, View subView, StoredContentData scd){
		if(subView instanceof LinearLayout) {
			try {
				LinearLayout ll = (LinearLayout) subView;
				for(int i = 0; i < ll.getChildCount(); i++) {
					scd = extractData(parentView, ll.getChildAt(i), scd);
				}
			} catch(Exception e) {	
			}
		} else if(subView instanceof RelativeLayout) {
			try {
				RelativeLayout rl = (RelativeLayout) subView;
				for(int j = 0; j < rl.getChildCount(); j++) {
					scd = extractData(parentView, rl.getChildAt(j), scd);
				}
			} catch(Exception e) {
			}
		} else if(subView instanceof TextView) {
			try {
				TextView tv = (TextView) subView;
				String textViewText = tv.getText().toString();
				if(textViewText.length() > 0) {
					scd.textViews.add(textViewText);
				}
			} catch(Exception e) {
			}
		} 
		return scd;
	}
	
	class StoredContentData {
		List<String> textViews;
		
		public StoredContentData(List<String> textViewsIn, float percentIn) {
			textViews = textViewsIn;
		}
	}

	private boolean tryParse(String s){
		try {
			process.parse(s);
			return true;
		} catch (Exception e){
			return false;
		}
	}
	
	@Override
	public void onClientChange(boolean clearing) {
		//If clearing, clear entire screen
		if(clearing){
			Intent clearScreen = new Intent();
			clearScreen.setAction(WatchLink.M_CLEARED);
			clearScreen.putExtra("data", "MEDIA_CLEAR|Media cleared.");
			LocalBroadcastManager.getInstance(this).sendBroadcast(clearScreen);
		}
	}

	@Override
	public void onClientMetadataUpdate(MetadataEditor metadataEditor) {
		log("Media update");
		Bitmap toSend = metadataEditor.getBitmap(MetadataEditor.BITMAP_KEY_ARTWORK, null);
		String artist = metadataEditor.getString(MediaMetadataRetriever.METADATA_KEY_ARTIST, "Unknown Artist");
		String album = metadataEditor.getString(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Unknown Album");
		String song = metadataEditor.getString(MediaMetadataRetriever.METADATA_KEY_TITLE, "Unknown Track");
		
		if(artist.equals("null")){
			artist = "Unknown Artist";
		}
		if(album.equals("null")){
			album = "Unknown Album";
		}
		if(song.equals("null")){
			album = "Unknown Track";
		}
		
		Intent mediaUpdate = new Intent();
		mediaUpdate.setAction(WatchLink.M_UPDATED);		
		
		String dataSender = "MEDIA_UPDATE|" + song + "|" + artist + "|" + album + "|";
		
		try {
			Bitmap tinyBitmap = toSend;
			tinyBitmap = ThumbnailUtils.extractThumbnail(tinyBitmap, 220, 176);
			ByteArrayOutputStream os = new ByteArrayOutputStream(); 
			tinyBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, (OutputStream) os); 
			byte[] b = os.toByteArray();
			String imageText = Base64.encodeToString(b, Base64.DEFAULT);
			dataSender += imageText;
		} catch (Exception e) {
			log("Bitmap get failed");
			dataSender += "null";
		}
		
		mediaUpdate.putExtra("data", dataSender);
	
		LocalBroadcastManager.getInstance(this).sendBroadcast(mediaUpdate);
		log("Media update sent");
	}

	@Override
	public void onClientPlaybackStateUpdate(int state) {
	}

	@Override
	public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
			long currentPosMs, float speed) {
	}

	@Override
	public void onClientTransportControlUpdate(int tcf) {
	}
}
