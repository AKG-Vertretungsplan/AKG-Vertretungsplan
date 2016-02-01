/*
 * Copyright (C) 2015-2016 SpiritCroc
 * Email: spiritcroc@gmail.com
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

package de.spiritcroc.akg_vertretungsplan;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.Log;
import android.widget.RemoteViews;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class DownloadService extends IntentService {
    public static final String ACTION_DOWNLOAD_PLAN = "de.spiritcroc.akg_vertretungsplan.action.downloadPlan";
    public static final String ACTION_RETRY = "de.spiritcroc.akg_vertretungsplan.action.retry";

    public static final String PLAN_1_ADDRESS = "http://www.sc.shuttle.de/sc/akg/Ver/";
    public static final String PLAN_2_ADDRESS = "https://akg-sc.selfip.org/INFOSCREEN/";
    public static final String PLAN_2_ADDRESS_1 = "https://akg-sc.selfip.org/INFOSCREEN/links.html?";
    public static final String PLAN_2_ADDRESS_2 = "https://akg-sc.selfip.org/INFOSCREEN/rechts.html?";
    public static final String CSS_ADDRESS = "http://www.sc.shuttle.de/sc/akg/Ver/willi.css";

    public static final String NO_PLAN = "**null**";

    public static final int NOTIFICATION_IMPORTANCE_RELEVANT = 3;
    public static final int NOTIFICATION_IMPORTANCE_GENERAL = 2;
    public static final int NOTIFICATION_IMPORTANCE_IRRELEVANT = 1;
    public static final int NOTIFICATION_IMPORTANCE_NONE = 0;

    public enum ContentType {AWAIT, IGNORE, HEADER, TABLE_START_FLAG, TABLE_END_FLAG, TABLE_ROW, TABLE_CONTENT}
    private String username, password;
    private SharedPreferences sharedPreferences;
    private boolean loginFailed = false;
    private static boolean downloading = false;
    private boolean skipLoginActivity = false;

    public DownloadService() {
        super("DownloadService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            OwnLog.add(getSharedPreferences(),
                    "DownloadService.onHandleIntent: got action: " + action);
            if (ACTION_DOWNLOAD_PLAN.equals(action)){
                if (getSharedPreferences().getBoolean("pref_reload_on_resume", false))
                    skipLoginActivity = true;// Don't prompt to login activity if coming from there
                getSharedPreferences().edit().putBoolean("pref_reload_on_resume", false)
                        .putBoolean("pref_last_offline", false).apply();
                OwnLog.add(getSharedPreferences(),
                        "DownloadService.onHandleIntent: set pref_last_offline false");

                if (sharedPreferences.getBoolean("pref_background_service", true))
                    BReceiver.startDownloadService(getApplicationContext(), false);//Schedule next download

                ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                if (networkInfo != null && networkInfo.isConnected()) {
                    if (!loginFailed) {
                        downloading = true;
                        updateLoadingInformation();
                        //hidden debug stuff start
                        int tmp = 0;
                        try {
                            tmp = Integer.parseInt(getSharedPreferences().getString("pref_debugging_enabled", "0"));
                        } catch (Exception e) {
                        }

                        if (tmp != 0)
                            dummyHandleActionDownloadPlan(tmp);
                        else//hidden debug stuff end
                            handleActionDownloadPlan();
                        downloading = false;
                    }
                }
                else{
                    getSharedPreferences().edit().putBoolean("pref_last_offline", true).apply();
                    loadOfflinePlan();
                    OwnLog.add(getSharedPreferences(),
                            "DownloadService.onHandleIntent: set pref_last_offline true");
                }
                updateLoadingInformation();
            }
            else if (ACTION_RETRY.equals(action)){
                loginFailed = false;
            }
        }
    }
    private SharedPreferences getSharedPreferences(){
        if (sharedPreferences == null)
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        return sharedPreferences;
    }
    public static boolean isDownloading(){
        return downloading;
    }

    private void handleActionDownloadPlan() {
        setTextViewText(getString(R.string.loading));
        Tools.updateWidgets(this);
        maybeSaveFormattedPlan();
        // Dismiss wrong userdata notifications
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(2);
        try {
            //int plan = Integer.parseInt(getSharedPreferences().getString("pref_plan", "1"));
            //switch (plan) {
            //    case 1:
            //    {
                    username = getSharedPreferences().getString("pref_username", "");
                    password = getSharedPreferences().getString("pref_password", "");
                    String base64EncodedCredentials = Base64.encodeToString((username + ":" + password).getBytes("US-ASCII"), Base64.URL_SAFE | Base64.NO_WRAP);
                    DefaultHttpClient httpClient = new DefaultHttpClient();//On purpose use deprecated stuff because it works better (I have problems with HttpURLConnection: it does not read the credentials each time they are needed, and if they are wrong, there is no appropriate message (just an java.io.FileNotFoundException))
                    HttpGet httpGet = new HttpGet(PLAN_1_ADDRESS);
                    httpGet.setHeader("Authorization", "Basic " + base64EncodedCredentials);
                    String result = EntityUtils.toString(httpClient.execute(httpGet).getEntity());
                    httpGet = new HttpGet(CSS_ADDRESS);
                    httpGet.setHeader("Authorization", "Basic " + base64EncodedCredentials);
                    //sharedPreferences.edit().remove("pref_web_plan_custom_style").apply();// test default
                    String css = EntityUtils.toString(httpClient.execute(httpGet).getEntity());
                    processPlan(result, css);
            //      break;
            //    }
            //    case 2:
            //    {
            //        String result = getPlanInsecure(PLAN_2_ADDRESS_1) + getPlanInsecure(PLAN_2_ADDRESS_2);
            //        processPlan(result);
            //        break;
            //    }
            //}
            getSharedPreferences().edit().putInt("last_plan_type", 1).apply();
        }
        catch (UnknownHostException e){
            loadOfflinePlan();
        }
        catch (IOException e){
            Log.e("downloadPlan", "Got Exception " + e);
            showText(getString(R.string.error_download_error));
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    private String getPlanInsecure(String address) {
        // The infoscreen site does not provide a verified certificate, but uses https
        String result = "";
        HttpURLConnection connection;
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){
                return null;
            }
            @Override
            public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException{}
            @Override
            public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException{}
        }};
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            URL url = new URL(address);
            URLConnection urlConnection = url.openConnection();
            if (urlConnection instanceof HttpsURLConnection){
                HttpsURLConnection httpsURLConnection = (HttpsURLConnection) urlConnection;
                httpsURLConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                httpsURLConnection.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
                connection = httpsURLConnection;
            } else {
                connection = (HttpURLConnection) urlConnection;
            }
            try {
                int buffer;
                InputStream in = new BufferedInputStream(connection.getInputStream());
                while (true) {
                    buffer = in.read();

                    if (buffer == -1) {
                        break;
                    }
                    result += (char) buffer;
                }
                in.close();
            } finally {
                connection.disconnect();
            }
        }  catch (IOException |NoSuchAlgorithmException |KeyManagementException e) {
            Log.i("DownloadService", "getPlanInsecure(" + address + "): Got exception " + e);
        }
        return result;
    }

    private void maybeSaveFormattedPlan(){
        if (!getSharedPreferences().getBoolean("pref_unseen_changes", false) && getSharedPreferences().getString("pref_auto_mark_read", "").equals("onPlanReloaded")){
            markPlanRead(this);
        }
    }

    public static void markPlanRead(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("pref_latest_title_1", sharedPreferences.getString("pref_current_title_1", ""));
        editor.putString("pref_latest_plan_1", sharedPreferences.getString("pref_current_plan_1", ""));
        editor.putString("pref_latest_title_2", sharedPreferences.getString("pref_current_title_2", ""));
        editor.putString("pref_latest_plan_2", sharedPreferences.getString("pref_current_plan_2", ""));
        editor.apply();
        updateNavigationDrawerInformation(context);
    }

    private void processPlan(String result, String css){
        String latestHtml = getSharedPreferences().getString("pref_html_latest", "");
        boolean newVersion = false;
        if (result.contains("401 Authorization Required")) {
            showText((username.equals("") ? getString(R.string.enter_userdata) : getString(R.string.correct_userdata)));
            loginFailed = true;
            if (IsRunningSingleton.getInstance().isRunning()) {
                startLoginActivity();
            } else {
                postLoginNotification();
            }
        } else {
            String time = timeAndDateToString(Calendar.getInstance());
            SharedPreferences.Editor editor = getSharedPreferences().edit();
            editor.putBoolean("pref_illegal_plan", false).apply();//there are some textView updates below, and we don't know yet whether plan is illegal, so just pretend it is not for now and handle stuff later
            editor.putString("pref_last_checked", time);
            String latestContent = getContentFromHtml(latestHtml),
                    currentContent = getContentFromHtml(result);
            if (latestHtml.equals("") || !currentContent.equals(latestContent)) {    //site has changed or nothing saved yet
                if (newVersionNotify(latestContent, currentContent)){   //are new entries available?
                    newVersion = true;
                    setTextViewText(getString(R.string.new_version));
                    editor.putString("pref_last_update", time);
                    editor.putBoolean("pref_unseen_changes", true);
                }
                else{
                    if (Integer.parseInt(getSharedPreferences().getString("pref_no_change_since_max_precision", "2")) > 0)
                        setTextViewText(getString(R.string.no_change_for) + " " + compareTime(stringToCalendar(getSharedPreferences().getString("pref_last_update", "???")), Calendar.getInstance()));
                    else
                        setTextViewText(getString(R.string.no_change));
                }
            }
            else {
                if (Integer.parseInt(getSharedPreferences().getString("pref_no_change_since_max_precision", "2")) > 0)
                    setTextViewText(getString(R.string.no_change_for) + " " + compareTime(stringToCalendar(getSharedPreferences().getString("pref_last_update", "???")), Calendar.getInstance()));
                else
                    setTextViewText(getString(R.string.no_change));
            }
            editor.putString("pref_html_latest", result);
            editor.putString("pref_css", css);
            editor.apply();
            loadWebViewData(result);
            if (!loadFormattedPlans(result))
                setTextViewText(getString(R.string.error_illegal_plan));
            if (newVersion) {//Notification after loadFormattedPlans, because getNewRelevantInformationCount depends on it
                ArrayList<String> relevantInformation = new ArrayList<>();
                ArrayList<String> generalInformation = new ArrayList<>();
                ArrayList<String> irrelevantInformation = new ArrayList<>();
                int newRelevantNotificationCount = getNewRelevantInformationCount(this, relevantInformation, generalInformation, irrelevantInformation);

                String message = null;
                int importance = NOTIFICATION_IMPORTANCE_NONE;
                int count = 0;
                if (newRelevantNotificationCount > 0) {
                    message = getResources().getQuantityString(R.plurals.new_relevant_information_chain, newRelevantNotificationCount, newRelevantNotificationCount);
                    importance = NOTIFICATION_IMPORTANCE_RELEVANT;
                    count += newRelevantNotificationCount;
                }
                boolean relevantOnly = sharedPreferences.getBoolean("pref_notification_only_if_relevant", false);
                if (!generalInformation.isEmpty() && (!relevantOnly || !sharedPreferences.getBoolean("pref_notification_general_not_relevant", false))) {
                    if (message == null) {
                        importance = NOTIFICATION_IMPORTANCE_GENERAL;
                        message = "";
                    } else {
                        message += getString(R.string.new_information_chain_separator);
                    }
                    message += getResources().getQuantityString(R.plurals.new_general_information_chain, generalInformation.size(), generalInformation.size());
                    count += generalInformation.size();
                }
                if (!irrelevantInformation.isEmpty() && !relevantOnly) {
                    if (message == null) {
                        importance = NOTIFICATION_IMPORTANCE_IRRELEVANT;
                        message = getResources().getQuantityString(R.plurals.new_irrelevant_information, irrelevantInformation.size(), irrelevantInformation.size());
                    } else {
                        message += getString(R.string.new_information_chain_separator) +
                                getResources().getQuantityString(R.plurals.new_irrelevant_information_chain, irrelevantInformation.size(), irrelevantInformation.size());
                    }
                    count += irrelevantInformation.size();
                }
                if (message == null) {
                    message = getString(R.string.last_checked) + " " + time;
                } else if (importance != NOTIFICATION_IMPORTANCE_IRRELEVANT){
                    message += getResources().getQuantityString(R.plurals.new_information_chain_end, count);
                }
                if (count > 0) {
                    maybePostNotification(getString(R.string.new_version), message, importance, relevantInformation, generalInformation, irrelevantInformation);
                }
                if (newRelevantNotificationCount > 0 || generalInformation.size() > 0 || irrelevantInformation.size() > 0)
                    setTextViewText(message);

                if (sharedPreferences.getBoolean("pref_tesla_unread_enable", true)){
                    int fullCount = newRelevantNotificationCount;
                    if (sharedPreferences.getBoolean("pref_tesla_unread_use_complete_count", false))
                        fullCount += generalInformation.size() + irrelevantInformation.size();
                    else if (sharedPreferences.getBoolean("pref_tesla_unread_include_general_information_count", true))
                        fullCount += generalInformation.size();
                    if (!IsRunningSingleton.getInstance().isRunning()){
                        try{
                            ContentValues contentValues = new ContentValues();
                            contentValues.put("tag", "de.spiritcroc.akg_vertretungsplan/.FormattedActivity");
                            contentValues.put("count", fullCount);
                            getContentResolver().insert(Uri.parse("content://com.teslacoilsw.notifier/unread_count"), contentValues);
                        }
                        catch (IllegalArgumentException e){
                            Log.d("DownloadService", "TeslaUnread is not installed");
                        }
                        catch (Exception e){
                            Log.e("DownloadService", "Got exception while trying to sending count to TeslaUnread: " + e);
                        }
                    }
                }
            }
        }
        Tools.updateWidgets(this);
        updateNavigationDrawerInformation(this);
    }
    private void loadOfflinePlan(){
        String latestHtml = getSharedPreferences().getString("pref_html_latest", "");
        if (latestHtml.equals(""))  //first download
            setTextViewText(getString(R.string.error_could_not_load));
        else {
            setTextViewText(getString(R.string.showing_offline) + " (" + getSharedPreferences().getString("pref_last_checked", "???") + ")");
            //loadWebViewData(latestHtml);  //no need for loading again, as already used on creation of activities
            //getPlans(latestHtml);
            Tools.updateWidgets(this);
        }
    }
    private void loadWebViewData(String data){
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "loadWebViewData");
        intent.putExtra("data", data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    private void loadFragmentData(String title1, String plan1, String title2, String plan2){
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "loadFragmentData");
        intent.putExtra("title1", title1);
        intent.putExtra("plan1", plan1);
        intent.putExtra("title2", title2);
        intent.putExtra("plan2", plan2);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    private void setTextViewText(String text){
        SharedPreferences.Editor editor = getSharedPreferences().edit();
        editor.putString("pref_text_view_text", text);
        editor.apply();
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "setTextViewText");
        intent.putExtra("text", text);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    private void showText(String text){   //toast+textView
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "showToast");
        intent.putExtra("text", text);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        setTextViewText(text);
    }
    public static void updateNavigationDrawerInformation(Context context) {
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "updateNavigationDrawerInformation");
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
    private void updateLoadingInformation() {
        Intent intent = new Intent("PlanDownloadServiceUpdate");
        intent.putExtra("action", "updateLoadingInformation");
        intent.putExtra("loading", isDownloading());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void startLoginActivity() {
        if (skipLoginActivity) {
            // Only skip once
            skipLoginActivity = false;
        } else {
            startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getSharedPreferences().edit().putBoolean("pref_reload_on_resume", true).apply();//reload on resume of FormattedActivity
        }
    }
    private void postLoginNotification() {
        postNotification(getString(R.string.wrong_userdata), getString(R.string.enter_userdata), 2, R.drawable.ic_stat_notify_wrong_credentials, SettingsActivity.class, true, NOTIFICATION_IMPORTANCE_NONE, null, null, null);
    }

    public static String timeAndDateToString (Calendar calendar){
        String minute = "" + calendar.get(Calendar.MINUTE);
        while (minute.length() < 2)
            minute = "0"+minute;
        String second = "" + calendar.get(Calendar.SECOND);
        while (second.length() < 2)
            second = "0"+second;
        return (calendar.get(Calendar.DAY_OF_MONTH)+"."+(calendar.get(Calendar.MONTH)+1)+"."+calendar.get(Calendar.YEAR) + " " + calendar.get(Calendar.HOUR_OF_DAY)+":"+minute+":"+second);
    }
    public static GregorianCalendar stringToCalendar(String string){
        String separated[] = {"", "", "", "", "", ""};
        int index = 0;
        boolean row = false;
        for (int i = 0; i < string.length() && index < separated.length; i++){
            if (string.charAt(i)>='0'&&string.charAt(i)<='9'){
                row = true;
                separated[index] += string.charAt(i);
            }
            else {
                if (row)
                    index++;
                row = false;
            }
        }
        try {
            return new GregorianCalendar(Integer.parseInt(separated[2]), Integer.parseInt(separated[1]) - 1, Integer.parseInt(separated[0]), Integer.parseInt(separated[3]), Integer.parseInt(separated[4]), Integer.parseInt(separated[5]));
        }
        catch (Exception e){
            Log.e("stringToCalendar", "" + e);
            return null;
        }
    }
    private String compareTime(Calendar first, Calendar second){
        if (first == null || second == null){
            Log.e("compareTime", "calendar == null");
            return getString(R.string.error_unknown);
        }
        int precision = 0;
        long difference = (second.getTime().getTime() - first.getTime().getTime())/1000;       //result: difference in seconds | yes, the .getTime().getTime() is on purpose
        if (difference < 0)
            difference *= -1;
        String[] everythingIn = new String[4];  //seconds, minutes, hours, days

        long tmp = difference % 60; //→seconds
        difference /= 60;   //→minutes
        if (tmp == 0)
            everythingIn[0] = "";
        else {
            everythingIn[0] = getResources().getQuantityString(R.plurals.plural_second, (int) tmp, (int) tmp);
            precision = 1;
        }

        tmp = difference % 60;  //→minutes
        difference /= 60;   //→hours
        if (tmp == 0)
            everythingIn[1] = "";
        else {
            everythingIn[1] = getResources().getQuantityString(R.plurals.plural_minute, (int) tmp, (int) tmp);
            precision = 2;
        }

        tmp = difference % 24;  //→hours
        difference /= 24;   //→days
        if (tmp == 0)
            everythingIn[2] = "";
        else {
            everythingIn[2] = getResources().getQuantityString(R.plurals.plural_hour, (int) tmp, (int) tmp);
            precision = 3;
        }

        if (difference == 0)
            everythingIn[3] = "";
        else {
            everythingIn[3] = getResources().getQuantityString(R.plurals.plural_day, (int) difference, (int) difference);
            precision = 4;
        }

        //shorten due to maxPrecision
        for (int i = 0; precision > Integer.parseInt(getSharedPreferences().getString("pref_no_change_since_max_precision", "2")) && i < everythingIn.length; i++){
            precision--;
            everythingIn[i] = "";
        }
        String result = (everythingIn[3].length()!=0 ? everythingIn[3] + " " : "") + (everythingIn[2].length()!=0 ? everythingIn[2] + " " : "") + (everythingIn[1].length()!=0 ? everythingIn[1] + " " : "") + (everythingIn[0].length()!=0 ? everythingIn[0] + " " : "");
        if (result.charAt(result.length()-1) == ' ')
            result = result.substring(0, result.length()-1);
        return result;
    }
    private String convertSpecialChars(String html){
        html = html.replace("&quot;", "\"");
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");
        html = html.replace("&nbsp;", " ");
        html = html.replace("&Auml;", "Ä");
        html = html.replace("&Ouml;", "Ö");
        html = html.replace("&Uuml;", "Ü");
        html = html.replace("&auml;", "ä");
        html = html.replace("&ouml;", "ö");
        html = html.replace("&uuml;", "ü");
        html = html.replace("&szlig;", "ß");  //add more if needed: http://de.selfhtml.org/html/referenz/zeichen.htm

        /*html = html.replace("&Agrave;", "À");     //unneeded, automatically translates unlike German Umlaute
        html = html.replace("&Egrave;", "È");
        html = html.replace("&Igrave;", "Ì");
        html = html.replace("&Ograve;", "Ò");
        html = html.replace("&Ugrave;", "Ù");
        html = html.replace("&agrave;", "à");
        html = html.replace("&egrave;", "è");
        html = html.replace("&igrave;", "ì");
        html = html.replace("&ograve;", "ò");
        html = html.replace("&ugrave;", "ù");

        html = html.replace("&Aacute;", "Á");
        html = html.replace("&Eacute;", "É");
        html = html.replace("&Iacute;", "Í");
        html = html.replace("&Oacute;", "Ó");
        html = html.replace("&Uacute;", "Ú");
        html = html.replace("&aacute;", "á");
        html = html.replace("&eacute;", "é");
        html = html.replace("&iacute;", "í");
        html = html.replace("&oacute;", "ó");
        html = html.replace("&uacute;", "ú");

        html = html.replace("&Acirc;", "Â");
        html = html.replace("&Ecirc;", "Ê");
        html = html.replace("&Icirc;", "Î");
        html = html.replace("&Ocirc;", "Ô");
        html = html.replace("&Ucirc;", "Û");
        html = html.replace("&acirc;", "â");
        html = html.replace("&ecirc;", "ê");
        html = html.replace("&icirc;", "î");
        html = html.replace("&ocirc;", "ô");
        html = html.replace("&ucirc;", "û");*/
        return html;
    }
    private boolean loadFormattedPlans(String html){
        String title1, plan1, title2, plan2;
        plan1 = convertSpecialChars(getContentFromHtml(html));

        SharedPreferences.Editor editor = getSharedPreferences().edit();

        //remove spacers from empty cells
        for (int i = 0; i < plan1.length()-1; i++){
            if ((plan1.charAt(i)=='¡' || plan1.charAt(i)=='¿') && plan1.charAt(i+1) == ' ')
                plan1=plan1.substring(0,i+1)+plan1.substring(i+2);
        }

        String searchingFor = ContentType.TABLE_START_FLAG + "¡Vertretungsplan für ";
        int index = plan1.indexOf(searchingFor);
        if (index == -1){
            Log.e("getHeadsAndDivide", "Could not find " + searchingFor);
            editor.putBoolean("pref_illegal_plan", true).apply();
            return false;
        }
        else
            title1 = Tools.getLine(plan1.substring(index + searchingFor.length()), 1);

        searchingFor = ContentType.TABLE_START_FLAG + "¡Vertretungsplan für ";
        index = plan1.indexOf(searchingFor, index + searchingFor.length());
        if (index == -1){
            Log.d("getHeadsAndDivide", "Could not find " + searchingFor + " twice");
            title2 = NO_PLAN;
            plan2 = NO_PLAN;
        }
        else {
            title2 = Tools.getLine(plan1.substring(index + searchingFor.length()), 1);
            plan2 = plan1.substring(index);
            plan1 = plan1.substring(0, index);
        }
        editor.putString("pref_current_title_1", title1);
        editor.putString("pref_current_plan_1", plan1);
        editor.putString("pref_current_title_2", title2);
        editor.putString("pref_current_plan_2", plan2);
        editor.putBoolean("pref_illegal_plan", false);
        editor.apply();
        loadFragmentData(title1, plan1, title2, plan2);
        return true;
    }

    //output format: type, specific content: normal spacer = ¡, fat content spacer: ¿ \n
    private String getContentFromHtml (String html){
        boolean bracketsOpen = false, newRowspan=false;
        String tmpHeader = "", tmpRowSpanningHeader="";
        int rowSpanNumber = 0;
        ContentType contentType = ContentType.AWAIT;
        String result = "", bracketContent = "";
        for (int index = 0; index < html.length(); index++){
            if (html.charAt(index) == '<'){
                bracketsOpen = true;
                bracketContent = "" + html.charAt(index);
            }
            else if (bracketsOpen && html.charAt(index) == '>') {
                bracketsOpen = false;
                bracketContent += html.charAt(index);

                //use bracket-information
                if (bracketContent.length() >= 2 && bracketContent.substring(0,2).equals("<a"))             //ignore links
                    contentType = ContentType.IGNORE;
                else if (bracketContent.equals("</a>"))
                    contentType = ContentType.AWAIT;
                else if (bracketContent.length() == 4 && bracketContent.substring(0,2).equals("<h")) {      //header=readable name for table
                    contentType = ContentType.HEADER;
                    tmpHeader = "";
                }
                else if (bracketContent.length() == 5 && bracketContent.substring(0,3).equals("</h"))       //header end
                    contentType = ContentType.AWAIT;
                else if (bracketContent.length() >= 7 && bracketContent.substring(0,6).equals("<table"))    //table
                    result += ContentType.TABLE_START_FLAG + "¡" + tmpHeader + "\n";
                    //else if (bracketContent.length() >= 8 && bracketContent.substring(0,7).equals("</table"))   //table end
                    //result += contentType.TABLE_END_FLAG + "\n";      //TABLE_END_FLAG not needed anymore
                else if (bracketContent.length() >= 4 && bracketContent.substring(0,3).equals("<tr")) {     //table row
                    result += ContentType.TABLE_ROW;
                    if (rowSpanNumber>0){
                        result += "¿" + tmpRowSpanningHeader;
                        rowSpanNumber--;
                    }
                }
                else if (bracketContent.equals("</tr>")){                                                   //table row end
                    result += "\n";
                }
                else if (bracketContent.length() >= 4 && bracketContent.substring(0,3).equals("<th")) {      //table header
                    int rowspan = bracketContent.indexOf("rowspan");
                    if (rowspan != -1){
                        rowspan = bracketContent.indexOf('"', rowspan)+1;
                        int end = bracketContent.indexOf('"', rowspan);
                        if (rowspan==-1 || end==-1){
                            Log.e("getContentFromHtml: ", "Could find String rowspan, but not the value");
                            break;
                        }
                        rowSpanNumber = Integer.parseInt(bracketContent.substring(rowspan, end))-1;
                        newRowspan = true;
                        tmpRowSpanningHeader = "";
                    }
                    contentType = ContentType.TABLE_CONTENT;
                    result += "¿";
                }
                else if (bracketContent.equals("</th>")) {                                                   //table header end
                    contentType = ContentType.AWAIT;
                    newRowspan = false;
                }
                else if (bracketContent.length() >= 4 && bracketContent.substring(0,3).equals("<td")){      //table data
                    contentType = ContentType.TABLE_CONTENT;
                    result += "¡";
                }
                else if (bracketContent.equals("</td>"))                                                    //table data end
                    contentType = ContentType.AWAIT;

            }
            else if (bracketsOpen){
                bracketContent += html.charAt(index);
            }
            else if (contentType.equals(ContentType.HEADER))
                tmpHeader += html.charAt(index);
            else if (contentType.equals(ContentType.TABLE_CONTENT) && html.charAt(index)!='¿' && (html.charAt(index) >= ' ' && html.charAt(index) <= '~' || html.charAt(index) >= '¡')) {
                result += html.charAt(index);

                if (newRowspan)
                    tmpRowSpanningHeader += html.charAt(index);
            }
        }
        return result;
    }

    private boolean newVersionNotify(String latestContent, String currentContent){
        if (latestContent.length() != currentContent.length())
            return true;
        String tmp = "a";   //not empty
        for (int i = 0; !tmp.equals(""); i++) {
            tmp = Tools.getLine(currentContent, i + 1);
            if (!Tools.lineAvailable(latestContent, tmp)) {//new plan available
                if ("".equals(Tools.getCellContent(tmp, 2))){//if only one cell
                    if (!Tools.ignoreSubstitution(Tools.getCellContent(tmp, 1)))
                        return true;
                }
                else
                    return true;
            }
        }
        return false;
    }

    private void maybePostNotification(String title, String text, int importance, ArrayList<String> relevantInformation, ArrayList<String> generalInformation, @Nullable ArrayList<String> irrelevantInformation) {
        if (!IsRunningSingleton.getInstance().isRunning() && getSharedPreferences().getBoolean("pref_notification_enabled", false))
            postNotification(title, text, 1, R.drawable.ic_stat_notify_plan_update, FormattedActivity.class, false, importance, relevantInformation, generalInformation, irrelevantInformation);
    }
    private void postNotification(String title, @Nullable String text, int id, int smallIconResource, Class touchActivity, boolean silent, int importance, @Nullable ArrayList<String> relevantInformation, @Nullable ArrayList<String> generalInformation, @Nullable ArrayList<String> irrelevantInformation) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setSmallIcon(smallIconResource).setContentTitle(title);
        if (text != null)
            builder.setContentText(text);
        if (!silent) {
            if (getSharedPreferences().getBoolean("pref_notification_sound_enabled", false)) {
                String notificationSound = getSharedPreferences().getString("pref_notification_sound", "");
                if (notificationSound.equals(""))
                    builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                else
                    builder.setSound(Uri.parse(notificationSound));
            }
            if (getSharedPreferences().getBoolean("pref_led_notification_enabled", false))
                builder.setLights(Integer.parseInt(getSharedPreferences().getString("pref_led_notification_color", "-1")), 500, 500);
            if (getSharedPreferences().getBoolean("pref_vibrate_notification_enabled", false)) {
                long[] vibrationPattern;
                switch (importance) {
                    case NOTIFICATION_IMPORTANCE_RELEVANT:
                        vibrationPattern = new long[] {50, 500, 250, 500};
                        break;
                    case NOTIFICATION_IMPORTANCE_GENERAL:
                        vibrationPattern = new long[] {50, 500};
                        break;
                    case NOTIFICATION_IMPORTANCE_IRRELEVANT:
                        vibrationPattern = new long[] {50, 200};
                        break;
                    default:
                    case NOTIFICATION_IMPORTANCE_NONE:
                        vibrationPattern = new long[] {50, 100};
                        break;
                }
                builder.setVibrate(vibrationPattern);
            }
            NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
            int lineCount = 0;
            if (relevantInformation != null) {
                int color = Integer.parseInt(sharedPreferences.getString("pref_notification_preview_relevant_color", "" + Color.RED));
                for (int i = 0; i < relevantInformation.size(); i++) {
                    Spannable s = new SpannableString(relevantInformation.get(i));
                    s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    StyleSpan styleSpan = Tools.getStyleSpanFromPref(this, sharedPreferences.getString("pref_notification_preview_relevant_style", getString(R.string.pref_text_style_normal_value)));
                    if (styleSpan != null) {
                        s.setSpan(styleSpan, 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    }
                    inboxStyle.addLine(s);
                    lineCount++;
                }
            }
            boolean relevantOnly = sharedPreferences.getBoolean("pref_notification_only_if_relevant", false);
            if ((!relevantOnly || !sharedPreferences.getBoolean("pref_notification_general_not_relevant", false)) && generalInformation != null) {
                int color = Integer.parseInt(sharedPreferences.getString("pref_notification_preview_general_color", "" + getString(R.string.pref_color_orange)));
                for (int i = 0; i < generalInformation.size(); i++) {
                    Spannable s = new SpannableString(generalInformation.get(i));
                    s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    StyleSpan styleSpan = Tools.getStyleSpanFromPref(this, sharedPreferences.getString("pref_notification_preview_general_style", getString(R.string.pref_text_style_normal_value)));
                    if (styleSpan != null) {
                        s.setSpan(styleSpan, 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    }
                    inboxStyle.addLine(s);
                    lineCount++;
                }
            }
            if (!relevantOnly && irrelevantInformation != null) {
                int color = Integer.parseInt(sharedPreferences.getString("pref_notification_preview_irrelevant_color", "" + Color.DKGRAY));
                for (int i = 0; i < irrelevantInformation.size(); i++) {
                    Spannable s = new SpannableString(irrelevantInformation.get(i));
                    s.setSpan(new ForegroundColorSpan(color), 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    StyleSpan styleSpan = Tools.getStyleSpanFromPref(this, sharedPreferences.getString("pref_notification_preview_irrelevant_style", getString(R.string.pref_text_style_normal_value)));
                    if (styleSpan != null) {
                        s.setSpan(styleSpan, 0, s.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    }
                    inboxStyle.addLine(s);
                    lineCount++;
                }
            }

            boolean showMarkSeen = false;
            String markSeenPref = sharedPreferences.getString("pref_notification_button_mark_seen", getString(R.string.pref_notification_button_mark_seen_if_max_5_value));
            if (getString(R.string.pref_notification_button_mark_seen_always_value).equals(markSeenPref)) {
                showMarkSeen = true;
            } else if (getString(R.string.pref_notification_button_mark_seen_if_max_5_value).equals(markSeenPref)) {
                showMarkSeen = lineCount <= 5;
            } else if (getString(R.string.pref_notification_button_mark_seen_if_max_7_value).equals(markSeenPref)) {
                showMarkSeen = lineCount <= 7;
            }
            if (showMarkSeen) {
                // All information is shown
                Intent clickIntent = new Intent(this, BReceiver.class).setAction(BReceiver.ACTION_MARK_SEEN);
                PendingIntent clickPendingIntent = PendingIntent.getBroadcast(this, 1, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(R.drawable.ic_done_black_36dp, getString(R.string.mark_seen), clickPendingIntent);
            }
            if (text != null) {
                inboxStyle.setSummaryText(text);
            }

            builder.setStyle(inboxStyle);
        }

        Intent resultIntent = new Intent(this, touchActivity);
        android.support.v4.app.TaskStackBuilder stackBuilder = android.support.v4.app.TaskStackBuilder.create(this);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(resultPendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 21) {
            //Disable heads up
            notification.headsUpContentView = new RemoteViews(Parcel.obtain());
        }
        notificationManager.notify(id, notification);
    }

    public static int getNewRelevantInformationCount(Context context, ArrayList<String> relevantInformation, ArrayList<String> generalInformation, ArrayList<String> irrelevantInformation) {
        return getNewRelevantInformationCount(context, relevantInformation, generalInformation, irrelevantInformation, new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>());
    }

    public static int getNewRelevantInformationCount(Context context,
                                                     ArrayList<String> relevantInformation, ArrayList<String> generalInformation, ArrayList<String> irrelevantInformation,
                                                     ArrayList<String> allRelevant, ArrayList<String> allGeneral, ArrayList<String> allIrrelevant) {
        relevantInformation.clear();
        generalInformation.clear();
        irrelevantInformation.clear();
        allRelevant.clear();
        allGeneral.clear();
        allIrrelevant.clear();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        int count = getNewRelevantInformationCount(context, sharedPreferences.getString("pref_current_plan_1", ""), sharedPreferences.getString("pref_current_title_1", ""), relevantInformation, generalInformation, irrelevantInformation, allRelevant, allGeneral, allIrrelevant) +
                getNewRelevantInformationCount(context, sharedPreferences.getString("pref_current_plan_2", ""), sharedPreferences.getString("pref_current_title_2", ""), relevantInformation, generalInformation, irrelevantInformation, allRelevant, allGeneral, allIrrelevant);
        if (!LessonPlan.getInstance(sharedPreferences).isConfigured()){
            ArrayList<String> irrelevantCopy = (ArrayList<String>) irrelevantInformation.clone();
            irrelevantInformation.clear();
            irrelevantInformation.addAll(generalInformation);
            irrelevantInformation.addAll(irrelevantCopy);
            generalInformation.clear();
            ArrayList<String> allIrrelevantCopy = (ArrayList<String>) allIrrelevant.clone();
            allIrrelevant.clear();
            allIrrelevant.addAll(allGeneral);
            allIrrelevant.addAll(allIrrelevantCopy);
            allGeneral.clear();
            count = 0;
        }
        return count;
    }
    private static int getNewRelevantInformationCount(Context context, String currentContent, String title,
                                                      ArrayList<String> relevantInformation, ArrayList<String> generalInformation, ArrayList<String> irrelevantInformation,
                                                      ArrayList<String> allRelevant, ArrayList<String> allGeneral, ArrayList<String> allIrrelevant) {
        if (title.equals(NO_PLAN)) {
            return 0;
        }
        String latestContent;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (title.equals(sharedPreferences.getString("pref_latest_title_1", "")))    //check date for comparison
            latestContent = sharedPreferences.getString("pref_latest_plan_1", "");
        else if (title.equals(sharedPreferences.getString("pref_latest_title_2", "")))
            latestContent = sharedPreferences.getString("pref_latest_plan_2", "");
        else
            latestContent = "";

        Calendar calendar = Tools.getDateFromPlanTitle(title);
        if (calendar == null){
            Log.e("DownloadService", "getNewRelevantInformationCount: Could not read calendar " + title);
            return 0;
        }

        int count = 0, tmpCellCount;
        String tmp = "a";   //not empty
        String[] tmpRowContent = new String[ItemFragment.cellCount];
        LessonPlan lessonPlan = LessonPlan.getInstance(sharedPreferences);
        for (int i = 1; !tmp.equals(""); i++) {
            tmp = Tools.getLine(currentContent, i);
            String comparison = tmp;//to compare whether information is new

            String searchingFor = "" + DownloadService.ContentType.TABLE_ROW;

            if (tmp.length() > searchingFor.length()+1 && tmp.substring(0, searchingFor.length()).equals(searchingFor)) { //ignore empty rows
                tmpCellCount = 0;
                tmp = tmp.substring(searchingFor.length());
                if (Tools.countHeaderCells(tmp)<=1) {//ignore headerRows
                    for (int j = 0; j < tmpRowContent.length; j++) {
                        tmpRowContent[j] = Tools.getCellContent(tmp, j+1);
                        if (!tmpRowContent[j].equals(""))
                            tmpCellCount++;
                    }

                    if (tmpCellCount <= 2) {//general info for whole school
                        if (tmpCellCount == 1) {
                            if (!Tools.ignoreSubstitution(tmpRowContent[0])) {
                                String item = ItemFragment.createItem(context, tmpRowContent, true);
                                allGeneral.add(item);
                                if (!Tools.lineAvailable(latestContent, comparison)) {
                                    generalInformation.add(item);
                                }
                            }
                        } else {
                            String item = ItemFragment.createItem(context, tmpRowContent, true);
                            allGeneral.add(item);
                            if (!Tools.lineAvailable(latestContent, comparison)) {
                                generalInformation.add(item);
                            }
                        }
                    }
                    else {
                        try {
                            if (lessonPlan.isRelevant(tmpRowContent[0], calendar.get(Calendar.DAY_OF_WEEK), Integer.parseInt(tmpRowContent[2]), tmpRowContent[1])) {
                                String item = ItemFragment.createItem(context, tmpRowContent, false);
                                allRelevant.add(item);
                                if (!Tools.lineAvailable(latestContent, comparison)) {
                                    count++;
                                    relevantInformation.add(item);
                                }
                            } else {
                                String item = ItemFragment.createItem(context, tmpRowContent, false);
                                allIrrelevant.add(item);
                                if (!Tools.lineAvailable(latestContent, comparison)) {
                                    irrelevantInformation.add(item);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("DownloadService", "getNewRelevantInformationCount: Got exception while checking for relevancy: " + e);
                        }
                    }
                }
            }
        }
        return count;
    }


    //hidden debug plans:
    private void dummyHandleActionDownloadPlan(int no){
        setTextViewText(getString(R.string.loading));
        Tools.updateWidgets(this);
        maybeSaveFormattedPlan();
        String result = "";
        switch (no){
            case -1:
                // Not actually dummy, but infoscreen
                result = getPlanInsecure(PLAN_2_ADDRESS_1) + getPlanInsecure(PLAN_2_ADDRESS_2);
                break;
            case 1:
                result += DummyPlans.dummy1;
                break;
            case 2:
                result += DummyPlans.dummy2;
                break;
            case 3:
                result += DummyPlans.dummy3;
                break;
            case 4:
                result += DummyPlans.dummy4;
                break;
            case 5:
                result += DummyPlans.dummy5;
                break;
            case 6:
                result += DummyPlans.dummy6;
                break;
            case 7:
                result += DummyPlans.dummy7;
                break;
            case 8:
                result += DummyPlans.dummy8;
                break;
            case 9:
                result += DummyPlans.dummy9;
                break;
        }

        processPlan(result, getString(R.string.web_plan_custom_style_akg_default));
    }
}
