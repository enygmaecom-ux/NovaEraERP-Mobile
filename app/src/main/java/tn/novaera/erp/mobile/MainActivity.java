package tn.novaera.erp.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.graphics.Rect;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private ApiClient api;
    private LinearLayout root, content;
    private TextView status;
    private BroadcastReceiver receiver;
    private final int blue = Color.rgb(16, 103, 211);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new ApiClient(this);
        buildShell();
        if (!api.configured()) {
            showSetup();
        } else {
            requestNotificationPermission();
            startSync();
            showInventory();
        }
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                status.setText("Live data updated");
            }
        };
        IntentFilter f = new IntentFilter(SyncService.ACTION_DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247,249,252));
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            root.setPadding(dp(14), Math.max(dp(10), top + dp(4)), dp(14), Math.max(dp(10), bottom + dp(8)));
            return insets;
        });

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0,0,0,dp(6));
        ImageView logo = new ImageView(this);
        logo.setImageResource(tn.novaera.erp.mobile.R.drawable.nova_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        head.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView title = text("NovaEraERP Mobile",22,true);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);
        tp.setMargins(dp(10),0,0,0);
        head.addView(title,tp);
        Button gear=button("⚙");
        gear.setOnClickListener(v->showSetup());
        head.addView(gear,new LinearLayout.LayoutParams(dp(54),dp(46)));
        root.addView(head);

        LinearLayout nav=new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0,dp(4),0,dp(4));
        Button inv=button("INVENTORY");
        Button ord=button("ORDERS");
        Button ret=button("MANUAL\nRETURNS");
        inv.setTextSize(13); ord.setTextSize(13); ret.setTextSize(12);
        inv.setOnClickListener(v->showInventory());
        ord.setOnClickListener(v->showOrders(false));
        ret.setOnClickListener(v->showOrders(true));
        LinearLayout.LayoutParams np1=new LinearLayout.LayoutParams(0,dp(54),1); np1.setMargins(0,0,dp(4),0);
        LinearLayout.LayoutParams np2=new LinearLayout.LayoutParams(0,dp(54),1); np2.setMargins(dp(2),0,dp(2),0);
        LinearLayout.LayoutParams np3=new LinearLayout.LayoutParams(0,dp(54),1); np3.setMargins(dp(4),0,0,0);
        nav.addView(inv,np1); nav.addView(ord,np2); nav.addView(ret,np3);
        root.addView(nav);

        status=text("",13,false);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0,dp(5),0,dp(6));
        status.setMaxLines(4);
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1);
        root.addView(content,cp);
        setContentView(root);
        root.requestApplyInsets();
    }

    private boolean tailscaleServer() {
        try {
            String host = new URL(api.baseUrl()).getHost();
            String[] p = host.split("\\.");
            if (p.length != 4) return false;
            int a=Integer.parseInt(p[0]), b=Integer.parseInt(p[1]);
            return a==100 && b>=64 && b<=127;
        } catch(Exception e) { return false; }
    }

    private boolean vpnActive() {
        try {
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(cm==null) return false;
            for(Network n: cm.getAllNetworks()) {
                NetworkCapabilities c=cm.getNetworkCapabilities(n);
                if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch(Exception ignored) {}
        return false;
    }

    private String connectionError(Exception e) {
        if (tailscaleServer() && !vpnActive()) {
            return "Tailscale is OFF on this phone. Open Tailscale and connect, then retry.";
        }
        return "Cannot reach ERP PC at " + api.baseUrl() + ". Check that the PC is on, Mobile Access is running, and Tailscale is connected.";
    }

    private boolean requireConnectionPath() {
        if (tailscaleServer() && !vpnActive()) {
            content.removeAllViews();
            TextView h=text("Tailscale connection required",20,true);
            content.addView(h);
            TextView m=text("This app is linked to your ERP PC through Tailscale. Tailscale is not active on this phone, so Android cannot reach 100.91.28.92.",15,false);
            m.setPadding(0,dp(10),0,dp(14));
            content.addView(m);
            Button open=button("OPEN TAILSCALE");
            open.setOnClickListener(v->openTailscale());
            content.addView(open,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
            Button retry=button("RETRY CONNECTION");
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)); rp.setMargins(0,dp(10),0,0);
            retry.setOnClickListener(v->showInventory());
            content.addView(retry,rp);
            status.setText("Tailscale is OFF — live ERP connection unavailable.");
            return false;
        }
        return true;
    }

    private void openTailscale() {
        try {
            Intent i=getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if(i!=null) { startActivity(i); return; }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn")));
        } catch(Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))); }
            catch(Exception ignored) { toast("Install/open Tailscale, then return to NovaEraERP Mobile."); }
        }
    }

    private void showSetup() {
        content.removeAllViews();
        TextView h=text("Mobile connection",20,true);
        content.addView(h);
        TextView note=text("The app can use the pre-linked server automatically. Change these fields only if the ERP PC address or mobile key changes.",14,false);
        note.setPadding(0,dp(8),0,dp(8));
        content.addView(note);
        EditText url=input("Server URL",api.baseUrl());
        EditText key=input("Mobile key",api.key());
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(url); content.addView(key);
        Button save=button("TEST & SAVE CONNECTION");
        content.addView(save,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
        save.setOnClickListener(v->{
            api.saveConfig(url.getText().toString(),key.getText().toString());
            status.setText("Testing connection…");
            executor.submit(()->{
                try {
                    api.health();
                    JSONObject me=api.get("/mobile-api/me");
                    runOnUiThread(()->{
                        toast("Connected as "+me.optString("user","Boss"));
                        requestNotificationPermission();
                        startSync();
                        maybeShowBatteryTip();
                        showInventory();
                    });
                } catch(Exception e) {
                    runOnUiThread(()->{
                        status.setText(connectionError(e));
                        toast("Connection failed");
                    });
                }
            });
        });
    }

    private void showInventory() {
        content.removeAllViews();
        if(!api.configured()){showSetup();return;}
        if(!requireConnectionPath()) return;
        LinearLayout searchRow=new LinearLayout(this);
        EditText search=input("Search product / color / SKU","");
        Button go=button("SEARCH");
        searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(50),1));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(100),dp(50)); gp.setMargins(dp(6),0,0,0);
        searchRow.addView(go,gp);
        content.addView(searchRow);
        ListView list=new ListView(this);
        content.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Runnable load=()->{
            status.setText("Loading inventory…");
            final String term=search.getText().toString();
            executor.submit(()->{
                try {
                    JSONObject r=api.inventory(term);
                    JSONArray a=r.optJSONArray("inventory");
                    List<String> rows=new ArrayList<>();
                    if(a!=null) for(int i=0;i<a.length();i++){
                        JSONObject x=a.getJSONObject(i);
                        String variant=x.optString("variant","");
                        rows.add(x.optString("display_name",x.optString("name",""))+(variant.isEmpty()?"":" — "+variant)+"\nStock: "+x.optInt("stock")+(x.optString("sku","").isEmpty()?"":"   SKU: "+x.optString("sku")));
                    }
                    getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE).edit().putString("inventory_cache",r.toString()).putString("state_token",r.optString("state_token","")).apply();
                    runOnUiThread(()->{
                        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){
                            @Override public View getView(int pos,View cv,ViewGroup par){
                                TextView tv=(TextView)super.getView(pos,cv,par);
                                tv.setTextSize(16); tv.setPadding(dp(8),dp(12),dp(8),dp(12)); return tv;
                            }
                        });
                        status.setText("Live inventory • "+rows.size()+" variants");
                    });
                } catch(Exception e){
                    runOnUiThread(()->{
                        loadCachedInventory(list);
                        status.setText(connectionError(e));
                    });
                }
            });
        };
        go.setOnClickListener(v->load.run());
        load.run();
    }

    private void loadCachedInventory(ListView list) {
        try {
            JSONObject r=new JSONObject(getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE).getString("inventory_cache","{}"));
            JSONArray a=r.optJSONArray("inventory");
            List<String> rows=new ArrayList<>();
            if(a!=null) for(int i=0;i<a.length();i++){
                JSONObject x=a.getJSONObject(i);
                rows.add(x.optString("display_name",x.optString("name",""))+(x.optString("variant","").isEmpty()?"":" — "+x.optString("variant"))+"\nStock: "+x.optInt("stock"));
            }
            list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
        } catch(Exception ignored){}
    }

    private void showOrders(boolean returnsOnly) {
        content.removeAllViews();
        if(!api.configured()){showSetup();return;}
        if(!requireConnectionPath()) return;
        TextView heading=text(returnsOnly?"Manual Returns":"Order Search",20,true);
        content.addView(heading);
        LinearLayout searchRow=new LinearLayout(this);
        EditText search=input("Client / phone / order ref","");
        Button go=button("SEARCH");
        searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(50),1));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(100),dp(50)); gp.setMargins(dp(6),0,0,0);
        searchRow.addView(go,gp);
        content.addView(searchRow);
        ListView list=new ListView(this);
        content.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Runnable load=()->{
            status.setText(returnsOnly?"Loading manual-return orders…":"Searching orders…");
            final String term=search.getText().toString();
            executor.submit(()->{
                try{
                    JSONObject r=api.orders(term);
                    JSONArray a=r.optJSONArray("orders");
                    List<JSONObject> data=new ArrayList<>();
                    List<String> labels=new ArrayList<>();
                    if(a!=null)for(int i=0;i<a.length();i++){
                        JSONObject x=a.getJSONObject(i);
                        if(returnsOnly&&!x.optBoolean("manual_return_allowed"))continue;
                        data.add(x);
                        String carrier=x.optString("shipper_name","");
                        labels.add("#"+x.optString("external_reference",x.optString("external_id",String.valueOf(x.optLong("id"))))+" • "+x.optString("customer_name","")+"\n"+x.optString("phone","")+" • "+x.optString("status","")+(carrier.isEmpty()?"":" • "+carrier));
                    }
                    runOnUiThread(()->{
                        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
                            @Override public View getView(int pos,View cv,ViewGroup par){
                                TextView tv=(TextView)super.getView(pos,cv,par);
                                tv.setTextSize(15);tv.setPadding(dp(8),dp(12),dp(8),dp(12));return tv;
                            }
                        });
                        list.setOnItemClickListener((p,v,pos,id)->showOrder(data.get(pos)));
                        status.setText((returnsOnly?"Manual-return eligible: ":"Orders: ")+labels.size());
                    });
                }catch(Exception e){
                    runOnUiThread(()->status.setText(connectionError(e)));
                }
            });
        };
        go.setOnClickListener(v->load.run());
        load.run();
    }

    private void showOrder(JSONObject order) {
        content.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);
        content.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Button back=button("← BACK TO ORDERS");
        back.setOnClickListener(v->showOrders(false));
        box.addView(back,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        TextView h=text("Order #"+order.optString("external_reference",order.optString("external_id",String.valueOf(order.optLong("id")))),20,true);
        box.addView(h);
        box.addView(text(order.optString("customer_name","")+"\n"+order.optString("phone","")+"\n"+order.optString("address","")+"\nStatus: "+order.optString("status","")+"\nDelivery: "+order.optString("shipper_name",order.optString("delivery_company","")),15,false));
        JSONArray items=order.optJSONArray("items");
        if(items!=null) for(int i=0;i<items.length();i++){
            JSONObject it=items.optJSONObject(i); if(it==null)continue;
            String name=it.optString("base_name",it.optString("product_name",""));
            String var=it.optString("variant_label","");
            int remaining=it.optInt("returnable_qty",0);
            Button b=button(name+(var.isEmpty()?"":" — "+var)+"\nQty: "+it.optInt("total_units")+" • Returnable: "+remaining);
            b.setAllCaps(false);
            final JSONObject item=it;
            b.setEnabled(order.optBoolean("manual_return_allowed")&&remaining>0);
            b.setOnClickListener(v->showReturnDialog(order,item));
            box.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(70)));
        }
        if(!order.optBoolean("manual_return_allowed")){
            TextView r=text(order.optString("manual_return_reason","Manual return unavailable"),14,true);
            r.setTextColor(Color.rgb(180,60,45));
            box.addView(r);
        }
    }

    private void showReturnDialog(JSONObject order, JSONObject item) {
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);
        EditText qty=input("Quantity","1");
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(qty);
        Spinner condition=new Spinner(this);
        condition.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"SELLABLE","DAMAGED"}));
        box.addView(condition);
        EditText notes=input("Notes (optional)","");
        box.addView(notes);
        new AlertDialog.Builder(this)
            .setTitle("Accept manual return")
            .setMessage("Only manual/own-delivery returns can be processed here. FIABILO/carrier returns stay read-only.")
            .setView(box)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Confirm",(d,w)->{
                int q;
                try{q=Integer.parseInt(qty.getText().toString());}catch(Exception e){q=0;}
                int max=item.optInt("returnable_qty",0);
                if(q<1||q>max){toast("Quantity must be 1–"+max);return;}
                String cond=String.valueOf(condition.getSelectedItem());
                String idem=UUID.randomUUID().toString();
                status.setText("Saving manual return…");
                int finalQ=q;
                executor.submit(()->{
                    try{
                        api.submitManualReturn(order.optLong("id"),item.optLong("id"),finalQ,cond,notes.getText().toString(),idem);
                        runOnUiThread(()->{
                            toast(cond.equals("SELLABLE")?"Return accepted — stock restored":"Damaged return accepted — stock not restored");
                            showOrders(true);
                        });
                    }catch(Exception e){
                        runOnUiThread(()->{
                            status.setText(connectionError(e));
                            toast("Nothing was changed");
                        });
                    }
                });
            }).show();
    }

    private void startSync() {
        if(!api.configured())return;
        Intent i=new Intent(this,SyncService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    }

    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},723);
    }

    private void maybeShowBatteryTip(){
        SharedPreferences p=getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE);
        if(p.getBoolean("battery_tip",false))return;
        p.edit().putBoolean("battery_tip",true).apply();
        new AlertDialog.Builder(this)
            .setTitle("Background live sync")
            .setMessage("For the most reliable sync while using other apps, allow NovaEraERP unrestricted background/battery access if your phone offers that option.")
            .setNegativeButton("Later",null)
            .setPositiveButton("Open battery settings",(d,w)->{
                try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){}
            }).show();
    }

    private EditText input(String hint,String value){
        EditText e=new EditText(this);
        e.setHint(hint); e.setText(value); e.setTextSize(16); e.setSingleLine(true); e.setPadding(dp(10),0,dp(10),0);
        return e;
    }

    private Button button(String label){
        Button b=new Button(this);
        b.setText(label); b.setTextSize(14); b.setTextColor(Color.WHITE); b.setBackgroundColor(blue);
        return b;
    }

    private TextView text(String t,int size,boolean bold){
        TextView v=new TextView(this);
        v.setText(t); v.setTextSize(size); v.setTextColor(Color.rgb(20,35,55)); v.setPadding(0,dp(4),0,dp(4));
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);
        return v;
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        executor.shutdownNow();
        super.onDestroy();
    }
}
