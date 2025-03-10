package com.truckmovers.cordova;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.util.Base64;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebViewEngine;
import org.apache.cordova.LOG;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

public class RemoteInjectionPlugin extends CordovaPlugin {
    private static String TAG = "RemoteInjectionPlugin";
    private static Pattern REMOTE_URL_REGEX = Pattern.compile("^http(s)?://.*");

    // List of files to inject before injecting Cordova.
    private final ArrayList<String> preInjectionFileNames = new ArrayList<String>();
    private int promptInterval; // Delay before prompting user to retry in seconds
    private String errorPage;

    private RequestLifecycle lifecycle;

    protected void pluginInitialize() {
        String pref = webView.getPreferences().getString("CRIInjectFirstFiles", "");
        for (String path : pref.split(",")) {
            preInjectionFileNames.add(path.trim());
        }
        promptInterval = webView.getPreferences().getInteger("CRIPageLoadPromptInterval", 10);
        errorPage = webView.getPreferences().getString("CRIAndroidErrorPage", null);

        final Activity activity = super.cordova.getActivity();
        final CordovaWebViewEngine engine = super.webView.getEngine();
        lifecycle = new RequestLifecycle(activity, engine, promptInterval);
    }

    private void onMessageTypeFailure(String messageId, Object data) {
        LOG.e(TAG, messageId + " received a data instance that is not an expected type:" + data.getClass().getName());
    }

    @Override
    public void onReset() {
        super.onReset();

        lifecycle.requestStopped();
    }

    @Override
    public Object onMessage(String id, Object data) {
        if (id.equals("onReceivedError")) {
            // Data is a JSONObject instance with the following keys:
            // * errorCode
            // * description
            // * url

            if (data instanceof JSONObject) {
                JSONObject json = (JSONObject) data;

                try {
                    if (isRemote(json.getString("url"))) {
                        lifecycle.setErrorOccured(true);
                        lifecycle.requestStopped();
                    }
                } catch (JSONException e) {
                    LOG.e(TAG, "Unexpected JSON in onReceiveError", e);
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        } else if (id.equals("onPageFinished")) {
            if (data instanceof String) {
                String url = (String) data;
                if (isRemote(url)) {
                    if (lifecycle.isErrorOccured()) {
                        webView.stopLoading();
                        if (errorPage != null) {
                            webView.loadUrlIntoView(errorPage, false);
                        }
                        new ErrorPrompt(lifecycle, super.cordova.getActivity(), webView.getEngine(), url).show();
                    } else {
                        injectCordova();
                        lifecycle.requestStopped();
                    }
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        } else if (id.equals("onPageStarted")) {
            if (data instanceof String) {
                String url = (String) data;

                if (isRemote(url)) {
                    lifecycle.requestStarted(url);
                }
            } else {
                onMessageTypeFailure(id, data);
            }
        }

        return null;
    }

    /**
     * @param url
     * @return true if the URL over HTTP or HTTPS
     */
    private boolean isRemote(String url) {
        return REMOTE_URL_REGEX.matcher((String) url).matches();
    }

    private void injectCordova() {
        List<String> jsPaths = new ArrayList<String>();
        for (String path : preInjectionFileNames) {
            jsPaths.add(path);
        }

        jsPaths.add("www/cordova.js");

        // We load the plugin code manually rather than allow cordova to load them (via
        // cordova_plugins.js). The reason for this is the WebView will attempt to load
        // the
        // file in the origin of the page (e.g.
        // https://truckmover.com/plugins/plugin/plugin.js).
        // By loading them first cordova will skip its loading process altogether.
        jsPaths.addAll(jsPathsToInject(cordova.getActivity().getResources().getAssets(), "www/plugins"));

        // Initialize the cordova plugin registry.
        jsPaths.add("www/cordova_plugins.js");

        // The way that I figured out to inject for android is to inject it as a script
        // tag with the full JS encoded as a data URI
        // (https://developer.mozilla.org/en-US/docs/Web/HTTP/data_URIs). The script tag
        // is appended to the DOM and executed via a javascript URL (e.g.
        // javascript:doJsStuff()).
        StringBuilder jsToInject = new StringBuilder();
        for (String path : jsPaths) {
            jsToInject.append(readFile(cordova.getActivity().getResources().getAssets(), path));
        }
        String jsUrl = "javascript:var script = document.createElement('script');";
        jsUrl += "script.src=\"data:text/javascript;charset=utf-8;base64,";

        jsUrl += Base64.encodeToString(jsToInject.toString().getBytes(), Base64.NO_WRAP);
        jsUrl += "\";";

        jsUrl += "document.getElementsByTagName('head')[0].appendChild(script);";

        webView.getEngine().loadUrl(jsUrl, false);
    }

    private String readFile(AssetManager assets, String filePath) {
        StringBuilder out = new StringBuilder();
        BufferedReader in = null;
        try {
            InputStream stream = assets.open(filePath);
            in = new BufferedReader(new InputStreamReader(stream));
            String str = "";

            while ((str = in.readLine()) != null) {
                out.append(str);
                out.append("\n");
            }
        } catch (MalformedURLException e) {
        } catch (IOException e) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return out.toString();
    }

    /**
     * Searches the provided path for javascript files recursively.
     *
     * @param assets
     * @param path   start path
     * @return found JS files
     */
    private List<String> jsPathsToInject(AssetManager assets, String path) {
        List jsPaths = new ArrayList<String>();

        try {
            for (String filePath : assets.list(path)) {
                String fullPath = path + File.separator + filePath;

                if (fullPath.endsWith(".js")) {
                    jsPaths.add(fullPath);
                } else {
                    List<String> childPaths = jsPathsToInject(assets, fullPath);
                    if (!childPaths.isEmpty()) {
                        jsPaths.addAll(childPaths);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return jsPaths;
    }

    private static class RequestLifecycle {
        private boolean errorOccured = false;

        private final Activity activity;
        private final CordovaWebViewEngine engine;
        private ErrorPrompt prompt;
        private final int promptInterval;
        private Timer timer;

        RequestLifecycle(Activity activity, CordovaWebViewEngine engine, int promptInterval) {
            this.activity = activity;
            this.engine = engine;
            this.promptInterval = promptInterval;
        }

        boolean isLoading() {
            return prompt != null;
        }

        boolean isErrorOccured() {
            return errorOccured;
        }

        void setErrorOccured(boolean errorOccured) {
            this.errorOccured = errorOccured;
        }

        void requestStopped() {
            stopTask();
        }

        void requestStarted(final String url) {
            errorOccured = false;
            startTask(url);
        }

        private synchronized void stopTask() {
            if (prompt != null) {
                prompt.cleanup();
                prompt = null;
            }
        }

        private synchronized void startTask(final String url) {
            if (prompt != null) {
                prompt.cleanup();
            }
            if (promptInterval > 0) {
                final ErrorPrompt prompt = new ErrorPrompt(this, activity, engine, url);
                final RequestLifecycle lifecycle = this;
                new Timer().schedule(new TimerTask() {
                    @Override
                    public boolean cancel() {
                        boolean result = super.cancel();
                        prompt.cleanup();
                        return result;
                    }

                    @Override
                    public void run() {
                        if (lifecycle.isLoading()) {
                            prompt.show();
                        } else {
                            lifecycle.stopTask();
                        }
                    }
                }, promptInterval * 1000);
            }
        }
    }

    /**
     * Prompt network error and retry button.
     */
    static class ErrorPrompt {
        private final RequestLifecycle lifecycle;
        private final Activity activity;
        private final CordovaWebViewEngine engine;
        final String url;

        AlertDialog alertDialog;

        ErrorPrompt(RequestLifecycle lifecycle, Activity activity, CordovaWebViewEngine engine, String url) {
            this.lifecycle = lifecycle;
            this.activity = activity;
            this.engine = engine;
            this.url = url;
        }

        private void cleanup() {
            if (alertDialog != null) {
                alertDialog.dismiss();
                alertDialog = null;
            }
        }

        public void show() {
            lifecycle.activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setMessage("サーバに接続できません.")
                            .setPositiveButton("再試行", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    // Obviously only works for GETs but good enough.
                                    engine.loadUrl(url, true);
                                    cleanup();
                                }
                            });
                    AlertDialog dialog = ErrorPrompt.this.alertDialog = builder.create();
                    dialog.setCancelable(false);
                    dialog.show();
                }
            });
        }
    }
}


