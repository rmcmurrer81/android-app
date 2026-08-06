package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public final class TrustedSyncActivity extends Activity {
    private TextView status;
    private Spinner peers;
    private ArrayAdapter<String> peerAdapter;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,30,30,30);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Trusted Sarah devices and trip photos");title.setTextSize(26);root.addView(title);
        TextView note=new TextView(this);note.setText("Pair only with your own Sarah desktop or laptop on trusted private Wi-Fi. Sharing Wi-Fi does not grant trust: Windows must show a matching six-digit code. Every payload is encrypted and signed. Sarah can remember several computers and synchronize each one.");note.setPadding(0,12,0,16);root.addView(note);
        EditText host=new EditText(this);host.setHint("Windows address, for example 192.168.1.25");root.addView(host);
        EditText code=new EditText(this);code.setHint("Six-digit code shown by that Windows Sarah");code.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(code);
        Button pair=new Button(this);pair.setText("Verify and pair this computer");pair.setOnClickListener(v->run(()->{TrustedSyncClient.pair(this,host.getText().toString().trim(),code.getText().toString().trim());runOnUiThread(this::refreshPeers);return "The computer was paired. It will not receive anything from a matching Wi-Fi network without this approval.";}));root.addView(pair);
        TextView saved=new TextView(this);saved.setText("Saved Sarah computers");saved.setPadding(0,18,0,5);root.addView(saved);
        peers=new Spinner(this);peerAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>());peers.setAdapter(peerAdapter);root.addView(peers);
        status=new TextView(this);status.setPadding(0,12,0,12);root.addView(status);
        Button selected=new Button(this);selected.setText("Sync selected computer now");selected.setOnClickListener(v->{String h=selectedHost();if(h.isEmpty()){status.setText("No computer is paired.");return;}TrustedDeviceStore.select(this,h);run(()->TrustedSyncClient.syncHost(this,h).optString("message","Sync completed."));});root.addView(selected);
        Button all=new Button(this);all.setText("Sync desktop and laptop now");all.setOnClickListener(v->run(()->TrustedSyncClient.syncAll(this).optString("message","Sync completed.")));root.addView(all);
        Button revoke=new Button(this);revoke.setText("Revoke selected computer");revoke.setOnClickListener(v->{String h=selectedHost();if(!h.isEmpty())TrustedDeviceStore.revoke(this,h);refreshPeers();status.setText("That computer's saved token was revoked on this phone.");});root.addView(revoke);
        refreshPeers();setContentView(scroll);
    }

    private void refreshPeers(){List<String> list=TrustedDeviceStore.hosts(this);peerAdapter.clear();peerAdapter.addAll(list);peerAdapter.notifyDataSetChanged();String selected=TrustedDeviceStore.host(this);int index=list.indexOf(selected);if(index>=0)peers.setSelection(index);}
    private String selectedHost(){Object value=peers.getSelectedItem();return value==null?"":value.toString();}
    private interface Work{String run()throws Exception;}
    private void run(Work work){status.setText("Working…");new Thread(()->{try{String result=work.run();runOnUiThread(()->status.setText(result));}catch(Exception e){runOnUiThread(()->status.setText("Could not complete: "+e.getMessage()));}},"Sarah-Trusted-Sync").start();}
}
