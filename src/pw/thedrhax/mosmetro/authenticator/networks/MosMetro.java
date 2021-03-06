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

package pw.thedrhax.mosmetro.authenticator.networks;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.ProtocolException;
import java.util.Map;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.Authenticator;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.WifiUtils;

public class MosMetro extends Authenticator {
    public static final String SSID = "MosMetro_Free";
    protected String redirect = null;
    private int version = 2;

    public MosMetro (Context context) {
        super(context);
    }

    @Override
    public String getSSID() {
        return "MosMetro_Free";
    }

    @Override
    public RESULT connect() {
        Map<String,String> fields = null;

        if (stopped) return RESULT.INTERRUPTED;
        progressListener.onProgressUpdate(0);

        logger.log(String.format(context.getString(R.string.auth_connecting), getSSID()));

        logger.log(context.getString(R.string.auth_checking_connection));
        CHECK connected = isConnected();
        if (connected == CHECK.CONNECTED) {
            logger.log(context.getString(R.string.auth_already_connected));
            return RESULT.ALREADY_CONNECTED;
        } else if (connected == CHECK.WRONG_NETWORK) {
            logger.log(String.format(
                    context.getString(R.string.error),
                    context.getString(R.string.auth_error_network)
            ));

            if (settings.getBoolean("pref_wifi_reconnect", true)) {
                logger.log(context.getString(R.string.auth_restarting_wifi));
                new WifiUtils(context).reconnect(SSID);
            }

            return RESULT.ERROR;
        }

        logger.log(String.format(context.getString(R.string.auth_version), version));

        if (stopped) return RESULT.INTERRUPTED;
        progressListener.onProgressUpdate(25);

        logger.log(context.getString(R.string.auth_auth_page));
        try {
            client.get(redirect, null, pref_retry_count);
            if (version == 2) {
                Uri redirect_uri = Uri.parse(redirect);
                redirect = redirect_uri.getScheme() + "://" + redirect_uri.getHost();
                client.get(redirect + "/auth", null, pref_retry_count);
                logger.log(Logger.LEVEL.DEBUG, client.getPageContent().toString());
            }
            logger.log(Logger.LEVEL.DEBUG, client.getPageContent().outerHtml());
        } catch (Exception ex) {
            logger.log(Logger.LEVEL.DEBUG, ex);
            logger.log(String.format(
                    context.getString(R.string.error),
                    context.getString(R.string.auth_error_auth_page)
            ));
            return RESULT.ERROR;
        }

        if (version == 1) {
            try {
                Elements forms = client.getPageContent().getElementsByTag("form");
                if (forms.size() > 1) {
                    logger.log(String.format(
                            context.getString(R.string.error),
                            context.getString(R.string.auth_error_not_registered)
                    ));
                    return RESULT.NOT_REGISTERED;
                }
                fields = Client.parseForm(forms.first());
            } catch (Exception ex) {
                logger.log(String.format(
                        context.getString(R.string.error),
                        context.getString(R.string.auth_error_auth_form)
                ));
                return RESULT.ERROR;
            }
        }

        // Handle captcha request
        if (version == 2) {
            Element form = client.getPageContent().getElementsByTag("form").first();
            if (form != null && "captcha__container".equals(form.attr("class"))) {
                // Trying to bypass captcha using official backdoor
                logger.log(context.getString(R.string.auth_captcha_bypass));
                try {
                    int code = new OkHttp()
                            .resetHeaders()
                            .setHeader(
                                    new String(Base64.decode(
                                        "VXNlci1BZ2VudA==", Base64.DEFAULT
                                    )),
                                    new String(Base64.decode(
                                        "QXV0b01vc01ldHJvV2lmaS8xLjUuMCAo" +
                                        "TGludXg7IEFuZHJvaWQgNC40LjQ7IEEw" +
                                        "MTIzIEJ1aWxkL0tUVTg0UCk=", Base64.DEFAULT
                                    ))
                            )
                            .get(
                                    new String(Base64.decode(
                                        "aHR0cHM6Ly9hbW13LndpLWZpLnJ1L25l" +
                                        "dGluZm8vYXV0aA==", Base64.DEFAULT
                                    )), null, pref_retry_count)
                            .getResponseCode();

                    if (code == 200 && isConnected() == CHECK.CONNECTED) {
                        logger.log(context.getString(R.string.auth_captcha_bypass_success));
                        return RESULT.CONNECTED;
                    } else {
                        throw new Exception("Internet check failed");
                    }
                } catch (Exception ex) {
                    logger.log(Logger.LEVEL.DEBUG, ex);
                    logger.log(context.getString(R.string.auth_captcha_bypass_fail));
                }

                // Parsing captcha URL
                String captcha_url;
                try {
                    Element captcha_img = form.getElementsByTag("img").first();
                    captcha_url = redirect + captcha_img.attr("src");
                } catch (Exception ex) {
                    logger.log(String.format(
                            context.getString(R.string.error),
                            context.getString(R.string.auth_error_captcha_image))
                    );
                    logger.log(Logger.LEVEL.DEBUG, ex);
                    return RESULT.CAPTCHA;
                }

                // Asking user to enter the code
                String code = new CaptchaRunnable(captcha_url).getResult();
                if (code.isEmpty()) {
                    logger.log(String.format(
                            context.getString(R.string.error),
                            context.getString(R.string.auth_error_captcha))
                    );
                    return RESULT.CAPTCHA;
                }

                logger.log(Logger.LEVEL.DEBUG, String.format(
                        context.getString(R.string.auth_captcha_result), code
                ));

                // Sending captcha form
                logger.log(context.getString(R.string.auth_request));
                fields = Client.parseForm(form);
                fields.put("_rucaptcha", code);

                try {
                    client.post(redirect + form.attr("action"), fields, pref_retry_count);
                    logger.log(Logger.LEVEL.DEBUG, client.getPageContent().toString());
                } catch (Exception ex) {
                    logger.log(Logger.LEVEL.DEBUG, ex);
                    logger.log(String.format(
                            context.getString(R.string.error),
                            context.getString(R.string.auth_error_server)
                    ));
                    return RESULT.ERROR;
                }
            }
        }

        if (stopped) return RESULT.INTERRUPTED;
        progressListener.onProgressUpdate(50);

        logger.log(context.getString(R.string.auth_auth_form));
        try {
            switch (version) {
                case 1: client.post(redirect, fields, pref_retry_count); break;
                case 2:
                    String csrf_token = client.parseMetaContent("csrf-token");
                    client.setHeader(Client.HEADER_CSRF, csrf_token);
                    logger.log(Logger.LEVEL.DEBUG, "CSRF Token: " + csrf_token);

                    client.setCookie(redirect, "afVideoPassed", "0");
                    client.post(redirect + "/auth/init?segment=metro", null, pref_retry_count);
                    break;
            }
            logger.log(Logger.LEVEL.DEBUG, client.getPageContent().outerHtml());
        } catch (ProtocolException ignored) { // Too many follow-up requests
        } catch (Exception ex) {
            logger.log(Logger.LEVEL.DEBUG, ex);
            logger.log(String.format(
                    context.getString(R.string.error),
                    context.getString(R.string.auth_error_server)
            ));
            return RESULT.ERROR;
        }

        if (stopped) return RESULT.INTERRUPTED;
        progressListener.onProgressUpdate(75);

        logger.log(context.getString(R.string.auth_checking_connection));
        boolean status = false;
        switch (version) {
            case 1: status = (isConnected() == CHECK.CONNECTED); break;
            case 2:
                try {
                    JSONObject response = (JSONObject)new JSONParser().parse(client.getPage());
                    status = (Boolean)response.get("result");
                } catch (Exception ex) {
                    logger.log(Logger.LEVEL.DEBUG, ex);
                }
                break;
        }
        if (status) {
            logger.log(context.getString(R.string.auth_connected));
        } else {
            logger.log(String.format(
                    context.getString(R.string.error),
                    context.getString(R.string.auth_error_connection)
            ));
            return RESULT.ERROR;
        }

        progressListener.onProgressUpdate(100);

        return RESULT.CONNECTED;
    }
    
    @Override
    public CHECK isConnected() {
        Client client = new OkHttp().followRedirects(false);
        try {
            client.get("http://wi-fi.ru", null, pref_retry_count);
        } catch (Exception ex) {
            // Server not responding => wrong network
            logger.log(Logger.LEVEL.DEBUG, ex);
            return CHECK.WRONG_NETWORK;
        }

        try {
            redirect = client.parseMetaRedirect();
            logger.log(Logger.LEVEL.DEBUG, client.getPageContent().outerHtml());
            logger.log(Logger.LEVEL.DEBUG, redirect);
        } catch (Exception ex) {
            // Redirect not found => connected
            return super.isConnected();
        }

        if (redirect.contains("login.wi-fi.ru")) // Fallback to the first version
            version = 1;

        // Redirect found => not connected
        return CHECK.NOT_CONNECTED;
    }

    private class CaptchaRunnable implements Runnable {
        private boolean locked;
        private String result = "";
        private String url;

        public String getResult() {
            // Dialog can be created only on the Activity context
            if (context instanceof Activity) {
                locked = true;

                ((Activity)context).runOnUiThread(this);

                while (locked && !stopped) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                }
            }

            return result;
        }

        public CaptchaRunnable (String url) {
            this.url = url;
        }

        @Override
        public void run() {
            final Dialog dialog = new Dialog(context);
            dialog.setTitle(R.string.auth_captcha_dialog);
            dialog.setContentView(R.layout.captcha_dialog);

            final Button submit_button = (Button) dialog.findViewById(R.id.submit_button);

            final EditText text_captcha = (EditText) dialog.findViewById(R.id.text_captcha);
            text_captcha.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
                    if (i == EditorInfo.IME_ACTION_DONE) {
                        submit_button.performClick();
                        return true;
                    }
                    return false;
                }
            });

            final ImageView image_captcha = (ImageView) dialog.findViewById(R.id.image_captcha);
            image_captcha.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    new AsyncTask<Void,Void,Bitmap>() {
                        @Override
                        protected Bitmap doInBackground(Void... voids) {
                            try {
                                return BitmapFactory.decodeStream(client.getInputStream(url));
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                logger.log(Logger.LEVEL.DEBUG, ex);
                            }
                            return null;
                        }

                        @Override
                        protected void onPostExecute(Bitmap bitmap) {
                            if (bitmap != null) image_captcha.setImageBitmap(bitmap);
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });
            image_captcha.performClick();

            submit_button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    result = text_captcha.getText().toString();
                    locked = false;
                    dialog.hide();
                }
            });

            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialogInterface) {
                    locked = false;
                }
            });

            dialog.show();
        }
    }
}
