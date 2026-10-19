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
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private int blue = Color.rgb(16, 103, 211);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); api = new ApiClient(this); buildShell();
        if (!api.configured()) showSetup(); else { requestNotificationPermission(); startSync(); showInventory(); }
        receiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { status.setText("Live data updated"); } };
        IntentFilter f = new IntentFilter(SyncService.ACTION_DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
    }

    private void buildShell() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(12),dp(14),dp(10)); root.setBackgroundColor(Color.rgb(247,249,252));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(0,0,0,dp(8));
        ImageView logo = new ImageView(this); logo.setImageResource(tn.novaera.erp.mobile.R.drawable.nova_logo); logo.setScaleType(ImageView.ScaleType.CENTER_CROP); head.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView title = text("NovaEraERP Mobile",22,true); LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1); tp.setMargins(dp(10),0,0,0); head.addView(title,tp);
        Button gear=button("⚙"); gear.setOnClickListener(v->showSetup()); head.addView(gear,new LinearLayout.LayoutParams(dp(52),dp(44))); root.addView(head);
        status=text("",12,false); status.setTextColor(Color.DKGRAY); root.addView(status);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1); root.addView(content,cp);
        LinearLayout nav=new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(0,dp(8),0,0);
        Button inv=button("Inventory"); Button ord=button("Orders"); Button ret=button("Manual Returns");
        inv.setOnClickListener(v->showInventory()); ord.setOnClickListener(v->showOrders(false)); ret.setOnClickListener(v->showOrders(true));
        nav.addView(inv,new LinearLayout.LayoutParams(0,dp(52),1)); nav.addView(ord,new LinearLayout.LayoutParams(0,dp(52),1)); nav.addView(ret,new LinearLayout.LayoutParams(0,dp(52),1)); root.addView(nav);
        setContentView(root);
    }

    private void showSetup() {
        content.removeAllViews(); stopSync();
        TextView h=text("Connect to your NovaEraERP PC",20,true); content.addView(h);
        TextView note=text("Use the Tailscale/LAN address shown by SHOW_MOBILE_PAIRING.bat on the ERP computer. Example: http://100.x.x.x:8767",14,false); note.setPadding(0,dp(8),0,dp(8)); content.addView(note);
        SharedPreferences p=getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE);
        EditText url=input("Server URL",p.getString("server_url","")); EditText key=input("Mobile key",p.getString("mobile_key","")); key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(url); content.addView(key);
        Button save=button("Test & Save Connection"); content.addView(save,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
        save.setOnClickListener(v->{ api.saveConfig(url.getText().toString(),key.getText().toString()); status.setText("Testing connection…"); executor.submit(()->{
            try { api.health(); JSONObject me=api.get("/mobile-api/me"); runOnUiThread(()->{ toast("Connected as "+me.optString("user","Boss")); requestNotificationPermission(); startSync(); maybeShowBatteryTip(); showInventory(); }); }
            catch(Exception e){ runOnUiThread(()->{ status.setText("Connection failed: "+e.getMessage()); toast("Check server address and mobile key"); }); }
        }); });
    }

    private void showInventory() {
        content.removeAllViews(); if(!api.configured()){showSetup();return;}
        LinearLayout searchRow=new LinearLayout(this); EditText search=input("Search product / color / SKU",""); Button go=button("Search"); searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(50),1)); searchRow.addView(go,new LinearLayout.LayoutParams(dp(90),dp(50))); content.addView(searchRow);
        ListView list=new ListView(this); content.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Runnable load=()->{ status.setText("Loading inventory…"); final String term=search.getText().toString(); executor.submit(()->{ try { JSONObject r=api.inventory(term); JSONArray a=r.optJSONArray("inventory"); List<String> rows=new ArrayList<>(); if(a!=null) for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i); String variant=x.optString("variant",""); rows.add(x.optString("display_name",x.optString("name",""))+(variant.isEmpty()?"":" — "+variant)+"\nStock: "+x.optInt("stock")+(x.optString("sku","").isEmpty()?"":"   SKU: "+x.optString("sku")));} getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE).edit().putString("inventory_cache",r.toString()).putString("state_token",r.optString("state_token","")).apply(); runOnUiThread(()->{list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){@Override public View getView(int pos,View cv,ViewGroup par){TextView tv=(TextView)super.getView(pos,cv,par);tv.setTextSize(16);tv.setPadding(dp(8),dp(12),dp(8),dp(12));return tv;}});status.setText("Live inventory • "+rows.size()+" variants");}); } catch(Exception e){ runOnUiThread(()->{ loadCachedInventory(list); status.setText("Offline/cache: "+e.getMessage()); }); } });};
        go.setOnClickListener(v->load.run()); load.run();
    }

    private void loadCachedInventory(ListView list) {
        try { JSONObject r=new JSONObject(getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE).getString("inventory_cache","{}")); JSONArray a=r.optJSONArray("inventory"); List<String> rows=new ArrayList<>(); if(a!=null) for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);rows.add(x.optString("display_name",x.optString("name",""))+(x.optString("variant","").isEmpty()?"":" — "+x.optString("variant"))+"\nStock: "+x.optInt("stock"));} list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows)); } catch(Exception ignored){}
    }

    private void showOrders(boolean returnsOnly) {
        content.removeAllViews(); if(!api.configured()){showSetup();return;}
        TextView heading=text(returnsOnly?"Manual Returns":"Order Search",20,true); content.addView(heading);
        LinearLayout searchRow=new LinearLayout(this); EditText search=input("Client / phone / order ref",""); Button go=button("Search"); searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(50),1)); searchRow.addView(go,new LinearLayout.LayoutParams(dp(90),dp(50))); content.addView(searchRow);
        ListView list=new ListView(this); content.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Runnable load=()->{status.setText("Searching orders…");final String term=search.getText().toString();executor.submit(()->{try{JSONObject r=api.orders(term);JSONArray a=r.optJSONArray("orders");List<JSONObject> data=new ArrayList<>();List<String> labels=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);if(returnsOnly&&!x.optBoolean("manual_return_allowed"))continue;data.add(x);String carrier=x.optString("shipper_name","");labels.add("#"+x.optString("external_reference",x.optString("external_id",String.valueOf(x.optLong("id"))))+" • "+x.optString("customer_name","")+"\n"+x.optString("phone","")+" • "+x.optString("status","")+(carrier.isEmpty()?"":" • "+carrier));}runOnUiThread(()->{list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){@Override public View getView(int pos,View cv,ViewGroup par){TextView tv=(TextView)super.getView(pos,cv,par);tv.setTextSize(15);tv.setPadding(dp(8),dp(12),dp(8),dp(12));return tv;}});list.setOnItemClickListener((p,v,pos,id)->showOrder(data.get(pos)));status.setText((returnsOnly?"Manual-return eligible: ":"Orders: ")+labels.size());});}catch(Exception e){runOnUiThread(()->status.setText("Order search failed: "+e.getMessage()));}});};
        go.setOnClickListener(v->load.run()); load.run();
    }

    private void showOrder(JSONObject order) {
        content.removeAllViews();
        ScrollView scroll=new ScrollView(this); LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); scroll.addView(box); content.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        Button back=button("← Back"); back.setOnClickListener(v->showOrders(false)); box.addView(back,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        TextView h=text("Order #"+order.optString("external_reference",order.optString("external_id",String.valueOf(order.optLong("id")))),20,true);box.addView(h);
        box.addView(text(order.optString("customer_name","")+"\n"+order.optString("phone","")+"\n"+order.optString("address","")+"\nStatus: "+order.optString("status","")+"\nDelivery: "+order.optString("shipper_name",order.optString("delivery_company","")),15,false));
        JSONArray items=order.optJSONArray("items"); if(items!=null) for(int i=0;i<items.length();i++){JSONObject it=items.optJSONObject(i); if(it==null)continue; String name=it.optString("base_name",it.optString("product_name","")); String var=it.optString("variant_label",""); int remaining=it.optInt("returnable_qty",0); Button b=button(name+(var.isEmpty()?"":" — "+var)+"\nQty: "+it.optInt("total_units")+" • Returnable: "+remaining); b.setAllCaps(false); final JSONObject item=it; b.setEnabled(order.optBoolean("manual_return_allowed")&&remaining>0); b.setOnClickListener(v->showReturnDialog(order,item)); box.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(70))); }
        if(!order.optBoolean("manual_return_allowed")){TextView r=text(order.optString("manual_return_reason","Manual return unavailable"),14,true);r.setTextColor(Color.rgb(180,60,45));box.addView(r);}
    }

    private void showReturnDialog(JSONObject order, JSONObject item) {
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText qty=input("Quantity","1");qty.setInputType(InputType.TYPE_CLASS_NUMBER);box.addView(qty);
        Spinner condition=new Spinner(this);condition.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"SELLABLE","DAMAGED"}));box.addView(condition);
        EditText notes=input("Notes (optional)","");box.addView(notes);
        new AlertDialog.Builder(this).setTitle("Accept manual return").setMessage("Only manual/own-delivery returns can be processed here. FIABILO/carrier returns stay read-only.").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->{
            int q;try{q=Integer.parseInt(qty.getText().toString());}catch(Exception e){q=0;}int max=item.optInt("returnable_qty",0);if(q<1||q>max){toast("Quantity must be 1–"+max);return;}String cond=String.valueOf(condition.getSelectedItem());String idem=UUID.randomUUID().toString();status.setText("Saving manual return…");int finalQ=q;executor.submit(()->{try{api.submitManualReturn(order.optLong("id"),item.optLong("id"),finalQ,cond,notes.getText().toString(),idem);runOnUiThread(()->{toast(cond.equals("SELLABLE")?"Return accepted — stock restored":"Damaged return accepted — stock not restored");showOrders(true);});}catch(Exception e){runOnUiThread(()->{status.setText("Return failed: "+e.getMessage());toast("Nothing was changed");});}});
        }).show();
    }

    private void startSync() {
        if(!api.configured())return; Intent i=new Intent(this,SyncService.class); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    }
    private void stopSync(){try{stopService(new Intent(this,SyncService.class));}catch(Exception ignored){}}
    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},723);}
    private void maybeShowBatteryTip(){SharedPreferences p=getSharedPreferences(ApiClient.PREFS,MODE_PRIVATE);if(p.getBoolean("battery_tip",false))return;p.edit().putBoolean("battery_tip",true).apply();new AlertDialog.Builder(this).setTitle("Background live sync").setMessage("For the most reliable sync while using other apps, allow NovaEraERP unrestricted background/battery access if your phone offers that option.").setNegativeButton("Later",null).setPositiveButton("Open battery settings",(d,w)->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){}}).show();}
    private EditText input(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextSize(16);e.setSingleLine(true);e.setPadding(dp(10),0,dp(10),0);return e;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setBackgroundColor(blue);return b;}
    private TextView text(String t,int size,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(Color.rgb(20,35,55));v.setPadding(0,dp(4),0,dp(4));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Exception ignored){}executor.shutdownNow();super.onDestroy();}
}
