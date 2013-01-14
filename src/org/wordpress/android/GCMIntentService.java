package org.wordpress.android;

import java.util.HashMap;
import java.util.Vector;

import org.wordpress.android.models.Blog;
import org.xmlrpc.android.XMLRPCClient;
import org.xmlrpc.android.XMLRPCException;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gcm.GCMBaseIntentService;

public class GCMIntentService extends GCMBaseIntentService {
    public GCMIntentService() {
        super(WordPress.GCM_SENDER_ID);
    }

	@Override
	protected void onError(Context context, String error) {
		Log.d("WordPress", "onError: " + error);
	}

	@Override
	protected void onMessage(Context context, Intent intent) {
		String message = intent.getStringExtra("message");
		Log.d("WordPress", "onMessage: " + message);

	}

	@Override
	protected void onRegistered(Context context, String regId) {
		Log.d("WordPress", "onRegistered: " + regId);
		Vector<HashMap<String,Object>> dotcomAccounts = WordPress.wpDB.getDotcomAccounts(GCMIntentService.this);
		Log.d("WordPress", "accounts: " + dotcomAccounts);
		for (HashMap<String, Object> hashMap : dotcomAccounts) {
			try {
				String username = (String) hashMap.get("username");
				Blog blog = new Blog((Integer) hashMap.get("blogId"), GCMIntentService.this);
				String password = blog.getPassword();
				XMLRPCClient client = new XMLRPCClient("https://wordpress.com/xmlrpc.php", null, null);
				Object[] vParams = { username, password, regId, WordPress.wpDB.getUUID(GCMIntentService.this), "android" };
				client.call("wpcom.mobile_push_register_token", vParams);
			} catch (XMLRPCException e) {
				Log.e("WordPress", "Error registering token", e);
			} catch (Exception e) {
			}
		}
	}

	@Override
	protected void onUnregistered(Context context, String regId) {
		Log.d("WordPress", "onUnregistered: " + regId);

	}

}
