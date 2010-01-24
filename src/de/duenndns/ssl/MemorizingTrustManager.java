/* MemorizingTrustManager - a TrustManager which asks the user about invalid
 *  certificates and memorizes their decision.
 *
 * Copyright (c) 2010 Georg Lukas <georg@op-co.de>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.duenndns.ssl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.util.Log;
import android.os.Handler;

import java.io.File;
import java.security.cert.*;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A X509 trust manager implementation which asks the user about invalid
 * certificates and memorizes their decision.
 * <p>
 * The certificate validity is checked using the system default X509
 * TrustManager, creating a query Dialog if the check fails.
 * <p>
 * <b>WARNING:</b> This only works if a dedicated thread is used for
 * opening sockets!
 */
public class MemorizingTrustManager implements X509TrustManager {
	final static String TAG = "MemorizingTrustManager";

	static String KEYSTORE_DIR = "KeyStore";
	static String KEYSTORE_FILE = "KeyStore.bks";

	Activity master;
	Handler masterHandler;
	private File keyStoreFile;
	private KeyStore appKeyStore;
	private X509TrustManager defaultTrustManager;
	private X509TrustManager appTrustManager;

	/** Creates an instance of the MemorizingTrustManager class.
	 *
	 * @param m the Activity to be used for displaying Dialogs.
	 */
	public MemorizingTrustManager(Activity m) {
		master = m;
		masterHandler = new Handler();

		File dir = m.getApplication().getDir(KEYSTORE_DIR, Context.MODE_PRIVATE);
		keyStoreFile = new File(dir + File.separator + KEYSTORE_FILE);

		appKeyStore = loadAppKeyStore();
		defaultTrustManager = getTrustManager(null);
		appTrustManager = getTrustManager(appKeyStore);
	}

	/**
	 * Returns a X509TrustManager list containing a new instance of
	 * TrustManagerFactory.
	 *
	 * This function is meant for convenience only. You can use it
	 * as follows to integrate TrustManagerFactory for HTTPS sockets:
	 *
	 * <pre>
	 *     SSLContext sc = SSLContext.getInstance("TLS");
	 *     sc.init(null, MemorizingTrustManager.getInstanceList(this),
	 *         new java.security.SecureRandom());
	 *     HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
	 * </pre>
	 * @param c the Activity to be used for displaying Dialogs.
	 */
	public static X509TrustManager[] getInstanceList(Activity c) {
		return new X509TrustManager[] { new MemorizingTrustManager(c) };
	}

	/**
	 * Changes the path for the KeyStore file.
	 *
	 * The actual filename relative to the app's directory will be
	 * <code>app_<i>dirname</i>/<i>filename</i></code>.
	 *
	 * @param dirname directory to store the KeyStore.
	 * @param filename file name for the KeyStore.
	 */
	public static void setKeyStoreFile(String dirname, String filename) {
		KEYSTORE_DIR = dirname;
		KEYSTORE_FILE = filename;
	}

	X509TrustManager getTrustManager(KeyStore ks) {
		try {
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
			tmf.init(ks);
			for (TrustManager t : tmf.getTrustManagers()) {
				if (t instanceof X509TrustManager) {
					return (X509TrustManager)t;
				}
			}
		} catch (Exception e) {
			// Here, we are covering up errors. It might be more useful
			// however to throw them out of the constructor so the
			// embedding app knows something went wrong.
			Log.e(TAG, "getTrustManager(" + ks + ")", e);
		}
		return null;
	}

	KeyStore loadAppKeyStore() {
		KeyStore ks;
		try {
			ks = KeyStore.getInstance(KeyStore.getDefaultType());
		} catch (KeyStoreException e) {
			Log.e(TAG, "getAppKeyStore()", e);
			return null;
		}
		try {
			ks.load(null, null);
			ks.load(new java.io.FileInputStream(keyStoreFile), "MTM".toCharArray());
		} catch (Exception e) {
			Log.e(TAG, "getAppKeyStore(" + keyStoreFile + ")", e);
		}
		return ks;
	}

	void storeCert(X509Certificate[] chain) {
		// add all certs from chain to appKeyStore
		try {
			for (X509Certificate c : chain)
				appKeyStore.setCertificateEntry(c.getSubjectDN().toString(), c);
		} catch (KeyStoreException e) {
			Log.e(TAG, "storeCert(" + chain + ")", e);
			return;
		}
		
		// reload appTrustManager
		appTrustManager = getTrustManager(appKeyStore);

		// store KeyStore to file
		try {
			java.io.FileOutputStream fos = new java.io.FileOutputStream(keyStoreFile);
			appKeyStore.store(fos, "MTM".toCharArray());
			fos.close();
		} catch (Exception e) {
			Log.e(TAG, "storeCert(" + keyStoreFile + ")", e);
		}
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType)
		throws CertificateException
	{
		Log.d(TAG, "checkClientTrusted(" + chain + ", " + authType + ")");
		try {
			appTrustManager.checkClientTrusted(chain, authType);
		} catch (CertificateException _) {
			try {
				defaultTrustManager.checkClientTrusted(chain, authType);
			} catch (CertificateException e) {
				interact(chain, authType, e);
			}
		}
	}

	public void checkServerTrusted(X509Certificate[] chain, String authType)
		throws CertificateException
	{
		Log.d(TAG, "checkServerTrusted(" + chain + ", " + authType + ")");
		try {
			appTrustManager.checkServerTrusted(chain, authType);
		} catch (CertificateException _) {
			try {
				defaultTrustManager.checkServerTrusted(chain, authType);
			} catch (CertificateException e) {
				interact(chain, authType, e);
			}
		}
	}

	public X509Certificate[] getAcceptedIssuers()
	{
		Log.d(TAG, "getAcceptedIssuers()");
		return defaultTrustManager.getAcceptedIssuers();
	}

	public void interact(final X509Certificate[] chain, String authType, CertificateException cause)
		throws CertificateException
	{
		DialogPoster dp = new DialogPoster(chain, cause);

		// wait for the result
		synchronized(dp) {
			try {
				dp.wait();
				switch (dp.get()) {
				case DialogPoster.ASK_ALWAYS:
					storeCert(chain);
				case DialogPoster.ASK_ONCE:
					break;
				default:
					throw (cause);
				}
			} catch (InterruptedException ie) {
				Log.d(TAG, "interact() interrupted.");
				throw (cause);
			}
		}
	}

	private class DialogPoster implements OnClickListener {

		private final static int ASK_ABORT	= 0;
		private final static int ASK_ONCE	= 1;
		private final static int ASK_ALWAYS	= 2;
		AtomicInteger askResult = new AtomicInteger(ASK_ABORT);

		private DialogPoster(X509Certificate[] chain, CertificateException cause) {
			Throwable e = cause;
			while (e.getCause() != null)
				e = e.getCause();
			StringBuffer si = new StringBuffer(e.toString());
			for (X509Certificate c : chain) {
				si.append("\n\n");
				si.append(c.getSubjectDN().toString());
				si.append(" (");
				si.append(c.getIssuerDN().toString());
				si.append(")");
			}
			final String msg = si.toString();

			// send a query dialog to the activity
			masterHandler.post(new Runnable() {
				public void run() {
					new AlertDialog.Builder(master).setTitle("Accept Invalid Certificate?")
						.setMessage(msg)
						.setPositiveButton("Always", DialogPoster.this)
						.setNeutralButton("Once", DialogPoster.this)
						.setNegativeButton("Abort", DialogPoster.this)
						.create().show();
				}
			});
		}

		// react on button press
		public void onClick(DialogInterface dialog, int btnId) {
			boolean allowConnect = false;
			dialog.dismiss();
			switch (btnId) {
			case DialogInterface.BUTTON_POSITIVE:
				Log.d(TAG, "Storing certificate forever...");
				askResult.set(ASK_ALWAYS);
				break;
			case DialogInterface.BUTTON_NEUTRAL:
				Log.d(TAG, "Allowing connection...");
				askResult.set(ASK_ONCE);
				break;
			default:
				Log.d(TAG, "Aborting connection!");
				askResult.set(ASK_ABORT);
			}
			synchronized(this) {
				this.notify();
			}
		}

		public int get() {
			return askResult.get();
		}
	}

}