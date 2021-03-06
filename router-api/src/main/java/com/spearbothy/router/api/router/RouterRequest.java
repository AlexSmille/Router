package com.spearbothy.router.api.router;

import android.content.Context;
import android.text.TextUtils;

import com.spearbothy.router.api.Constants;
import com.spearbothy.router.api.ResultCallback;
import com.spearbothy.router.api.util.Logger;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author mahao
 * @date 2018/6/27 下午3:19
 * @email zziamahao@163.com
 */

public class RouterRequest {

    public static final String URL_PATTERN = "(router)://([a-zA-Z0-9_]+)(/[a-zA-Z0-9_]+)((\\?([a-zA-Z0-9]+=[^&]+&)*)?)";

    // 保留参数的key
    private static final String PARAMS_INTENT_FLAG = "intentFlag";
    private static final String PARAMS_ACTIVITY_REQUEST_CODE = "activityReqCode";


    private Context context;
    private String url;
    private String protocol = Constants.ROUTER_PROTOCOL;
    private String module; // 模块名
    private String path;
    private Map<String, String> params = new HashMap<>();
    private List<Integer> intentFlags = new ArrayList<>();
    private ResultCallback callback;
    private int activityRequestCode;

    public RouterRequest(Context context) {
        this.context = context;
    }

    public RouterRequest url(String url) {
        this.url = url;
        return this;
    }

    public RouterRequest param(String key, String value) {
        params.put(key, value);
        return this;
    }

    public RouterRequest module(String module) {
        this.module = module;
        return this;
    }

    public RouterRequest path(String path) {
        this.path = path;
        return this;
    }

    public RouterRequest activityRequest(int activityRequestCode) {
        this.activityRequestCode = activityRequestCode;
        return this;
    }

    public RouterRequest addIntentFlag(int flag) {
        intentFlags.add(flag);
        return this;
    }

    public List<Integer> getIntentFlags() {
        return intentFlags;
    }

    public void start() {
        start(null);
    }

    public void start(ResultCallback resultCallback) {
        callback = resultCallback;

        if (initProtocol()) {
            // 获取flag 参数
            RouterClient.process(this);
        } else {
            if (callback != null) {
                Response response = new Response();
                response.setError(Response.CODE_FAIL_PROTOCOL, "协议不合法");
                callback.onError(response);
            }
        }
    }

    private boolean initProtocol() {
        if (TextUtils.isEmpty(url)) {
            return !TextUtils.isEmpty(protocol) && !TextUtils.isEmpty(module) && !TextUtils.isEmpty(path);
        } else {
            String[] result = parserUrl(url);
            if (result != null) {
                protocol = result[0];
                module = result[1];
                path = result[2];
                if (!TextUtils.isEmpty(result[3])) {
                    loadUrlParams(result[3].substring(
                            1), params);
                }
                String intentFlagsStr = params.get(PARAMS_INTENT_FLAG);
                if (!TextUtils.isEmpty(intentFlagsStr)) {
                    try {
                        JSONArray array = new JSONArray(intentFlagsStr);
                        for (int i = 0; i < array.length(); i++) {
                            intentFlags.add(array.getInt(i));
                        }
                    } catch (Exception e) {
                        Logger.error(PARAMS_INTENT_FLAG + " 传值不合法，需传入JSONArray：" + intentFlagsStr, e);
                        return false;
                    }
                }

                String activityReqCode = params.get(PARAMS_ACTIVITY_REQUEST_CODE);
                if (!TextUtils.isEmpty(activityReqCode)) {
                    try {
                        activityRequestCode = Integer.parseInt(activityReqCode);
                    } catch (NumberFormatException e) {
                        Logger.error(PARAMS_ACTIVITY_REQUEST_CODE + " 传值不合法，需传入int：" + activityReqCode, e);
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private String[] parserUrl(String url) {
        if (url.contains("?") && !url.endsWith("&")) {
            // 正则表达式实在不知道怎么过滤&了
            url += "&";
        }

        Pattern r = Pattern.compile(URL_PATTERN);
        Matcher m = r.matcher(url);
        if (m.matches()) {
            String[] result = new String[4];
            result[0] = m.group(1); // protocol
            result[1] = m.group(2); // module
            result[2] = m.group(3); // path
            result[3] = m.group(4); // params
            return result;
        }
        return null;
    }

    private void loadUrlParams(String param, Map<String, String> map) {
        if (param == null) {
            return;
        }
        String[] params = param.split("&");
        for (int i = 0; i < params.length; i++) {
            String[] p = params[i].split("=");
            if (p.length == 2) {
                map.put(p[0], p[1]);
            }
        }
    }

    public Context getContext() {
        return context;
    }

    public String getUrl() {
        return url;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return module;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public ResultCallback getCallback() {
        return callback;
    }

    public int getActivityRequestCode() {
        return activityRequestCode;
    }

    @Override
    public String toString() {
        return "RouterRequest{" +
                "context=" + context +
                ", url='" + url + '\'' +
                ", protocol='" + protocol + '\'' +
                ", module='" + module + '\'' +
                ", path='" + path + '\'' +
                ", params=" + params +
                ", callback=" + callback +
                '}';
    }
}
