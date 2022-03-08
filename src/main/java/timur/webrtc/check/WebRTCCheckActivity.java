// WebRTC-Check Copyright 2022 timur.mobi. All rights reserved.

package timur.webrtc.check;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.graphics.Color;
import android.graphics.Canvas;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.webkit.SslErrorHandler;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.widget.Toast;
import android.util.Log;
import android.net.Uri;
import android.net.http.SslError;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.Manifest;

import java.lang.reflect.Method;

import timur.webrtc.check.BuildConfig;

public class WebRTCCheckActivity extends Activity {
	private static final String TAG = "WebRTCCheckActivity";

	private WebView myWebView = null;
	private	Context context;
	private boolean startupFail = false;
	private String currentUrl = null;
	private String webviewVersionString = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d(TAG, "onCreate "+BuildConfig.VERSION_NAME);
		context = this;

		// call getCurrentWebViewPackageInfo() to get webview versionName, may fail on old Android / old webview
		PackageInfo webviewPackageInfo = getCurrentWebViewPackageInfo();
		if(webviewPackageInfo != null) {
			Log.d(TAG, "onCreate webview packageInfo "+
				webviewPackageInfo.packageName+" "+webviewPackageInfo.versionName);
			webviewVersionString = webviewPackageInfo.versionName+" ("+webviewPackageInfo.packageName+")";
		}

		// the real webview test comes here and we MUST try/catch
		try {
			setContentView(R.layout.activity_main);
		} catch(Exception ex) {
			Log.d(TAG, "onCreate setContentView ex="+ex);
			startupFail = true;
			Toast.makeText(context, "WebRTCCheck cannot start. No System WebView installed?",
				Toast.LENGTH_LONG).show();
			return;
		}

		myWebView = findViewById(R.id.webview);
		myWebView.setBackgroundColor(Color.TRANSPARENT);

		WebSettings webSettings = myWebView.getSettings();
		webSettings.setJavaScriptEnabled(true);
		webSettings.setAllowFileAccessFromFileURLs(true);
		webSettings.setMediaPlaybackRequiresUserGesture(false);
		webSettings.setDomStorageEnabled(true);

		myWebView.setWebViewClient(new WebViewClient() {
			@Override
			public void onLoadResource(WebView view, String url) {
				if(url.indexOf("googleapis.com/")>=0 || url.indexOf("google-analytics.com/")>=0) {
					Log.d(TAG, "onLoadResource deny: " + url);
					return;
				}
				Log.d(TAG, "onLoadResource accept: " + url);
				super.onLoadResource(view, url);
			}

			/*
			@Override
			public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
				// this is called when webview does a https PAGE request and fails
				Log.d(TAG, "onReceivedSslError "+error);
				// error.getPrimaryError()
				// -1 = no error
				// 0 = not yet valid
				// 1 = SSL_EXPIRED
				// 2 = SSL_IDMISMATCH  certificate Hostname mismatch
				// 3 = SSL_UNTRUSTED   certificate authority is not trusted
				// 5 = SSL_INVALID
				super.onReceivedSslError(view, handler, error);
			}
			*/

			@SuppressWarnings("deprecation")
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				final Uri uri = Uri.parse(url);
				return handleUri(uri);
			}

			//@TargetApi(Build.VERSION_CODES.N)
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				final Uri uri = request.getUrl();
				return handleUri(uri);
			}

			private boolean handleUri(final Uri uri) {
				final String newUrl = uri.toString();
				if(newUrl.startsWith("https:")) {
					// forward to an external browser
					Log.i(TAG, "handleUri true "+newUrl);
					final Intent intent = new Intent(Intent.ACTION_VIEW, uri);
					startActivity(intent);
					return true;
				}
				// continue to load this url ourselves
				Log.i(TAG, "handleUri false "+newUrl);
				currentUrl = newUrl;
				return false;
			}

			@Override
			public void onPageFinished(WebView view, String url){
				Log.d(TAG, "onPageFinished url=" + url);
				currentUrl = url;
			}
		});

		myWebView.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onConsoleMessage(ConsoleMessage cm) {
				String msg = cm.message();
				Log.d(TAG,"console "+msg + " L"+cm.lineNumber());
				return true;
			}

			@Override
			public void onPermissionRequest(PermissionRequest request) {
				String[] strArray = request.getResources();
				for(int i=0; i<strArray.length; i++) {
					Log.w(TAG, "onPermissionRequest "+i+" ("+strArray[i]+")");
					// we only grant the permission we want to grant
					if(strArray[i].equals("android.webkit.resource.AUDIO_CAPTURE") ||
					   strArray[i].equals("android.webkit.resource.VIDEO_CAPTURE")) {
						request.grant(strArray);
						break;
					}
					Log.w(TAG, "onPermissionRequest unexpected "+strArray[i]);
				}
			}

			@Override
			public Bitmap getDefaultVideoPoster() {
				// this replaces android's ugly default video poster with a dark grey background
				final Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
				Canvas canvas = new Canvas(bitmap);
				canvas.drawARGB(200, 4, 4, 4);
				return bitmap;
			}
		});

		// let JS call java service code
		myWebView.addJavascriptInterface(new WebCallJSInterface(), "Android");

		currentUrl = "file:///android_asset/index.html";
		Log.d(TAG, "startWebView load currentUrl="+currentUrl);
		myWebView.loadUrl(currentUrl);
		Log.d(TAG, "onCreate done");
	}

	@Override
	public void onStart() {
		super.onStart();
		if(startupFail) {
			Log.d(TAG, "onStart abort on startupFail");
			return;
		}
		// ask user to provide basic permissions for mic and camera
		boolean permMicNeeed = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO)
					!= PackageManager.PERMISSION_GRANTED;
		boolean permCamNeeed = checkCallingOrSelfPermission(android.Manifest.permission.CAMERA)
					!= PackageManager.PERMISSION_GRANTED;
		if(permMicNeeed || permCamNeeed) {
			AlertDialog.Builder alertbox = new AlertDialog.Builder(context);
			alertbox.setTitle("Permission needed");
			String msg = "";
			if(permMicNeeed && permCamNeeed) {
				msg = "Permissions needed for WebView to use microphone and camera.";
			} else if(permMicNeeed) {
				msg = "A permission is needed for WebView to use the microphone.";
			} else if(permCamNeeed) {
				msg = "A permission is needed for WebView to use the camera.";
			}
			msg += "\nOpen 'Permissions' and allow media devices to be used.";
			alertbox.setMessage(msg);
			alertbox.setPositiveButton("Continue", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Intent myAppSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.parse("package:" + getPackageName()));
					myAppSettings.addCategory(Intent.CATEGORY_DEFAULT);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					myAppSettings.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
					context.startActivity(myAppSettings);
				}
			});
			alertbox.show();
			return;
		}
	}

	@Override
	public void onBackPressed() {
		Log.d(TAG, "onBackPressed "+currentUrl);
		if(!currentUrl.equals("file:///android_asset/index.html")) {
			Log.d(TAG, "onBackPressed -> history.back()");
			String str = "history.back()";
			final ValueCallback<String> myBlock = null;
			myWebView.post(new Runnable() {
				@Override
				public void run() {
					// escape '\r\n' to '\\r\\n'
					final String str2 = str.replace("\\", "\\\\");
					if(myWebView!=null) {
						// evaluateJavascript() instead of loadUrl()
						myWebView.evaluateJavascript(str2, myBlock);
					}
				}
			});
			return;
		}

		Log.d(TAG, "onBackPressed -> super.onBackPressed()");
		super.onBackPressed();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// accept all changes without restarting the activity
		super.onConfigurationChanged(newConfig);
		Log.d(TAG, "onConfigurationChanged "+newConfig);
	}

	// private

	public class WebCallJSInterface {
//		static final String TAG = "WebCallJSIntrf";

		@android.webkit.JavascriptInterface
		public String webviewVersion() {
			return webviewVersionString;
		}
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private PackageInfo getCurrentWebViewPackageInfo() {
		PackageInfo pInfo = null;
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Log.d(TAG, "getCurrentWebViewPackageInfo for O+");
			pInfo = WebView.getCurrentWebViewPackage();
		} else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			try {
				Log.d(TAG, "getCurrentWebViewPackageInfo for M+");
				Class webViewFactory = Class.forName("android.webkit.WebViewFactory");
				Method method = webViewFactory.getMethod("getLoadedPackageInfo");
				pInfo = (PackageInfo)method.invoke(null);
			} catch(Exception e) {
				Log.d(TAG, "getCurrentWebViewPackageInfo for M+ ex="+e);
			}
			if(pInfo==null) {
				try {
					Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2)");
					Class webViewFactory = Class.forName("com.google.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo) method.invoke(null);
				} catch(Exception e2) {
					//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2) ex="+e2);
				}
			}
		} else {
			// no info before Lollipop
		}
		if(pInfo!=null) {
			Log.d(TAG, "getCurrentWebViewPackageInfo pInfo set");
		}
		return pInfo;
	}
}

