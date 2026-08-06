package com.kiraworld.sarahtravel;
import android.app.Activity; import android.content.Context; import android.content.Intent; import android.util.AttributeSet; import android.widget.Button; import java.util.Map;
public final class ProactiveDiscoveryButton extends Button {
    public ProactiveDiscoveryButton(Context c){super(c);init();} public ProactiveDiscoveryButton(Context c,AttributeSet a){super(c,a);init();} public ProactiveDiscoveryButton(Context c,AttributeSet a,int s){super(c,a,s);init();}
    private void init(){setAllCaps(false);setText("✨ Sarah discoveries");setContentDescription("Open Sarah's source-backed proactive discoveries");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),DiscoveryActivity.class)));}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow(); SarahDatabase db=new SarahDatabase(getContext()); PersonProfileStore people=new PersonProfileStore(getContext()); try{Map<String,String> owner=db.getProfile();people.ensureOwner(owner);Map<String,String> p=people.getActiveProfile();String n=p.getOrDefault("name",owner.getOrDefault("name","Traveler"));ProactiveDiscoveryStore s=new ProactiveDiscoveryStore(getContext());try{int c=s.count(n);setText(c>0?"✨ Discoveries ("+c+")":"✨ Sarah discoveries");}finally{s.close();}}finally{people.close();db.close();}}
}
