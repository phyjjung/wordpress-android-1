package org.wordpress.android;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.apps.authenticator.Base32String;
import com.google.android.apps.authenticator.Base32String.DecodingException;
import com.google.android.apps.authenticator.OtpSourceException;
import com.google.android.apps.authenticator.PasscodeGenerator;
import com.google.android.apps.authenticator.PasscodeGenerator.Signer;
import com.google.android.apps.authenticator.TotpClock;
import com.google.android.apps.authenticator.TotpCountdownTask;
import com.google.android.apps.authenticator.TotpCounter;
import com.google.android.apps.authenticator.Utilities;

public class TwoStep extends Activity {
	/**
	 * Frequency (milliseconds) with which TOTP countdown indicators are
	 * updated.
	 */
	private static final long TOTP_COUNTDOWN_REFRESH_PERIOD = 100;
	public static final int PIN_LENGTH = 6; // HOTP or TOTP
	private static final String SECRET_PARAM = "secret";

	// Links
	public static final String ZXING_MARKET = "market://search?q=pname:com.google.zxing.client.android";
	public static final String ZXING_DIRECT = "https://zxing.googlecode.com/files/BarcodeScanner3.1.apk";

	static final int SCAN_REQUEST = 31337;
	static final int DOWNLOAD_DIALOG = 0;
	static final int INVALID_SECRET_IN_QR_CODE = 1;
	/** Counter used for generating TOTP verification codes. */
	private TotpCounter mTotpCounter;

	/** Clock used for generating TOTP verification codes. */
	private TotpClock mTotpClock;

	/**
	 * Task that periodically notifies this activity about the amount of time
	 * remaining until the TOTP codes refresh. The task also notifies this
	 * activity when TOTP codes refresh.
	 */
	private TotpCountdownTask mTotpCountdownTask;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.two_step);

		setTitle(R.string.two_step_authentication);

		mTotpClock = new TotpClock(TwoStep.this);
		mTotpCounter = new TotpCounter(PasscodeGenerator.INTERVAL);

		checkSecret();
		Button barcode_button = (Button) findViewById(R.id.scan_barcode);
		barcode_button.setOnClickListener(new Button.OnClickListener() {

			@Override
			public void onClick(View v) {
				Intent intentScan = new Intent(
						"com.google.zxing.client.android.SCAN");
				intentScan.putExtra("SCAN_MODE", "QR_CODE_MODE");
				intentScan.putExtra("SAVE_HISTORY", false);
				try {
					startActivityForResult(intentScan, SCAN_REQUEST);
				} catch (ActivityNotFoundException error) {
					showDialog(TwoStep.DOWNLOAD_DIALOG);
				}
			}

		});

		Button enter_secret_button = (Button) findViewById(R.id.enter_secret);
		enter_secret_button.setOnClickListener(new Button.OnClickListener() {
			@Override
			public void onClick(View v) {
				final EditText input = new EditText(TwoStep.this);
				input.setInputType(~(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | ~(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) | ~(InputType.TYPE_TEXT_FLAG_CAP_WORDS));
				new AlertDialog.Builder(TwoStep.this)
						.setTitle("Enter provided key")
						.setView(input)
						.setPositiveButton("Ok",
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
											int whichButton) {
										Editable value = input.getText();
										saveSecret(value.toString());
									}
								}).show();
			}
		});

		TextView otp_code = (TextView) findViewById(R.id.otp_code);
		registerForContextMenu(otp_code);
	}

	@Override
	protected void onStart() {
		super.onStart();

		updateCodesAndStartTotpCountdownTask();
	}

	@Override
	protected void onStop() {
		stopTotpCountdownTask();

		super.onStop();
	}

	private void updateCodesAndStartTotpCountdownTask() {
		stopTotpCountdownTask();

		if (WordPress.wpDB.getTwostepSecret() == null) {
			return;
		}
		
		mTotpCountdownTask = new TotpCountdownTask(mTotpCounter, mTotpClock,
				TOTP_COUNTDOWN_REFRESH_PERIOD);
		mTotpCountdownTask.setListener(new TotpCountdownTask.Listener() {
			@Override
			public void onTotpCountdown(long millisRemaining) {
				TextView otp_code = (TextView) findViewById(R.id.otp_code);
				int progressWidth = (int) Math.round(((30000 - millisRemaining)/30000.0) * otp_code.getWidth());
				Rect bounds[] = new Rect[2];
				ShapeDrawable background = new ShapeDrawable(new RectShape());
				bounds[0] = new Rect(0, 0, otp_code.getWidth(), otp_code.getHeight());
				background.getPaint().setColor(Color.BLACK);
				ShapeDrawable progess = new  ShapeDrawable(new RectShape());
				bounds[1] = new Rect(0, 0, progressWidth, otp_code.getHeight());
				progess.getPaint().setColor(Color.rgb(50, 50, 50));
				Drawable layers[] = { background, progess };
				LayerDrawable ld = new LayerDrawable(layers);
				ld.setBounds(bounds[0]);
				for (int i = 0; i < ld.getNumberOfLayers(); i++) {
		            ld.getDrawable(i).setBounds(bounds[i]);
		        }
				otp_code.setBackgroundDrawable(ld);
				if (millisRemaining < 10000) {
					int gbValue = Math.round(200 * millisRemaining / 10000);
					otp_code.setTextColor(Color.rgb(200, gbValue, gbValue));
				} else {
					otp_code.setTextColor(Color.rgb(200, 200, 200));
				}
				return;
			}

			@Override
			public void onTotpCounterValueChanged() {
				if (isFinishing()) {
					// No need to reach to this even because the Activity is
					// finishing anyway
					return;
				}
				refreshVerificationCodes();
			}
		});

		mTotpCountdownTask.startAndNotifyListener();
	}

	private void stopTotpCountdownTask() {
		if (mTotpCountdownTask != null) {
			mTotpCountdownTask.stop();
			mTotpCountdownTask = null;
		}
	}

	private void refreshVerificationCodes() {
		TextView otp_code = (TextView) findViewById(R.id.otp_code);
		try {
			otp_code.setText(computePin());
		} catch (OtpSourceException e) {
			otp_code.setText("??????");
			Log.e("WordPress", "Error generating Two Step code", e);
		}
	}

	private String computePin() throws OtpSourceException {
		String secret = WordPress.wpDB.getTwostepSecret();
		if (secret == null || secret.length() == 0) {
			throw new OtpSourceException("Null or empty secret");
		}

		long otp_state = mTotpCounter.getValueAtTime(Utilities
				.millisToSeconds(mTotpClock.currentTimeMillis()));

		try {
			Signer signer = getSigningOracle(secret);
			PasscodeGenerator pcg = new PasscodeGenerator(signer, PIN_LENGTH);

			return pcg.generateResponseCode(otp_state);
		} catch (GeneralSecurityException e) {
			throw new OtpSourceException("Crypto failure", e);
		}
	}

	static Signer getSigningOracle(String secret) {
		try {
			byte[] keyBytes = Base32String.decode(secret);
			final Mac mac = Mac.getInstance("HMACSHA1");
			mac.init(new SecretKeySpec(keyBytes, ""));

			// Create a signer object out of the standard Java MAC
			// implementation.
			return new Signer() {
				@Override
				public byte[] sign(byte[] data) {
					return mac.doFinal(data);
				}
			};
		} catch (DecodingException error) {
			Log.e("WordPress", error.getMessage());
		} catch (NoSuchAlgorithmException error) {
			Log.e("WordPress", error.getMessage());
		} catch (InvalidKeyException error) {
			Log.e("WordPress", error.getMessage());
		}

		return null;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		if (requestCode == SCAN_REQUEST && resultCode == Activity.RESULT_OK) {
			// Grab the scan results and convert it into a URI
			String scanResult = (intent != null) ? intent
					.getStringExtra("SCAN_RESULT") : null;
			Uri uri = (scanResult != null) ? Uri.parse(scanResult) : null;
			String secret = uri.getQueryParameter(SECRET_PARAM);
		    if (secret == null || secret.length() == 0) {
		        Log.e(getString(R.string.app_name), "Two Step: Secret key not found in URI");
		        showDialog(INVALID_SECRET_IN_QR_CODE);
		        return;
		      }

		      if (getSigningOracle(secret) == null) {
		        Log.e(getString(R.string.app_name), "Two Step: Invalid secret key");
		        showDialog(INVALID_SECRET_IN_QR_CODE);
		        return;
		      }
		      saveSecret(secret);
		      
			Log.i(getString(R.string.app_name), "TwoStep: " + uri.toString());
		}
	}

	@Override
	protected Dialog onCreateDialog(final int id) {
		Dialog dialog = null;
		switch (id) {
		/**
		 * Prompt to download ZXing from Market. If Market app is not installed,
		 * such as on a development phone, open the HTTPS URI for the ZXing apk.
		 */
		case DOWNLOAD_DIALOG:
			AlertDialog.Builder dlBuilder = new AlertDialog.Builder(this);
			dlBuilder.setTitle(R.string.install_dialog_title);
			dlBuilder.setMessage(R.string.install_dialog_message);
			dlBuilder.setIcon(android.R.drawable.ic_dialog_alert);
			dlBuilder.setPositiveButton(R.string.install_button,
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog,
								int whichButton) {
							Intent intent = new Intent(Intent.ACTION_VIEW, Uri
									.parse(ZXING_MARKET));
							try {
								startActivity(intent);
							} catch (ActivityNotFoundException e) { // if no
																	// Market
																	// app
								intent = new Intent(Intent.ACTION_VIEW, Uri
										.parse(ZXING_DIRECT));
								startActivity(intent);
							}
						}
					});
			dlBuilder.setNegativeButton(R.string.cancel, null);
			dialog = dlBuilder.create();
			break;

		case INVALID_SECRET_IN_QR_CODE:
			dialog = new AlertDialog.Builder(this)
	        .setTitle(R.string.error)
	        .setMessage(R.string.error_uri)
	        .setIcon(android.R.drawable.ic_dialog_alert)
	        .setPositiveButton(R.string.ok, null)
	        .create();
			break;

		default:
			if (dialog == null) {
				dialog = super.onCreateDialog(id);
			}
			break;
		}
		return dialog;
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		// Create your context menu here
		menu.add(0, v.getId(), 0, "Remove Two Step authentication");
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		WordPress.wpDB.removeTwostepSecret();
		checkSecret();
		return true;
	}

	private void checkSecret() {
		String secret = WordPress.wpDB.getTwostepSecret();
		if (secret == null) {
			findViewById(R.id.otp_code).setVisibility(View.GONE);
			findViewById(R.id.twostep_setup).setVisibility(View.VISIBLE);
		} else {
			findViewById(R.id.otp_code).setVisibility(View.VISIBLE);
			findViewById(R.id.twostep_setup).setVisibility(View.GONE);
		}
	}

	private void saveSecret(String secret) {
		WordPress.wpDB.setTwostepSecret(secret);
		updateCodesAndStartTotpCountdownTask();
		checkSecret();
	}
}
