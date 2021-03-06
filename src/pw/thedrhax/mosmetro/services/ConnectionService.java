/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.mosmetro.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.activities.DebugActivity;
import pw.thedrhax.mosmetro.activities.SettingsActivity;
import pw.thedrhax.mosmetro.authenticator.Authenticator;
import pw.thedrhax.mosmetro.authenticator.Chooser;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Notification;
import pw.thedrhax.util.Util;
import pw.thedrhax.util.WifiUtils;

public class ConnectionService extends IntentService {
    private static boolean running = false;
    private static String SSID = WifiUtils.UNKNOWN_SSID;
    private boolean from_shortcut = false;

    // Preferences
    private WifiUtils wifi;
    private SharedPreferences settings;
    private int pref_retry_count;
    private int pref_retry_delay;
    private int pref_ip_wait;
    private boolean pref_notify_success_lock;

    // Notifications
    private Notification notify_progress;
    private Notification notification;

    // Authenticator
    private Logger logger;
    private Authenticator connection;

    public ConnectionService () {
		super("ConnectionService");
	}
	
	@Override
    public void onCreate() {
		super.onCreate();

        wifi = new WifiUtils(this) {
            @Override
            public boolean isConnected(String SSID) {
                return from_shortcut || super.isConnected(SSID);
            }
        };
        settings = PreferenceManager.getDefaultSharedPreferences(this);
        pref_retry_count = Util.getIntPreference(settings, "pref_retry_count", 3);
        pref_retry_delay = Util.getIntPreference(settings, "pref_retry_delay", 5);
        pref_ip_wait = Util.getIntPreference(settings, "pref_ip_wait", 30);
        pref_notify_success_lock = settings.getBoolean("pref_notify_success_lock", true);

        PendingIntent delete_intent = PendingIntent.getService(
                this, 0,
                new Intent(this, ConnectionService.class).setAction("STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        notify_progress = new Notification(this)
                .setIcon(R.drawable.ic_notification_connecting)
                .setId(1)
                .setEnabled(settings.getBoolean("pref_notify_progress", true) && (Build.VERSION.SDK_INT >= 14))
                .setDeleteIntent(delete_intent);

        notification = new Notification(this)
                .setId(0)
                .setDeleteIntent(delete_intent);

        logger = new Logger();
    }

    private void notify (Authenticator.RESULT result) {
        if (!running) return;

        switch (result) {
            case CONNECTED:
            case ALREADY_CONNECTED:
                notification
                        .setTitle(getString(R.string.notification_success))
                        .setIcon(R.drawable.ic_notification_success);

                if (settings.getBoolean("pref_notify_success_log", false)) {
                    notification
                            .setText(getString(R.string.notification_success_log))
                            .setIntent(new Intent(this, DebugActivity.class).putExtra("logger", logger));
                } else {
                    notification
                            .setText(getString(R.string.notification_success_settings))
                            .setIntent(new Intent(this, SettingsActivity.class));
                }

                notification
                        .setCancellable(from_shortcut || !pref_notify_success_lock)
                        .setEnabled(settings.getBoolean("pref_notify_success", true))
                        .show();

                notification.setCancellable(true);

                return;

            case NOT_REGISTERED:
                notification
                        .setTitle(getString(R.string.notification_not_registered))
                        .setText(getString(R.string.notification_not_registered_register))
                        .setIcon(R.drawable.ic_notification_register)
                        .setIntent(new Intent(Intent.ACTION_VIEW).setData(Uri.parse("http://wi-fi.ru")))
                        .setEnabled(settings.getBoolean("pref_notify_fail", true))
                        .setId(2)
                        .show();

                notification.setId(0); // Reset ID to default

                return;

            case ERROR:
                notification
                        .setTitle(getString(R.string.notification_error))
                        .setText(getString(R.string.notification_error_log))
                        .setIcon(R.drawable.ic_notification_error)
                        .setIntent(new Intent(this, DebugActivity.class).putExtra("logger", logger))
                        .setEnabled(settings.getBoolean("pref_notify_fail", true))
                        .show();

                return;

            case CAPTCHA:
                notification
                        .setTitle(getString(R.string.notification_captcha))
                        .setText(getString(R.string.notification_captcha_summary))
                        .setIcon(R.drawable.ic_notification_register)
                        .setIntent(new Intent(this, DebugActivity.class)
                                    .putExtra("logger", logger)
                                    .putExtra("captcha", true)
                        )
                        .show();
                return;

            case UNSUPPORTED:
                notification
                        .setTitle(getString(R.string.notification_unsupported))
                        .setText(getString(R.string.notification_error_log))
                        .setIcon(R.drawable.ic_notification_register)
                        .setIntent(new Intent(this, DebugActivity.class).putExtra("logger", logger))
                        .setEnabled(settings.getBoolean("pref_notify_fail", true))
                        .show();
        }
    }

    private boolean waitForIP() {
        if (from_shortcut) return true;

        int count = 0;

        logger.log(getString(R.string.ip_wait));
        notify_progress
                .setText(getString(R.string.ip_wait))
                .setContinuous()
                .show();

        while (wifi.getIP() == 0 && running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}

            if (!wifi.isConnected(SSID)) {
                logger.log(String.format(
                        getString(R.string.error),
                        getString(R.string.auth_error_network_disconnected)
                ));
                return false;
            }

            if (pref_ip_wait != 0 && count++ == pref_ip_wait) {
                logger.log(String.format(
                        getString(R.string.error),
                        String.format(
                                getString(R.string.ip_wait_result),
                                " " + getString(R.string.not),
                                pref_ip_wait
                        )
                ));
                return false;
            }
        }

        logger.log(String.format(
                getString(R.string.ip_wait_result),
                "", count/2
        ));
        return true;
    }

    private Authenticator.RESULT connect() {
        Authenticator.RESULT result;
        int count = 0;

        do {
            if (!waitForIP()) return Authenticator.RESULT.ERROR;

            if (count > 0) {
                notify_progress
                        .setText(String.format("%s (%s)",
                                getString(R.string.notification_progress_waiting),
                                String.format(
                                        getString(R.string.try_out_of),
                                        count + 1,
                                        pref_retry_count
                                )
                        ))
                        .setContinuous()
                        .show();

                try {
                    Thread.sleep(pref_retry_delay * 1000);
                } catch (InterruptedException ignored) {}
            }

            notify_progress
                    .setText(String.format("%s (%s)",
                            getString(R.string.notification_progress_connecting),
                            String.format(
                                    getString(R.string.try_out_of),
                                    count + 1,
                                    pref_retry_count
                            )
                    ))
                    .show();

            result = connection.start();

            if (!wifi.isConnected(SSID)) {
                logger.log(String.format(
                        getString(R.string.error),
                        getString(R.string.auth_error_network_disconnected)
                ));
                result = Authenticator.RESULT.ERROR; break;
            }

            if (result == Authenticator.RESULT.NOT_REGISTERED) break;
            if (result == Authenticator.RESULT.CAPTCHA) break;
        } while (++count < pref_retry_count && running && result == Authenticator.RESULT.ERROR);

        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ("STOP".equals(intent.getAction())) { // Stop by intent
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent.hasExtra("SSID")) {
            SSID = intent.getStringExtra("SSID");
            from_shortcut = true;
        }

        if (SSID.isEmpty() || WifiUtils.UNKNOWN_SSID.equals(SSID)) {
            SSID = wifi.getSSID(intent);
            from_shortcut = false;
        }

        if (!(WifiUtils.UNKNOWN_SSID.equals(SSID) || running)) { // Start if SSID has changed
            onStart(intent, startId);
        }
        return START_NOT_STICKY;
    }

    public void onHandleIntent(Intent intent) {
        running = true;

        connection = new Chooser(this, logger).choose(SSID);
        if (connection != null) main();

        running = false;
    }
    
    private void main() {
        logger.date();

        connection.setLogger(logger);
        connection.setProgressListener(new Authenticator.ProgressListener() {
            @Override
            public void onProgressUpdate(int progress) {
                notify_progress
                        .setProgress(progress)
                        .show();
            }
        });

        notify_progress
            .setTitle(String.format(
                getString(R.string.auth_connecting),
                connection.getSSID()
            ))
            .setText(getString(R.string.auth_waiting))
            .setContinuous()
            .show();

        try {
            if (!from_shortcut)
                Thread.sleep(5000);
        } catch (InterruptedException ignored) {}

        // Try to connect
        notification.hide();
        Authenticator.RESULT result = connect();
        notify_progress.hide();

        logger.date();

        // Notify user if still connected to Wi-Fi
        if (wifi.isConnected(SSID)) notify(result);

        if (from_shortcut || !(result == Authenticator.RESULT.ALREADY_CONNECTED
                || result == Authenticator.RESULT.CONNECTED)) return;

        // Wait while internet connection is available
        int count = 0;
        while (wifi.isConnected(SSID)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}

            // Check internet connection each 10 seconds
            if (settings.getBoolean("pref_internet_check", true) && ++count == 10) {
                count = 0;
                if (connection.isConnected() != Authenticator.CHECK.CONNECTED)
                    break;
            }
        }

        notification.hide();

        // Try to reconnect the Wi-Fi network
        if (settings.getBoolean("pref_wifi_reconnect", false))
            wifi.reconnect(connection.getSSID());
	}
	
	@Override
    public void onDestroy() {
        SSID = WifiUtils.UNKNOWN_SSID;
        if (connection != null) connection.stop();
        if (!from_shortcut) notification.hide();
        notify_progress.hide();
    }
}
